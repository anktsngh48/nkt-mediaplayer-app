package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen photos and videos together, in the order of the grid: swipe or tap ‹ › for the next one,
 * pinch or double-tap photos to zoom; videos play right here (⛶ opens the full video player).
 */
public class ViewerActivity extends Activity {

    // The list is handed over in memory (it can be thousands of photos)
    private static List<MediaInfo> pending = new ArrayList<>();
    private static int pendingIndex = 0;

    public static void open(Context c, List<MediaInfo> photos, int index) {
        prepare(photos, index);
        c.startActivity(new Intent(c, ViewerActivity.class));
    }

    /** Sets the list for the next start (used when the start intent is built elsewhere). */
    public static void prepare(List<MediaInfo> photos, int index) {
        pending = new ArrayList<>(photos);
        pendingIndex = index;
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private List<MediaInfo> items;
    private RecyclerView pager, film;
    private LinearLayoutManager pagerLm, filmLm;
    private View topBar, bottomBar;
    private TextView title, sub;
    private int current = 0;
    private android.widget.ImageButton favBtn;
    private String recorded;
    private boolean barsVisible = true;
    private FilmAdapter filmAdapter;
    private FileOps.Deleter deleter;
    private View prevBtn, nextBtn, fullBtn;

    // one player for whichever video page is showing
    private androidx.media3.exoplayer.ExoPlayer player;
    private androidx.media3.ui.PlayerView playerView;
    private android.widget.ImageButton playBtn;
    private String playingKey;
    private View seekRow;
    private android.widget.SeekBar seek;
    private TextView posText, durText;
    private boolean seeking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        Ui.fullScreen(this);
        items = new ArrayList<>(pending);
        Intent in = getIntent();
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            // "Open with NKT Media Player" on a photo
            MediaInfo m = MediaInfo.link(in.getData(), Ui.displayName(this, in.getData()), false);
            m.image = true;
            items = new ArrayList<>();
            items.add(m);
            pendingIndex = 0;
        }
        current = Math.max(0, Math.min(pendingIndex, items.size() - 1));
        if (items.isEmpty()) { finish(); return; }

