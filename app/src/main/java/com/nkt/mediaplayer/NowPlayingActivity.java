package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class NowPlayingActivity extends Activity {
    private ImageView art;
    private TextView title, artist, pos, dur, sleepChip, upNext, speedLabel;
    private SeekBar seek;
    private ImageButton play, shuffle, repeat;
    private ImageView loveIcon;
    private TextView watchVideo;
    private MediaController c;
    private boolean dragging;
    private String shownUri = "";
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = Ui.col(this);
        col.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 20));
        sv.addView(col);
        sv.setBackgroundColor(Ui.BG);

        LinearLayout top = Ui.row(this);
        ImageButton back = Ui.roundBtn(this, "back", 44, Ui.TEXT, true);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView np = Ui.label(this, "Now playing", 11, Ui.ACCENT, 0.22f);
        np.setGravity(Gravity.CENTER);
        top.addView(np, Ui.lpw(0, Ui.WRAP, 1));
        ImageButton more = Ui.roundBtn(this, "more", 44, Ui.TEXT, true);
        more.setOnClickListener(v -> menu());
        top.addView(more);
        col.addView(top);

        sleepChip = Ui.text(this, "", 13, Ui.SUCCESS);
        sleepChip.setBackground(Ui.rect(this, 0x1200E5A0, 18, 0x7300E5A0, 1));
        sleepChip.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        sleepChip.setOnClickListener(v -> timerSheet());
        LinearLayout.LayoutParams sp = Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 12, 0, 0);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(sleepChip, sp);

        FrameLayout stage = new FrameLayout(this);
        art = new ImageView(this);
        art.setBackground(Ui.rect(this, 0x2200E5FF, 18, Ui.BORDER, 1));
        art.setClipToOutline(true);
        art.setElevation(Ui.dp(this, 12));
        int side = Math.min(Ui.dp(this, 280), getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 64));
        stage.addView(art, new FrameLayout.LayoutParams(side, side, Gravity.CENTER));
        col.addView(stage, Ui.lpm(this, Ui.MATCH, side + Ui.dp(this, 20), 0, 8, 0, 0));

        title = Ui.text(this, "", 20, Ui.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(2);
        col.addView(title, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
        artist = Ui.single(Ui.text(this, "", 14, Ui.MUTED));
        col.addView(artist, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));

        seek = new SeekBar(this);
        Ui.styleSeek(seek);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && c != null) pos.setText(Ui.time(c.getDuration() * p / 1000)); }
            public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            public void onStopTrackingTouch(SeekBar s) { dragging = false; if (c != null) c.seekTo(c.getDuration() * s.getProgress() / 1000); }
        });
        col.addView(seek, Ui.lpm(this, Ui.MATCH, Ui.WRAP, -16, 14, -16, 0));
        LinearLayout times = Ui.row(this);
        pos = Ui.text(this, "0:00", 12, Ui.MUTED);
        dur = Ui.text(this, "0:00", 12, Ui.MUTED);
        times.addView(pos, Ui.lpw(0, Ui.WRAP, 1));
        times.addView(dur);
        col.addView(times);

        LinearLayout ctr = Ui.row(this);
        ctr.setGravity(Gravity.CENTER);
        shuffle = Ui.iconBtn(this, "shuffle", 44, Ui.MUTED);
        shuffle.setOnClickListener(v -> { if (c != null) c.setShuffleModeEnabled(!c.getShuffleModeEnabled()); });
        ImageButton prev = Ui.roundBtn(this, "prev", 56, Ui.TEXT, true);
        prev.setOnClickListener(v -> { if (c != null) c.seekToPrevious(); });
        play = new ImageButton(this);
        play.setBackground(Ui.pressable(playBg(0x26), playBg(0x4D)));
        play.setImageDrawable(new Icon(this, "pause", Ui.ACCENT));
        int pp = Ui.dp(this, 22);
        play.setPadding(pp, pp, pp, pp);
        play.setScaleType(ImageView.ScaleType.FIT_CENTER);
        play.setOnClickListener(v -> { if (c != null) { if (c.isPlaying()) c.pause(); else c.play(); } });
        ImageButton next = Ui.roundBtn(this, "next", 56, Ui.TEXT, true);
        next.setOnClickListener(v -> { if (c != null) c.seekToNext(); });
        repeat = Ui.iconBtn(this, "repeat", 44, Ui.MUTED);
        repeat.setOnClickListener(v -> {
            if (c == null) return;
            int m = c.getRepeatMode();
            c.setRepeatMode(m == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL : m == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        });
        ctr.addView(shuffle);
        ctr.addView(Ui.space(this, -1, 1));
        ctr.addView(prev);
        ctr.addView(Ui.space(this, 14, 1));
        ctr.addView(play, Ui.lp(Ui.dp(this, 76), Ui.dp(this, 76)));
        ctr.addView(Ui.space(this, 14, 1));
        ctr.addView(next);
        ctr.addView(Ui.space(this, -1, 1));
        ctr.addView(repeat);
        col.addView(ctr, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));

        watchVideo = Ui.pill(this, "WATCH VIDEO", false, Ui.ACCENT);
        watchVideo.setOnClickListener(v -> watch());
        LinearLayout.LayoutParams wp = Ui.lpm(this, Ui.WRAP, Ui.dp(this, 46), 0, 12, 0, 0);
        wp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(watchVideo, wp);

        LinearLayout opts = Ui.row(this);
        opts.setBackground(Ui.rect(this, Ui.CARD, 16, Ui.BORDER, 1));
        opts.addView(option("eq", "EQ", v -> startActivity(new Intent(this, EqualizerActivity.class))), Ui.lpw(0, Ui.dp(this, 58), 1));
        opts.addView(option("timer", "TIMER", v -> timerSheet()), Ui.lpw(0, Ui.dp(this, 58), 1));
        View sOpt = option("speed", "1×", v -> timerSheet());
        speedLabel = (TextView) ((LinearLayout) sOpt).getChildAt(1);
        opts.addView(sOpt, Ui.lpw(0, Ui.dp(this, 58), 1));
        opts.addView(option("queue", "QUEUE", v -> queueSheet()), Ui.lpw(0, Ui.dp(this, 58), 1));
        View love = option("heart", "LOVE", v -> {
            MediaInfo m = current();
            if (m == null) return;
            boolean on = Store.toggleFav(this, m);
            Ui.msg(this, on ? "Added to " + Store.favName(m) : "Removed from " + Store.favName(m));
            updateLove();
        });
        loveIcon = (ImageView) ((LinearLayout) love).getChildAt(0);
        opts.addView(love, Ui.lpw(0, Ui.dp(this, 58), 1));
        col.addView(opts, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 18, 0, 0));

        upNext = Ui.single(Ui.text(this, "", 13, Ui.TEXT));
        upNext.setBackground(Ui.rect(this, 0x0F00E5FF, 14, Ui.BORDER, 1));
        upNext.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        upNext.setGravity(Gravity.CENTER_VERTICAL);
        upNext.setOnClickListener(v -> queueSheet());
        col.addView(upNext, Ui.lpm(this, Ui.MATCH, Ui.dp(this, 58), 0, 10, 0, 0));

        setContentView(sv);
        Ui.edgeToEdge(this, sv);
        handleIntent(getIntent());
    }

    private android.graphics.drawable.GradientDrawable playBg(int alpha) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BL_TR, new int[]{(alpha << 24) | 0x00E5FF, (alpha << 24) | 0x7B61FF});
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setStroke(Ui.dp(this, 2), 0x8000E5FF);
        return g;
    }

    private View option(String icon, String label, View.OnClickListener l) {
        LinearLayout o = Ui.col(this);
        o.setGravity(Gravity.CENTER);
        ImageView i = new ImageView(this);
        i.setImageDrawable(new Icon(this, icon, Ui.ACCENT));
        o.addView(i, Ui.lp(Ui.dp(this, 20), Ui.dp(this, 20)));
        TextView t = Ui.label(this, label, 8.5f, Ui.MUTED, 0.08f);
        t.setPadding(0, Ui.dp(this, 5), 0, 0);
        o.addView(t);
        o.setOnClickListener(l);
        o.setBackground(Ui.pressable(Ui.rect(this, 0, 12, 0, 0), Ui.rect(this, 0x1400E5FF, 12, 0, 0)));
        return o;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    /** "Open with" from another app: one file or several replace the queue. */
    private void handleIntent(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return;
        List<MediaInfo> l = new ArrayList<>();
        ClipData cd = i.getClipData();
        if (cd != null && cd.getItemCount() > 1) {
            for (int k = 0; k < cd.getItemCount(); k++) if (cd.getItemAt(k).getUri() != null) l.add(Media.fromUri(this, cd.getItemAt(k).getUri(), MediaInfo.AUDIO));
        } else {
            Uri u = i.getData();
            l.add(Media.fromUri(this, u, MediaInfo.AUDIO));
        }
        Media.playSongs(this, l, 0, false);
        setIntent(new Intent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Media.get(this, ctrl -> {
            c = ctrl;
            c.addListener(listener);
            refresh();
        });
        h.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        h.removeCallbacks(tick);
        if (c != null) c.removeListener(listener);
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override public void onEvents(@NonNull Player p, @NonNull Player.Events e) { refresh(); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (c != null && !dragging) {
                long d = Math.max(0, c.getDuration());
                long p = c.getCurrentPosition();
                pos.setText(Ui.time(p));
                dur.setText(Ui.time(d));
                seek.setProgress(d > 0 ? (int) (p * 1000 / d) : 0);
                seek.setSecondaryProgress(d > 0 ? (int) (c.getBufferedPosition() * 1000 / d) : 0);
            }
            updateSleep();
            h.postDelayed(this, 500);
        }
    };

    private MediaInfo current() { return c == null ? null : Media.info(c.getCurrentMediaItem()); }

    private void refresh() {
        if (c == null) return;
        MediaInfo m = current();
        if (m == null) {
            title.setText("Nothing playing");
            artist.setText("Pick a song from the library");
            return;
        }
        title.setText(m.title.isEmpty() ? m.name : m.title);
        artist.setText(m.isVideo() ? "Video" : m.artistOrUnknown());
        if (!m.uri.equals(shownUri)) {
            shownUri = m.uri;
            Thumbs.load(art, m, Ui.dp(this, 560), m.isVideo() ? "video" : "music");
        }
        play.setImageDrawable(new Icon(this, c.isPlaying() ? "pause" : "play", Ui.ACCENT));
        ((Icon) shuffle.getDrawable()).setColor(c.getShuffleModeEnabled() ? Ui.ACCENT : Ui.MUTED);
        int rm = c.getRepeatMode();
        repeat.setImageDrawable(new Icon(this, rm == Player.REPEAT_MODE_ONE ? "repeat_one" : "repeat", rm == Player.REPEAT_MODE_OFF ? Ui.MUTED : Ui.ACCENT));
        watchVideo.setVisibility(m.isVideo() ? View.VISIBLE : View.GONE);
        float sp = c.getPlaybackParameters().speed;
        speedLabel.setText(fmtSpeed(sp));
        speedLabel.setTextColor(sp == 1f ? Ui.MUTED : Ui.ACCENT);
        int ni = c.getNextMediaItemIndex();
        if (ni >= 0 && ni < c.getMediaItemCount()) {
            MediaInfo n = Media.info(c.getMediaItemAt(ni));
            upNext.setText("UP NEXT   " + n.title + "  ·  " + (n.isVideo() ? "Video" : n.artistOrUnknown()));
        } else upNext.setText("UP NEXT   End of queue");
        updateLove();
    }

    private void updateLove() {
        MediaInfo m = current();
        boolean on = m != null && Store.isFav(this, m);
        loveIcon.setImageDrawable(new Icon(this, on ? "heart_filled" : "heart", Ui.ACCENT));
    }

    private void updateSleep() {
        PlaybackService s = PlaybackService.instance;
        if (s != null && s.timerEndOfSong()) { sleepChip.setText("⏱  Stops after this song"); sleepChip.setVisibility(View.VISIBLE); }
        else if (s != null && s.timerLeftMs() > 0) { sleepChip.setText("⏱  Stops in " + Ui.time(s.timerLeftMs())); sleepChip.setVisibility(View.VISIBLE); }
        else sleepChip.setVisibility(View.GONE);
    }

    static String fmtSpeed(float s) {
        String t = String.format(Locale.US, "%.2f", s);
        while (t.endsWith("0") && t.contains(".") && !t.endsWith(".0")) t = t.substring(0, t.length() - 1);
        return t + "×";
    }

    private void watch() {
        if (c == null) return;
        List<MediaInfo> l = new ArrayList<>();
        for (int i = 0; i < c.getMediaItemCount(); i++) l.add(Media.info(c.getMediaItemAt(i)));
        int idx = c.getCurrentMediaItemIndex();
        long p = c.getCurrentPosition();
        c.pause();
        Media.handoff = l;
        startActivity(new Intent(this, PlayerActivity.class).putExtra("index", idx).putExtra("position", p));
    }

    private void menu() {
        MediaInfo m = current();
        if (m == null) return;
        List<String> o = new ArrayList<>();
        o.add("Add to playlist");
        o.add("Share");
        if (m.isOnline()) o.add("Download");
        o.add("Details");
        new AlertDialog.Builder(this).setItems(o.toArray(new String[0]), (d, w) -> {
            switch (o.get(w)) {
                case "Add to playlist": Actions.addToPlaylist(this, Collections.singletonList(m)); break;
                case "Share": Media.share(this, Collections.singletonList(m)); break;
                case "Download": Downloads.get(this, m.uri, m.title); break;
                default: Actions.details(this, m, null);
            }
        }).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Actions.onResult(this, req, res);
    }

    // ---------------- sleep timer + speed sheet ----------------
    private void timerSheet() { speedSheet(this, true); }

    /** Shared with the video player (which shows only the speed part). */
    static void speedSheet(Activity a, boolean withTimer) {
        LinearLayout box = Ui.col(a);
        Dialog[] d = new Dialog[1];
        MediaController mc = Media.now();
        if (withTimer) {
            box.addView(Ui.label(a, "Sleep timer", 10, Ui.MUTED, 0.18f));
            String[] names = {"Off", "15 min", "30 min", "45 min", "1 hour", "End of song"};
            int[] mins = {0, 15, 30, 45, 60, -1};
            LinearLayout r1 = Ui.row(a), r2 = Ui.row(a);
            for (int i = 0; i < names.length; i++) {
                final int m = mins[i];
                PlaybackService s = PlaybackService.instance;
                boolean on = s != null && (m == -1 ? s.timerEndOfSong() : m == 0 ? s.timerLeftMs() == 0 && !s.timerEndOfSong() : false);
                TextView t = Ui.chip(a, names[i], on);
                t.setOnClickListener(v -> {
                    PlaybackService sv = PlaybackService.instance;
                    if (sv != null) sv.setTimer(m);
                    Ui.msg(a, m == 0 ? "Sleep timer off" : m == -1 ? "Stops after this song" : "Stops in " + names[mins.length == 0 ? 0 : indexOf(mins, m)]);
                    d[0].dismiss();
                });
                (i < 3 ? r1 : r2).addView(t, Ui.lpm(a, 0, Ui.dp(a, 40), 0, 8, i % 3 == 2 ? 0 : 8, 0));
                ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1;
            }
            box.addView(r1);
            box.addView(r2);
        }
        TextView sl = Ui.label(a, "Speed", 10, Ui.MUTED, 0.18f);
        sl.setPadding(0, withTimer ? Ui.dp(a, 22) : 0, 0, 0);
        box.addView(sl);
        float cur = a instanceof PlayerActivity ? ((PlayerActivity) a).speed() : mc != null ? mc.getPlaybackParameters().speed : 1f;
        TextView val = Ui.text(a, fmtSpeed(cur), 18, Ui.ACCENT);
        val.setTypeface(null, Typeface.BOLD);
        box.addView(val);
        SeekBar s = new SeekBar(a);
        Ui.styleSeek(s);
        s.setMax(35);
        s.setProgress(Math.round((cur - 0.25f) / 0.05f));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                float sp = 0.25f + p * 0.05f;
                val.setText(fmtSpeed(sp));
                if (u) setSpeed(a, sp);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(s, Ui.lpm(a, Ui.MATCH, Ui.WRAP, -16, 8, -16, 0));
        LinearLayout chips = Ui.row(a);
        float[] presets = {0.75f, 1f, 1.25f, 1.5f, 2f};
        for (int i = 0; i < presets.length; i++) {
            float p = presets[i];
            TextView t = Ui.chip(a, fmtSpeed(p), Math.abs(p - cur) < 0.01f);
            t.setOnClickListener(v -> { setSpeed(a, p); s.setProgress(Math.round((p - 0.25f) / 0.05f)); d[0].dismiss(); });
            chips.addView(t, Ui.lpm(a, 0, Ui.dp(a, 40), 0, 10, i == presets.length - 1 ? 0 : 6, 0));
            ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1;
        }
        box.addView(chips);
        if (withTimer) {
            Switch sw = Ui.toggle(a, Store.bool(a, "skip_silence", false));
            sw.setOnCheckedChangeListener((x, on) -> {
                Store.put(a, "skip_silence", on);
                if (PlaybackService.instance != null) PlaybackService.instance.setSkipSilence(on);
            });
            LinearLayout r = Ui.infoRow(a, "Skip silence", "Jumps over quiet gaps in songs", sw);
            ((LinearLayout.LayoutParams) r.getLayoutParams()).topMargin = Ui.dp(a, 20);
            box.addView(r);
        }
        d[0] = Ui.sheet(a, box);
        d[0].show();
    }

    static int indexOf(int[] a, int v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return i; return 0; }

    static void setSpeed(Activity a, float sp) {
        if (a instanceof PlayerActivity) { ((PlayerActivity) a).setSpeed(sp); return; }
        MediaController mc = Media.now();
        if (mc != null) mc.setPlaybackParameters(new PlaybackParameters(sp));
    }

    // ---------------- queue sheet ----------------
    private void queueSheet() {
        if (c == null) return;
        LinearLayout box = Ui.col(this);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.label(this, "Queue", 13, Ui.TEXT, 0.2f), Ui.lpw(0, Ui.WRAP, 1));
        TextView save = Ui.chip(this, "Save", false), clear = Ui.chip(this, "Clear", false);
        head.addView(save, Ui.lpm(this, Ui.WRAP, Ui.dp(this, 36), 0, 0, 8, 0));
        head.addView(clear, Ui.lp(Ui.WRAP, Ui.dp(this, 36)));
        box.addView(head);
        TextView hint = Ui.text(this, "Drag ≡ to reorder · swipe or ✕ to remove · tap to play", 12, Ui.MUTED);
        hint.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        box.addView(hint);
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        QueueAdapter qa = new QueueAdapter();
        rv.setAdapter(qa);
        int hgt = (int) (getResources().getDisplayMetrics().heightPixels * 0.55);
        box.addView(rv, Ui.lp(Ui.MATCH, hgt));
        ItemTouchHelper th = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder b) {
                int from = a.getAdapterPosition(), to = b.getAdapterPosition();
                c.moveMediaItem(from, to);
                qa.notifyItemMoved(from, to);
                return true;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                int p = vh.getAdapterPosition();
                c.removeMediaItem(p);
                qa.notifyItemRemoved(p);
            }
        });
        th.attachToRecyclerView(rv);
        qa.helper = th;
        Dialog d = Ui.sheet(this, box);
        save.setOnClickListener(v -> Actions.newPlaylist(this, n -> {
            List<MediaInfo> l = new ArrayList<>();
            for (int i = 0; i < c.getMediaItemCount(); i++) l.add(Media.info(c.getMediaItemAt(i)));
            Store.addTo(this, n, l);
            Ui.msg(this, "Saved as " + n);
        }));
        clear.setOnClickListener(v -> {
            int cur = c.getCurrentMediaItemIndex();
            if (cur + 1 < c.getMediaItemCount()) c.removeMediaItems(cur + 1, c.getMediaItemCount());
            if (cur > 0) c.removeMediaItems(0, cur);
            qa.notifyDataSetChanged();
        });
        d.show();
        rv.scrollToPosition(Math.max(0, c.getCurrentMediaItemIndex()));
    }

    final class QueueAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        ItemTouchHelper helper;

        @Override public int getItemCount() { return c == null ? 0 : c.getMediaItemCount(); }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout r = Ui.row(NowPlayingActivity.this);
            r.setPadding(0, Ui.dp(NowPlayingActivity.this, 4), 0, Ui.dp(NowPlayingActivity.this, 4));
            r.setLayoutParams(new RecyclerView.LayoutParams(Ui.MATCH, Ui.dp(NowPlayingActivity.this, 58)));
            return new RecyclerView.ViewHolder(r) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
            LinearLayout r = (LinearLayout) vh.itemView;
            r.removeAllViews();
            MediaInfo m = Media.info(c.getMediaItemAt(pos));
            boolean cur = pos == c.getCurrentMediaItemIndex();
            ImageView drag = new ImageView(NowPlayingActivity.this);
            drag.setImageDrawable(new Icon(NowPlayingActivity.this, "menu", Ui.MUTED));
            drag.setPadding(Ui.dp(NowPlayingActivity.this, 10), Ui.dp(NowPlayingActivity.this, 10), Ui.dp(NowPlayingActivity.this, 10), Ui.dp(NowPlayingActivity.this, 10));
            drag.setOnTouchListener((v, e) -> { if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && helper != null) helper.startDrag(vh); return false; });
            r.addView(drag, Ui.lp(Ui.dp(NowPlayingActivity.this, 44), Ui.dp(NowPlayingActivity.this, 44)));
            LinearLayout t = Ui.col(NowPlayingActivity.this);
            TextView tt = Ui.single(Ui.text(NowPlayingActivity.this, m.title, 14, cur ? Ui.ACCENT : Ui.TEXT));
            TextView st = Ui.single(Ui.text(NowPlayingActivity.this, cur ? "Playing now" : m.isVideo() ? "Video" : m.artistOrUnknown(), 12, Ui.MUTED));
            t.addView(tt);
            t.addView(st);
            r.addView(t, Ui.lpw(0, Ui.WRAP, 1));
            ImageButton x = Ui.iconBtn(NowPlayingActivity.this, "close", 44, Ui.MUTED);
            x.setOnClickListener(v -> { int p = vh.getAdapterPosition(); if (p >= 0) { c.removeMediaItem(p); notifyItemRemoved(p); } });
            r.addView(x);
            r.setOnClickListener(v -> { int p = vh.getAdapterPosition(); if (p >= 0) { c.seekTo(p, 0); c.play(); notifyDataSetChanged(); } });
        }
    }
}
