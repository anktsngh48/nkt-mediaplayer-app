package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Swipes through photos AND videos in the same order as the grid; videos play right here. */
public class ViewerActivity extends Activity {
    private List<MediaInfo> items = new ArrayList<>();
    private RecyclerView pager, strip;
    private LinearLayoutManager plm, slm;
    private int index;
    private View top, bottom;
    private TextView when, where, hint;
    private ImageButton heart;
    private boolean barsShown = true;
    private ExoPlayer player;
    private PlayerView pv;
    private int playingAt = -1;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final java.util.Set<String> logged = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        items = new ArrayList<>(Media.handoff);
        index = getIntent().getIntExtra("index", 0);
        Intent in = getIntent();
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            items = new ArrayList<>();
            ClipData cd = in.getClipData();
            if (cd != null && cd.getItemCount() > 1) for (int i = 0; i < cd.getItemCount(); i++) items.add(Media.fromUri(this, cd.getItemAt(i).getUri(), MediaInfo.IMAGE));
            else items.add(Media.fromUri(this, in.getData(), MediaInfo.IMAGE));
            index = 0;
        }
        if (items.isEmpty()) { finish(); return; }
        index = Math.max(0, Math.min(index, items.size() - 1));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        pager = new RecyclerView(this);
        plm = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        pager.setLayoutManager(plm);
        pager.setAdapter(new PageAdapter());
        new PagerSnapHelper().attachToRecyclerView(pager);
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int st) {
                if (st == RecyclerView.SCROLL_STATE_IDLE) {
                    int p = plm.findFirstCompletelyVisibleItemPosition();
                    if (p >= 0 && p != index) { index = p; onPage(); }
                }
            }
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (playingAt >= 0 && dx != 0) stopVideo();
            }
        });
        root.addView(pager, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        LinearLayout t = Ui.row(this);
        t.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0}));
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 26));
        ImageButton back = Ui.iconBtn(this, "back", 44, 0xFFFFFFFF);
        back.setOnClickListener(v -> finish());
        t.addView(back);
        LinearLayout tt = Ui.col(this);
        when = Ui.single(Ui.text(this, "", 14, 0xFFFFFFFF));
        where = Ui.single(Ui.text(this, "", 12, 0xFFB6C3D6));
        tt.addView(when);
        tt.addView(where);
        t.addView(tt, Ui.lpw(0, Ui.WRAP, 1));
        heart = Ui.iconBtn(this, "heart", 44, 0xFFFFFFFF);
        heart.setOnClickListener(v -> {
            MediaInfo m = items.get(index);
            boolean on = Store.toggleFav(this, m);
            Ui.msg(this, on ? "Added to " + Store.favName(m) : "Removed from " + Store.favName(m));
            updateHeart();
        });
        ImageButton share = Ui.iconBtn(this, "share", 44, 0xFFFFFFFF);
        share.setOnClickListener(v -> Media.share(this, Collections.singletonList(items.get(index))));
        ImageButton info = Ui.iconBtn(this, "info", 44, 0xFFFFFFFF);
        info.setOnClickListener(v -> Actions.details(this, items.get(index), () -> {
            if (Library.find(items.get(index).uri) == null && items.get(index).uri.startsWith("content://media")) finish();
        }));
        t.addView(heart);
        t.addView(share);
        t.addView(info);
        top = t;
        root.addView(t, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        LinearLayout bt = Ui.col(this);
        bt.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xD9000000, 0}));
        bt.setPadding(0, Ui.dp(this, 30), 0, Ui.dp(this, 16));
        strip = new RecyclerView(this);
        slm = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        strip.setLayoutManager(slm);
        strip.setAdapter(new StripAdapter());
        strip.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        strip.setClipToPadding(false);
        bt.addView(strip, Ui.lp(Ui.MATCH, Ui.dp(this, 52)));
        hint = Ui.text(this, "", 12, 0xFFB6C3D6);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 10), 0, 0);
        bt.addView(hint);
        bottom = bt;
        root.addView(bt, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        setContentView(root);
        Ui.edgeToEdge(this, root);
        root.setPadding(0, 0, 0, 0);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            top.setPadding(Ui.dp(this, 8), ins.getSystemWindowInsetTop() + Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 26));
            bottom.setPadding(0, Ui.dp(this, 30), 0, ins.getSystemWindowInsetBottom() + Ui.dp(this, 16));
            return ins;
        });
        pager.scrollToPosition(index);
        strip.scrollToPosition(index);
        onPage();
    }

    private void onPage() {
        MediaInfo m = items.get(index);
        when.setText(m.date > 0 ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(m.date)) : m.name);
        where.setText((m.folder.isEmpty() ? "" : m.folder + " · ") + (index + 1) + " of " + items.size() + (m.isVideo() ? " · Video " + Ui.time(m.duration) : ""));
        hint.setText(m.isVideo() ? "Video plays right here · swipe to keep going" : "Pinch or double-tap to zoom · swipe for photos and videos");
        updateHeart();
        strip.getAdapter().notifyDataSetChanged();
        int first = slm.findFirstVisibleItemPosition(), last = slm.findLastVisibleItemPosition();
        if (index < first + 1 || index > last - 1) strip.smoothScrollToPosition(index);
        if (logged.add(m.uri)) Store.addHistory(this, m);
    }

    private void updateHeart() {
        boolean on = Store.isFav(this, items.get(index));
        heart.setImageDrawable(new Icon(this, on ? "heart_filled" : "heart", on ? Ui.ACCENT : 0xFFFFFFFF));
    }

    private void toggleBars() {
        barsShown = !barsShown;
        top.animate().alpha(barsShown ? 1 : 0).setDuration(150).start();
        bottom.animate().alpha(barsShown ? 1 : 0).setDuration(150).start();
        top.setVisibility(View.VISIBLE);
        bottom.setVisibility(View.VISIBLE);
    }

    // ---------------- inline video ----------------
    private void playVideo(FrameLayout page, int pos) {
        stopVideo();
        if (player == null) {
            player = new ExoPlayer.Builder(this).build();
            pv = new PlayerView(this);
            pv.setUseController(true);
            pv.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
            pv.setPlayer(player);
        }
        MediaInfo m = items.get(pos);
        player.setMediaItem(MediaItem.fromUri(m.uri));
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.prepare();
        player.play();
        page.addView(pv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        playingAt = pos;
        pv.showController();
    }

    private void stopVideo() {
        if (player != null) player.pause();
        if (pv != null && pv.getParent() != null) ((ViewGroup) pv.getParent()).removeView(pv);
        playingAt = -1;
    }

    @Override protected void onPause() { super.onPause(); if (player != null) player.pause(); }

    @Override
    protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Actions.onResult(this, req, res);
    }

    final class PageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return items.size(); }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            FrameLayout f = new FrameLayout(ViewerActivity.this);
            f.setLayoutParams(new RecyclerView.LayoutParams(Ui.MATCH, Ui.MATCH));
            ZoomImageView z = new ZoomImageView(ViewerActivity.this);
            f.addView(z, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
            ImageButton play = new ImageButton(ViewerActivity.this);
            play.setImageDrawable(new Icon(ViewerActivity.this, "play", Ui.ACCENT));
            play.setBackground(Ui.oval(ViewerActivity.this, 0x9905080F, 0x8000E5FF, 2));
            int pd = Ui.dp(ViewerActivity.this, 22);
            play.setPadding(pd, pd, pd, pd);
            play.setScaleType(ImageView.ScaleType.FIT_CENTER);
            play.setContentDescription("Play video");
            f.addView(play, new FrameLayout.LayoutParams(Ui.dp(ViewerActivity.this, 76), Ui.dp(ViewerActivity.this, 76), Gravity.CENTER));
            return new RecyclerView.ViewHolder(f) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
            FrameLayout f = (FrameLayout) vh.itemView;
            if (pv != null && pv.getParent() == f && playingAt != pos) f.removeView(pv);
            ZoomImageView z = (ZoomImageView) f.getChildAt(0);
            View play = f.getChildAt(1);
            MediaInfo m = items.get(pos);
            z.onTap = ViewerActivity.this::toggleBars;
            z.setImageDrawable(null);
            String key = m.uri;
            z.setTag(key);
            play.setVisibility(m.isVideo() ? View.VISIBLE : View.GONE);
            play.setOnClickListener(v -> playVideo(f, vh.getAdapterPosition()));
            int px = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
            pool.execute(() -> {
                Bitmap b = null;
                try {
                    b = m.isVideo() ? Thumbs.make(getApplicationContext(), m, px / 2) : Thumbs.decode(getApplicationContext(), m.u(), px);
                } catch (Throwable ignored) {}
                final Bitmap fb = b;
                z.post(() -> { if (key.equals(z.getTag()) && fb != null) z.setImageBitmap(fb); });
            });
        }
    }

    final class StripAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return items.size(); }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            FrameLayout f = new FrameLayout(ViewerActivity.this);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(Ui.dp(ViewerActivity.this, 48), Ui.dp(ViewerActivity.this, 48));
            lp.setMargins(Ui.dp(ViewerActivity.this, 3), 0, Ui.dp(ViewerActivity.this, 3), 0);
            f.setLayoutParams(lp);
            ImageView iv = new ImageView(ViewerActivity.this);
            iv.setClipToOutline(true);
            f.addView(iv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
            TextView badge = Ui.text(ViewerActivity.this, "▶", 8, 0xFFFFFFFF);
            badge.setBackground(Ui.oval(ViewerActivity.this, 0xBF05080F, 0, 0));
            badge.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.dp(ViewerActivity.this, 16), Ui.dp(ViewerActivity.this, 16), Gravity.BOTTOM | Gravity.START);
            bp.setMargins(Ui.dp(ViewerActivity.this, 3), 0, 0, Ui.dp(ViewerActivity.this, 3));
            f.addView(badge, bp);
            return new RecyclerView.ViewHolder(f) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
            FrameLayout f = (FrameLayout) vh.itemView;
            ImageView iv = (ImageView) f.getChildAt(0);
            MediaInfo m = items.get(pos);
            iv.setBackground(Ui.rect(ViewerActivity.this, Ui.CELL, 8, pos == index ? Ui.ACCENT : 0, pos == index ? 2 : 0));
            int p = pos == index ? Ui.dp(ViewerActivity.this, 2) : 0;
            iv.setPadding(p, p, p, p);
            Thumbs.load(iv, m, Ui.dp(ViewerActivity.this, 96), m.isVideo() ? "video" : "image");
            f.getChildAt(1).setVisibility(m.isVideo() ? View.VISIBLE : View.GONE);
            f.setOnClickListener(v -> {
                int at = vh.getAdapterPosition();
                if (at < 0) return;
                stopVideo();
                index = at;
                pager.scrollToPosition(at);
                onPage();
            });
        }
    }
}