        topBar = findViewById(R.id.vw_top);
        bottomBar = findViewById(R.id.vw_bottom);
        title = findViewById(R.id.vw_title);
        sub = findViewById(R.id.vw_sub);
        findViewById(R.id.vw_back).setOnClickListener(v -> finish());
        favBtn = findViewById(R.id.vw_fav);
        favBtn.setOnClickListener(v -> {
            MediaInfo m = items.get(current);
            Ui.toast(this, Prefs.toggleFavourite(this, m) ? R.string.fav_photo_added : R.string.fav_photo_removed);
            updateFav();
        });
        findViewById(R.id.vw_share).setOnClickListener(v -> Share.send(this, items.get(current)));
        deleter = new FileOps.Deleter(this);
        findViewById(R.id.vw_info).setOnClickListener(v -> FileOps.properties(this, items.get(current), deleter, gone -> removeItem(current), null));
        findViewById(R.id.vw_delete).setOnClickListener(v -> {
            List<MediaInfo> one = new ArrayList<>();
            one.add(items.get(current));
            final int at = current;
            deleter.delete(one, gone -> removeItem(at));          // gone at once; UNDO in the message
        });
        findViewById(R.id.vw_more).setOnClickListener(v -> viewerMenu());
        prevBtn = findViewById(R.id.vw_prev);
        nextBtn = findViewById(R.id.vw_next);
        fullBtn = findViewById(R.id.vw_full);
        prevBtn.setOnClickListener(v -> go(current - 1));
        nextBtn.setOnClickListener(v -> go(current + 1));
        fullBtn.setOnClickListener(v -> openFullPlayer());
        seekRow = findViewById(R.id.vw_seek_row);
        seek = findViewById(R.id.vw_seek);
        posText = findViewById(R.id.vw_pos);
        durText = findViewById(R.id.vw_dur);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar b, int p, boolean fromUser) {
                if (!fromUser || player == null || player.getDuration() <= 0) return;
                long to = player.getDuration() * p / 1000;
                posText.setText(Ui.time(to));
                player.seekTo(to);                       // the picture follows the finger
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar b) {
                seeking = true;
                if (player != null) player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
            }
            @Override public void onStopTrackingTouch(android.widget.SeekBar b) {
                seeking = false;
                if (player == null || player.getDuration() <= 0) return;
                player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT);
                player.seekTo(player.getDuration() * b.getProgress() / 1000);
            }
        });
        Ui.padTopBar(findViewById(R.id.vw_top));
        Ui.padBottomBar(findViewById(R.id.vw_bottom));
        boolean anyVideo = false;
        for (MediaInfo m : items) if (m.video) { anyVideo = true; break; }
        ((TextView) findViewById(R.id.vw_hint)).setText(anyVideo ? R.string.viewer_hint_mixed : R.string.viewer_hint);

        pager = findViewById(R.id.vw_pager);
        pagerLm = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        pager.setLayoutManager(pagerLm);
        pager.setAdapter(new PageAdapter());
        new PagerSnapHelper().attachToRecyclerView(pager);
        pager.scrollToPosition(current);
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                int p = pagerLm.findFirstCompletelyVisibleItemPosition();
                if (p >= 0 && p != current) { current = p; updateBars(); }
                attachVideo();
            }

            /** Start the video as soon as its page is more than half in view, not after the swipe settles. */
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dx == 0) return;
                int mid = rv.getWidth() / 2;
                View c = rv.findChildViewUnder(mid, rv.getHeight() / 2f);
                if (c == null) return;
                int p = rv.getChildAdapterPosition(c);
                if (p >= 0 && p < items.size() && items.get(p).video && !items.get(p).key().equals(playingKey)) attachVideo(p);
            }
        });

        film = findViewById(R.id.vw_film);
        filmLm = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        film.setLayoutManager(filmLm);
        filmAdapter = new FilmAdapter();
        film.setAdapter(filmAdapter);
        updateBars();
        pager.post(this::attachVideo);
    }

    private void viewerMenu() {
        MediaInfo m = items.get(current);
        List<String> opts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        opts.add(getString(R.string.rename)); ids.add("rename");
        opts.add(getString(R.string.copy_files)); ids.add("copy");
        opts.add(getString(R.string.cut)); ids.add("cut");
        if (m.image) { opts.add(getString(R.string.set_wallpaper)); ids.add("wall"); }
        opts.add(getString(R.string.properties)); ids.add("props");
        Dialogs.builder(this).setTitle(m.title).setItems(opts.toArray(new String[0]), (d, w) -> {
            List<MediaInfo> one = new ArrayList<>();
            one.add(m);
            switch (ids.get(w)) {
                case "rename": FileOps.rename(this, m, null); break;
                case "copy": FileJobs.put(this, one, false, null); break;
                case "cut": FileJobs.put(this, one, true, null); break;
                case "wall": Wallpapers.set(this, m); break;
                default: FileOps.properties(this, m, deleter, gone -> removeItem(current), null);
            }
        }).show();
    }

    /** ‹ › : smooth step to the previous or next photo or video. */
    private void go(int to) {
        if (to < 0 || to >= items.size()) return;
        current = to;
        pager.smoothScrollToPosition(to);
        updateBars();
    }

    private void removeItem(int i) {
        if (i < 0 || i >= items.size()) return;
        stopVideo();
        items.remove(i);
        if (items.isEmpty()) { finish(); return; }
        pager.getAdapter().notifyItemRemoved(i);
        current = Math.min(i, items.size() - 1);
        filmAdapter.notifyDataSetChanged();
        updateBars();
        pager.post(this::attachVideo);
    }

    // ── Videos inside the viewer ─────────────────────────────────────────────

    private void ensurePlayer() {
        if (player != null) return;
        player = Players.build(this, false);
        player.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_OFF);
        playerView = (androidx.media3.ui.PlayerView) getLayoutInflater().inflate(R.layout.viewer_player, null, false);
        playerView.setUseController(false);
        playerView.setShutterBackgroundColor(0);
        playerView.setKeepContentOnPlayerReset(false);
        playerView.setPlayer(player);
        playBtn = new android.widget.ImageButton(this);
        playBtn.setBackgroundResource(R.drawable.bg_play_btn);
        playBtn.setImageResource(R.drawable.ic_play);
        playBtn.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        playBtn.setContentDescription(getString(R.string.cd_play));
        playBtn.setOnClickListener(v -> {
            if (player.isPlaying()) player.pause();
            else {
                if (player.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED) player.seekTo(0);
                player.play();
            }
        });
        player.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onIsPlayingChanged(boolean playing) {
                playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                playBtn.animate().alpha(playing ? 0f : 1f).setDuration(200).start();
            }
            @Override public void onRenderedFirstFrame() {
                // the video is drawing: now (and only now) take the still picture away, so there is no jump
                if (posterShowing != null) { posterShowing.setVisibility(View.GONE); posterShowing = null; }
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    playBtn.setImageResource(R.drawable.ic_repeat);
                    playBtn.animate().alpha(1f).setDuration(150).start();
                }
            }
        });
    }

    /** Puts the player into the video page that is showing (or stops it on a photo). */
    private void attachVideo() {
        if (items.isEmpty()) return;
        if (!items.get(current).video) { stopVideo(); return; }
        attachVideo(current);
    }

    private ImageView posterShowing, holderPoster;

    private void attachVideo(int pos) {
        MediaInfo m = items.get(pos);
        RecyclerView.ViewHolder vh = pager.findViewHolderForAdapterPosition(pos);
        if (!(vh instanceof VideoVH)) return;
        VideoVH h = (VideoVH) vh;
        ensurePlayer();
        if (m.key().equals(playingKey) && playerView.getParent() == h.box) return;
        detachPlayerView();
        // the video sits under the still picture; the picture goes when the first frame is drawn
        h.box.addView(playerView, 0, new android.widget.FrameLayout.LayoutParams(-1, -1));
        int size = dp(72);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER);
        h.box.addView(playBtn, lp);
        h.poster.setVisibility(View.VISIBLE);
        posterShowing = h.poster;
        holderPoster = h.poster;
        if (!m.key().equals(playingKey)) {
            playingKey = m.key();
            player.setMediaItem(new androidx.media3.common.MediaItem.Builder().setUri(m.uri).setMediaId(m.key()).build());
            player.prepare();
        }
        player.play();
        if (!barsVisible) toggleBars();                 // arriving at a video: show its time and seek bar
        ui.removeCallbacks(progressTick);
        ui.post(progressTick);
    }

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (player == null || playingKey == null || isFinishing()) return;
            long d = player.getDuration(), p = player.getCurrentPosition();
            if (d > 0 && !seeking) {
                seek.setProgress((int) (1000 * p / d));
                seek.setSecondaryProgress((int) (1000 * player.getBufferedPosition() / d));
                posText.setText(Ui.time(p));
                durText.setText(Ui.time(d));
            }
            ui.postDelayed(this, 250);
        }
    };

    private void detachPlayerView() {
        if (holderPoster != null) { holderPoster.setVisibility(View.VISIBLE); holderPoster = null; }
        if (playerView != null && playerView.getParent() instanceof ViewGroup) ((ViewGroup) playerView.getParent()).removeView(playerView);
        if (playBtn != null && playBtn.getParent() instanceof ViewGroup) ((ViewGroup) playBtn.getParent()).removeView(playBtn);
    }

    private void stopVideo() {
        if (player == null) return;
        player.stop();
        player.clearMediaItems();
        playingKey = null;
        detachPlayerView();
    }

    private void openFullPlayer() {
        MediaInfo m = items.get(current);
        if (!m.video) return;
        long pos = player != null && m.key().equals(playingKey) ? player.getCurrentPosition() : 0;
        if (player != null) player.pause();
        List<MediaInfo> vids = new ArrayList<>();
        for (MediaInfo x : items) if (x.video) vids.add(x);
        PlayerActivity.open(this, vids, Math.max(0, vids.indexOf(m)), pos);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        deleter.onActivityResult(requestCode, resultCode);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        deleter.onRequestPermissionsResult(requestCode, results);
    }

    private void updateFav() {
        boolean fav = Prefs.isFavourite(this, items.get(current));
        favBtn.setImageResource(fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        favBtn.setColorFilter(getColor(fav ? R.color.accent : R.color.text));
    }

    private void updateBars() {
        MediaInfo m = items.get(current);
        if (favBtn != null) updateFav();
        prevBtn.setVisibility(current > 0 ? View.VISIBLE : View.INVISIBLE);
        nextBtn.setVisibility(current < items.size() - 1 ? View.VISIBLE : View.INVISIBLE);
        fullBtn.setVisibility(m.video ? View.VISIBLE : View.GONE);
        seekRow.setVisibility(m.video ? View.VISIBLE : View.GONE);
        if (m.video) { seek.setProgress(0); posText.setText("0:00"); durText.setText(m.durationMs > 0 ? Ui.time(m.durationMs) : "0:00"); }
        if (!m.key().equals(recorded)) { recorded = m.key(); History.record(this, m); }
        title.setText(m.dateMs > 0
                ? DateFormat.getMediumDateFormat(this).format(new Date(m.dateMs)) + ", " + DateFormat.getTimeFormat(this).format(new Date(m.dateMs))
                : m.fileName);
        sub.setText(getString(R.string.n_of_total, m.folder, current + 1, items.size()));
        filmAdapter.notifyDataSetChanged();
        filmLm.scrollToPositionWithOffset(current, film.getWidth() / 2 - dp(24));
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        topBar.animate().alpha(barsVisible ? 1 : 0).setDuration(150).start();
        bottomBar.animate().alpha(barsVisible ? 1 : 0).setDuration(150).start();
        prevBtn.animate().alpha(barsVisible ? 1 : 0).setDuration(150).start();
        nextBtn.animate().alpha(barsVisible ? 1 : 0).setDuration(150).start();
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        prevBtn.setEnabled(barsVisible);
        nextBtn.setEnabled(barsVisible);
        if (!barsVisible) ui.postDelayed(() -> { if (!barsVisible) { topBar.setVisibility(View.INVISIBLE); bottomBar.setVisibility(View.INVISIBLE); } }, 160);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.fullScreen(this);
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }

    /** Decodes a photo at about screen size, the right way up. */
    private Bitmap decode(MediaInfo m) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int target = Math.max(dm.widthPixels, dm.heightPixels);
        try {
            if (Build.VERSION.SDK_INT >= 28 && !"file".equals(m.uri.getScheme())) {
                ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), m.uri);
                return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    decoder.setTargetSampleSize(Thumbs.sample(w, h, target));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(m.uri)) { BitmapFactory.decodeStream(in, null, o); }
            o.inSampleSize = Thumbs.sample(o.outWidth, o.outHeight, target);
            o.inJustDecodeBounds = false;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(m.uri)) { b = BitmapFactory.decodeStream(in, null, o); }
            return Thumbs.rotate(b, m.orientation);
        } catch (Throwable t) {
            return null;
        }
    }

    private class PageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return items.size(); }

        @Override public int getItemViewType(int p) { return items.get(p).video ? 1 : 0; }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == 1) {
                android.widget.FrameLayout box = new android.widget.FrameLayout(parent.getContext());
                box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                ImageView poster = new ImageView(parent.getContext());
                poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
                box.addView(poster, new android.widget.FrameLayout.LayoutParams(-1, -1));
                return new VideoVH(box, poster);
            }
            ZoomImageView z = new ZoomImageView(parent.getContext());
            z.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new PageVH(z);
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            MediaInfo m = items.get(position);
            if (holder instanceof VideoVH) {
                VideoVH h = (VideoVH) holder;
                h.poster.setVisibility(View.VISIBLE);
                showFirstFrame(h.poster, m);
                h.box.setOnClickListener(v -> toggleBars());
                if (position == current) h.box.post(ViewerActivity.this::attachVideo);
                return;
            }
            PageVH h = (PageVH) holder;
            String key = m.uri.toString();
            h.view.setTag(R.id.thumb_key, key);
            h.view.setImageDrawable(null);
            h.view.setOnTap(ViewerActivity.this::toggleBars);
            pool.execute(() -> {
                Bitmap b = decode(m);
                ui.post(() -> { if (b != null && key.equals(h.view.getTag(R.id.thumb_key))) h.view.setImageBitmap(b); });
            });
        }

        @Override public void onViewRecycled(RecyclerView.ViewHolder h) {
            if (h instanceof PageVH) ((PageVH) h).view.setImageDrawable(null);
            if (h instanceof VideoVH && playerView != null && playerView.getParent() == ((VideoVH) h).box) {
                if (player != null) player.pause();
                detachPlayerView();
                playingKey = null;
            }
        }
    }

    // ── Still picture for a video page: its very first frame, at screen size, so playback starts with no jump ──

    private final android.util.LruCache<String, Bitmap> firstFrames = new android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private void showFirstFrame(ImageView v, MediaInfo m) {
        String key = m.key();
        v.setTag(R.id.frame_key, key);
        Bitmap b = firstFrames.get(key);
        if (b != null) { v.setTag(R.id.thumb_key, null); v.setScaleType(ImageView.ScaleType.FIT_CENTER); v.setImageBitmap(b); return; }
        Thumbs.load(v, m, 720);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final int w = getResources().getDisplayMetrics().widthPixels;
        pool.execute(() -> {
            Bitmap f = null;
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(this, m.uri);
                Bitmap full = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (full != null && full.getWidth() > w) {
                    f = Bitmap.createScaledBitmap(full, w, Math.max(1, full.getHeight() * w / full.getWidth()), true);
                    if (f != full) full.recycle();
                } else f = full;
            } catch (Throwable ignored) {
            } finally { try { r.release(); } catch (Exception ignored) { } }
            if (f == null) return;
            final Bitmap got = f;
            firstFrames.put(key, got);
            ui.post(() -> { if (key.equals(v.getTag(R.id.frame_key))) { v.setTag(R.id.thumb_key, null); v.setScaleType(ImageView.ScaleType.FIT_CENTER); v.setImageBitmap(got); } });
        });
    }

    static class PageVH extends RecyclerView.ViewHolder {
        final ZoomImageView view;
        PageVH(ZoomImageView v) { super(v); view = v; }
    }

    static class VideoVH extends RecyclerView.ViewHolder {
        final android.widget.FrameLayout box; final ImageView poster;
        VideoVH(android.widget.FrameLayout b, ImageView p) { super(b); box = b; poster = p; }
    }

    private class FilmAdapter extends RecyclerView.Adapter<FilmVH> {
        @Override public int getItemCount() { return items.size(); }

        @Override public FilmVH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new FilmVH(LayoutInflater.from(parent.getContext()).inflate(R.layout.film_item, parent, false));
        }

        @Override public void onBindViewHolder(FilmVH h, int position) {
            Thumbs.load(h.img, items.get(position), 120);
            h.play.setVisibility(items.get(position).video ? View.VISIBLE : View.GONE);
            boolean on = position == current;
            h.frame.setSelected(on);
            h.img.setAlpha(on ? 1f : 0.6f);
            h.itemView.setOnClickListener(v -> {
                current = h.getBindingAdapterPosition();
                pager.scrollToPosition(current);
                updateBars();
                pager.post(ViewerActivity.this::attachVideo);
            });
        }
    }

    static class FilmVH extends RecyclerView.ViewHolder {
        final ImageView img, play; final View frame;
        FilmVH(View v) {
            super(v);
            img = v.findViewById(R.id.fi_img);
            play = v.findViewById(R.id.fi_play);
            frame = v.findViewById(R.id.fi_frame);
            img.setClipToOutline(true);
        }
    }

    static String sizeText(Context c, long bytes) { return Formatter.formatShortFileSize(c, bytes); }
}
