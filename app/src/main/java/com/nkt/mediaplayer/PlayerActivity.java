package com.nkt.mediaplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.session.MediaController;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Full-screen video. Controls stay hidden until you tap; they all sit at the bottom.
 * Gestures: swipe up/down left = brightness, right = volume; slide sideways = seek, the picture itself follows the finger;
 * double-tap left/right = −/+10 s; pinch = fill/fit; press and hold = 2× while held.
 */
public class PlayerActivity extends Activity {

    private static final String EXTRA_URIS = "uris", EXTRA_TITLES = "titles", EXTRA_INDEX = "index", EXTRA_POS = "pos";

    /** Full details for the list being opened (favourites, history, details); the intent keeps a plain copy. */
    private static List<MediaInfo> pendingItems;

    public static void open(Context c, List<MediaInfo> list, int index) { open(c, list, index, 0); }

    public static void open(Context c, List<MediaInfo> list, int index, long startMs) {
        c.startActivity(intent(c, list, index, startMs));
    }

    public static Intent intent(Context c, List<MediaInfo> list, int index, long startMs) {
        String[] uris = new String[list.size()], titles = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            uris[i] = list.get(i).uri.toString();
            titles[i] = list.get(i).title;
        }
        pendingItems = new ArrayList<>(list);
        return new Intent(c, PlayerActivity.class)
                .putExtra(EXTRA_URIS, uris)
                .putExtra(EXTRA_TITLES, titles)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_POS, startMs);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView titleView, indicator, speedBtn, holdBadge, posText, durText;
    private View controls, replayBtn;
    private SeekBar seekBar;
    private ImageButton playBtn;
    private boolean draggingBar = false;
    private ImageButton rotateBtn, favBtn;
    private AudioManager audio;
    private boolean zoomed = false, scaling = false, holding = false, inPip = false;
    private int scrollMode = 0;          // 0 = undecided, 1 = brightness, 2 = volume, 3 = seek
    private float volumeAcc = 0, scaleAcc = 1, speedBeforeHold = 1f;
    private long seekStartPos = 0, seekTarget = -1;
    private final List<MediaInfo> items = new ArrayList<>();

    // live scrubbing: the video jumps to key frames while the finger moves, then to the exact moment on release
    private boolean scrubbing = false, playingBeforeScrub = false;
    private long scrubTarget = -1, scrubShown = -1, scrubFrom = 0;
    private FileOps.Deleter deleter;

    private final Runnable hideIndicator = () -> indicator.setVisibility(View.GONE);

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.fullScreen(this);
        applyOrientation(Prefs.videoOrientation(this));

        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        playerView = findViewById(R.id.player_view);
        indicator = findViewById(R.id.indicator);
        indicator.setTypeface(Ui.orbitron(this));
        holdBadge = findViewById(R.id.hold_badge);
        holdBadge.setTypeface(Ui.orbitron(this));
        deleter = new FileOps.Deleter(this);
        replayBtn = findViewById(R.id.btn_replay);
        replayBtn.setOnClickListener(v -> replay());
        Ui.padTopBar(findViewById(R.id.top_bar));
        Ui.padBottomBar(findViewById(R.id.bottom_bar));

        // our own control layer (hidden until you tap)
        controls = findViewById(R.id.controls);
        titleView = findViewById(R.id.video_title);
        speedBtn = findViewById(R.id.btn_speed);
        rotateBtn = findViewById(R.id.btn_rotate);
        favBtn = findViewById(R.id.btn_fav);
        posText = findViewById(R.id.ctl_position);
        durText = findViewById(R.id.ctl_duration);
        seekBar = findViewById(R.id.ctl_seek);
        playBtn = findViewById(R.id.ctl_play);
        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        rotateBtn.setOnClickListener(v -> { keepControls(); chooseOrientation(); });
        speedBtn.setOnClickListener(v -> { keepControls(); openSpeed(); });
        favBtn.setOnClickListener(v -> { keepControls(); toggleFavourite(); });
        findViewById(R.id.btn_more).setOnClickListener(v -> { keepControls(); moreMenu(); });
        playBtn.setOnClickListener(v -> {
            keepControls();
            if (player.isPlaying()) player.pause();
            else if (atEnd()) replay();
            else player.play();
        });
        findViewById(R.id.ctl_prev).setOnClickListener(v -> { keepControls(); player.seekToPrevious(); });
        findViewById(R.id.ctl_next).setOnClickListener(v -> { keepControls(); if (player.getNextMediaItemIndex() != C.INDEX_UNSET) player.seekToNextMediaItem(); });
        findViewById(R.id.vid_prev).setOnClickListener(v -> { keepControls(); if (player.hasPreviousMediaItem()) { player.seekToPreviousMediaItem(); player.play(); } });
        findViewById(R.id.vid_next).setOnClickListener(v -> { keepControls(); if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.play(); } });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser || player.getDuration() <= 0) return;
                long pos = player.getDuration() * p / 1000;
                posText.setText(Ui.time(pos));
                scrubTo(pos);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                draggingBar = true;
                ui.removeCallbacks(hideControls);
                startScrub();
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                draggingBar = false;
                endScrub(player.getDuration() > 0 ? player.getDuration() * sb.getProgress() / 1000 : -1);
                keepControls();
            }
        });
        View pip = findViewById(R.id.btn_pip);
        if (Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature("android.software.picture_in_picture"))
            pip.setOnClickListener(v -> enterPip());
        else pip.setVisibility(View.GONE);
        updateRotateIcon();

        // karaoke works on videos too; focus pauses the music player while a video plays
        KaraokeProcessor.enabled = Prefs.karaoke(this);
        KaraokeProcessor.strength = Prefs.karaokeStrength(this);
        player = Players.build(this, false);
        applyVideoEnd();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(MediaItem item, int reason) { onNewItem(); }
            @Override public void onPlayerError(PlaybackException error) { flash(getString(R.string.cant_play)); }
            @Override public void onEvents(Player p, Player.Events e) { updateControls(); }
            @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return;
                videoEnded();
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) videoEnded();
            }
        });
        ui.post(tick);

        load(getIntent());
        setupGestures();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        load(intent);
    }

    private void load(Intent i) {
        List<MediaItem> list = new ArrayList<>();
        items.clear();
        int index = 0;
        long pos = 0;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            // "Open with NKT Media Player" on a video file
            Uri u = i.getData();
            items.add(MediaInfo.link(u, Ui.displayName(this, u), true));
        } else {
            String[] uris = i.getStringArrayExtra(EXTRA_URIS);
            String[] t = i.getStringArrayExtra(EXTRA_TITLES);
            if (uris == null || uris.length == 0) { finish(); return; }
            List<MediaInfo> rich = pendingItems;
            pendingItems = null;
            boolean richOk = rich != null && rich.size() == uris.length && rich.get(0).uri.toString().equals(uris[0]);
            for (int k = 0; k < uris.length; k++) {
                if (richOk) items.add(rich.get(k));
                else {
                    String title = t != null && k < t.length ? t[k] : null;
                    items.add(MediaInfo.link(Uri.parse(uris[k]), title == null ? "" : title, true));
                }
            }
            index = Math.max(0, Math.min(i.getIntExtra(EXTRA_INDEX, 0), uris.length - 1));
            pos = i.getLongExtra(EXTRA_POS, 0);
        }
        for (MediaInfo m : items) list.add(new MediaItem.Builder().setUri(m.uri).setMediaId(m.key()).build());
        player.setMediaItems(list, index, pos);
        player.prepare();
        player.play();
        hideControls.run();
        onNewItem();
    }

    private MediaInfo current() {
        int i = player == null ? -1 : player.getCurrentMediaItemIndex();
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    private void onNewItem() {
        MediaInfo m = current();
        titleView.setText(m == null ? "" : m.title);
        updateFav();
        if (m != null) History.record(this, m);
    }

    // ── When a video ends: next · go back · stop on the last frame ───────────

    private void applyVideoEnd() {
        // pause at the end of each video unless the next one should start by itself
        if (player != null) player.setPauseAtEndOfMediaItems(Prefs.videoEnd(this) != 0);
    }

    private void videoEnded() {
        if (scrubbing) return;
        int mode = Prefs.videoEnd(this);
        if (mode == 1) { finish(); return; }
        // stopped on the last frame (or the last video ended): offer replay
        replayBtn.setVisibility(View.VISIBLE);
        keepControls();
    }

    private boolean atEnd() {
        if (player == null) return false;
        if (player.getPlaybackState() == Player.STATE_ENDED) return true;
        long d = player.getDuration();
        return !player.getPlayWhenReady() && d > 0 && player.getCurrentPosition() >= d - 300;
    }

    private void replay() {
        if (player == null) return;
        replayBtn.setVisibility(View.GONE);
        player.seekTo(player.getCurrentMediaItemIndex(), 0);
        player.play();
        keepControls();
    }

    // ── Gestures ─────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private void setupGestures() {
        float dp = getResources().getDisplayMetrics().density;
        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { scrollMode = 0; volumeAcc = 0; seekTarget = -1; return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (controls.getVisibility() == View.VISIBLE) hideControls.run(); else keepControls();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                boolean forward = e.getX() > playerView.getWidth() / 2f;
                long dur = player.getDuration();
                long pos = player.getCurrentPosition() + (forward ? 10_000 : -10_000);
                if (dur > 0) pos = Math.min(pos, dur);
                player.seekTo(Math.max(0, pos));
                flash(forward ? getString(R.string.fwd_10) : getString(R.string.back_10));
                return true;
            }

            @Override public void onLongPress(MotionEvent e) { if (scrollMode == 0) startHold(); }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null || scaling || holding) return false;
                if (scrollMode == 0) {
                    if (Math.abs(e2.getY() - e1.getY()) > Math.abs(e2.getX() - e1.getX()))
                        scrollMode = e1.getX() < playerView.getWidth() / 2f ? 1 : 2;
                    else {
                        scrollMode = 3;
                        seekStartPos = player.getCurrentPosition();
                        startScrub();
                    }
                }
                if (scrollMode == 3) {
                    // fine seeking: about 1 second for every 12 dp the finger moves
                    float moved = (e2.getX() - e1.getX()) / dp;
                    long delta = Math.round(moved / 12f) * 1000L;
                    long dur = player.getDuration();
                    long target = Math.max(0, seekStartPos + delta);
                    if (dur > 0) target = Math.min(target, dur);
                    seekTarget = target;
                    String sign = delta >= 0 ? "+" : "−";
                    indicator.setText(sign + Ui.time(Math.abs(delta)) + " · " + Ui.time(target));
                    indicator.setVisibility(View.VISIBLE);
                    ui.removeCallbacks(hideIndicator);
                    scrubTo(target);
                    return true;
                }
                float delta = dy / Math.max(1, playerView.getHeight());   // finger up = positive
                if (scrollMode == 1) changeBrightness(delta * 1.4f);
                else if (scrollMode == 2) changeVolume(delta * 1.4f);
                return true;
            }
        });

        ScaleGestureDetector pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaleAcc = 1; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) { scaleAcc *= d.getScaleFactor(); return true; }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                if (scaleAcc > 1.08f) setZoom(true);
                else if (scaleAcc < 0.92f) setZoom(false);
            }
        });

        playerView.setOnTouchListener((v, ev) -> {
            if (inPip) return false;
            int act = ev.getActionMasked();
            if (holding && (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL)) { endHold(); return true; }
            if (holding) return true;
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                if (scrollMode == 3) {
                    endScrub(act == MotionEvent.ACTION_UP ? seekTarget : -1);
                    seekTarget = -1;
                    ui.postDelayed(hideIndicator, 600);
                }
            }
            pinch.onTouchEvent(ev);
            scaling = pinch.isInProgress() || ev.getPointerCount() > 1;
            if (!scaling) taps.onTouchEvent(ev);
            return true;
        });
    }

    // ── Live scrubbing (no small preview: the video itself shows the moment) ──

    private void startScrub() {
        if (player == null || scrubbing) return;
        scrubbing = true;
        scrubFrom = player.getCurrentPosition();
        playingBeforeScrub = player.getPlayWhenReady();
        replayBtn.setVisibility(View.GONE);
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC);     // fast: nearest key frame
        player.pause();
        scrubTarget = scrubShown = -1;
    }

    /** Called for every finger move; seeks at most every 70 ms so the decoder keeps up. */
    private void scrubTo(long pos) {
        if (!scrubbing) startScrub();
        scrubTarget = pos;
        if (!draggingBar) indicator.setVisibility(View.VISIBLE);
        else {
            long d = pos - scrubFrom;
            indicator.setText((d >= 0 ? "+" : "−") + Ui.time(Math.abs(d)) + " · " + Ui.time(pos));
            indicator.setVisibility(View.VISIBLE);
            ui.removeCallbacks(hideIndicator);
        }
        ui.removeCallbacks(scrubStep);
        ui.post(scrubStep);
    }

    private long lastScrubSeek = 0;

    private final Runnable scrubStep = new Runnable() {
        @Override public void run() {
            if (!scrubbing || player == null || scrubTarget < 0 || scrubTarget == scrubShown) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastScrubSeek < 70) { ui.postDelayed(this, 70 - (now - lastScrubSeek)); return; }
            lastScrubSeek = now;
            scrubShown = scrubTarget;
            player.seekTo(scrubTarget);
        }
    };

    /** target < 0 = cancel (go back to where it was). */
    private void endScrub(long target) {
        if (!scrubbing || player == null) return;
        scrubbing = false;
        ui.removeCallbacks(scrubStep);
        player.setSeekParameters(SeekParameters.EXACT);             // the exact moment on release
        player.seekTo(target >= 0 ? target : scrubFrom);
        if (playingBeforeScrub) player.play();
        ui.postDelayed(hideIndicator, 600);
    }

    // ── Save this frame as an image ──────────────────────────────────────────

    private void saveFrame() {
        MediaInfo m = current();
        if (m == null || player == null) return;
        final long pos = player.getCurrentPosition();
        VideoSize vs = player.getVideoSize();
        View surface = playerView.getVideoSurfaceView();
        if (Build.VERSION.SDK_INT >= 24 && surface instanceof android.view.SurfaceView && vs.width > 0 && vs.height > 0) {
            try {
                Bitmap b = Bitmap.createBitmap(vs.width, vs.height, Bitmap.Config.ARGB_8888);
                android.view.PixelCopy.request((android.view.SurfaceView) surface, b, result -> {
                    if (result == android.view.PixelCopy.SUCCESS) storeFrame(b, m, pos);
                    else frameFromFile(m, pos);
                }, ui);
                return;
            } catch (Throwable ignored) { }
        }
        frameFromFile(m, pos);
    }

    private void frameFromFile(MediaInfo m, long pos) {
        new Thread(() -> {
            Bitmap b = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                String scheme = m.uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) r.setDataSource(m.uri.toString(), new HashMap<>());
                else r.setDataSource(this, m.uri);
                b = r.getFrameAtTime(pos * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
            } catch (Throwable ignored) {
            } finally { try { r.release(); } catch (Exception ignored) { } }
            final Bitmap f = b;
            ui.post(() -> { if (f != null) storeFrame(f, m, pos); else flash(getString(R.string.frame_failed)); });
        }).start();
    }

    /** Pictures/NKT Media Player/<video> 1m23s.jpg */
    private void storeFrame(Bitmap b, MediaInfo m, long pos) {
        new Thread(() -> {
            Uri saved = null;
            String name = (m.title.replaceAll("[\\\\/:*?\"<>|]", "_") + " " + Ui.time(pos).replace(":", "m") + "s.jpg");
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues v = new android.content.ContentValues();
                    v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
                    v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NKT Media Player");
                    saved = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    if (saved != null) try (java.io.OutputStream out = getContentResolver().openOutputStream(saved)) {
                        b.compress(Bitmap.CompressFormat.JPEG, 95, out);
                    }
                } else {
                    @SuppressWarnings("deprecation")
                    java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES), "NKT Media Player");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    java.io.File f = new java.io.File(dir, name);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 95, out); }
                    android.media.MediaScannerConnection.scanFile(getApplicationContext(), new String[]{f.getAbsolutePath()}, null, null);
                    saved = Uri.fromFile(f);
                }
            } catch (Throwable t) { saved = null; }
            final Uri done = saved;
            ui.post(() -> {
                if (done == null) { flash(getString(R.string.frame_failed)); return; }
                Ui.toast(this, getString(R.string.frame_saved), R.string.open, () -> {
                    MediaInfo pic = MediaInfo.link(done, name, false);
                    pic.image = true;
                    List<MediaInfo> one = new ArrayList<>();
                    one.add(pic);
                    ViewerActivity.open(this, one, 0);
                });
            });
        }).start();
    }

    // ── Speed & hold for 2× ──────────────────────────────────────────────────

    private void openSpeed() {
        SpeedSheet.show(this, new SpeedSheet.Target() {
            @Override public float speed() { return player == null ? 1f : player.getPlaybackParameters().speed; }
            @Override public void setSpeed(float s) { setVideoSpeed(s); }
        }, false);
    }

    /** Changes speed without changing the pitch of voices. */
    private void setVideoSpeed(float s) {
        if (player == null) return;
        player.setPlaybackParameters(new PlaybackParameters(s));
        speedBtn.setText(SpeedSheet.label(s));
        speedBtn.setTextColor(Math.abs(s - 1f) < 0.01f ? 0xFFFFFFFF : getColor(R.color.accent));
    }

    private void startHold() {
        if (player == null || scaling || !player.isPlaying()) return;
        holding = true;
        speedBeforeHold = player.getPlaybackParameters().speed;
        player.setPlaybackParameters(new PlaybackParameters(2f));
        holdBadge.setVisibility(View.VISIBLE);
        hideControls.run();
        playerView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private void endHold() {
        holding = false;
        holdBadge.setVisibility(View.GONE);
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(speedBeforeHold));
    }

    // ── Brightness, volume, zoom ─────────────────────────────────────────────

    private void changeBrightness(float delta) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        float b = lp.screenBrightness < 0 ? 0.5f : lp.screenBrightness;
        b = Math.max(0.02f, Math.min(1f, b + delta));
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);
        flash(getString(R.string.brightness_pct, Math.round(b * 100)));
    }

    private void changeVolume(float delta) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        volumeAcc += delta * max;
        int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (Math.abs(volumeAcc) >= 1) {
            int step = (int) volumeAcc;
            volumeAcc -= step;
            cur = Math.max(0, Math.min(max, cur + step));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, cur, 0);
        }
        flash(getString(R.string.volume_pct, Math.round(cur * 100f / Math.max(1, max))));
    }

    private void setZoom(boolean z) {
        zoomed = z;
        playerView.setResizeMode(z ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        flash(getString(z ? R.string.zoom_fill : R.string.zoom_fit));
    }

    // ── Rotation: auto / portrait / landscape ────────────────────────────────

    private void applyOrientation(int mode) {
        setRequestedOrientation(mode == 1 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : mode == 2 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_USER);
    }

    private void updateRotateIcon() {
        int mode = Prefs.videoOrientation(this);
        rotateBtn.setImageResource(mode == 0 ? R.drawable.ic_screen_rotation : R.drawable.ic_lock);
        rotateBtn.setColorFilter(mode == 0 ? 0xFFFFFFFF : getColor(R.color.accent));
    }

    private void chooseOrientation() {
        String[] names = getResources().getStringArray(R.array.orientations);
        Dialogs.builder(this)
                .setTitle(R.string.video_rotation)
                .setSingleChoiceItems(names, Prefs.videoOrientation(this), (d, w) -> {
                    Prefs.setVideoOrientation(this, w);
                    applyOrientation(w);
                    updateRotateIcon();
                    d.dismiss();
                    flash(names[w]);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Favourite, menu, background, picture-in-picture ──────────────────────

    private void updateFav() {
        MediaInfo m = current();
        boolean fav = m != null && Prefs.isFavourite(this, m);
        favBtn.setImageResource(fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        favBtn.setColorFilter(fav ? getColor(R.color.accent) : 0xFFFFFFFF);
    }

    private void toggleFavourite() {
        MediaInfo m = current();
        if (m == null) return;
        flash(getString(Prefs.toggleFavourite(this, m) ? R.string.fav_video_added : R.string.fav_video_removed));
        updateFav();
    }

    private void moreMenu() {
        MediaInfo m = current();
        if (m == null) return;
        String[] ends = getResources().getStringArray(R.array.video_end_choices);
        List<String> opts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        opts.add(getString(R.string.save_frame)); ids.add("frame");
        opts.add(getString(Prefs.karaoke(this) ? R.string.karaoke_menu_on : R.string.karaoke_menu_off)); ids.add("karaoke");
        opts.add(getString(R.string.video_end_menu, ends[Prefs.videoEnd(this)])); ids.add("end");
        opts.add(getString(R.string.background_play)); ids.add("bg");
        if (!m.remote) { opts.add(getString(R.string.save_sound)); ids.add("sound"); }
        opts.add(getString(zoomed ? R.string.zoom_fit_menu : R.string.zoom_fill_menu)); ids.add("zoom");
        opts.add(getString(R.string.add_to_playlist)); ids.add("add");
        opts.add(getString(R.string.share)); ids.add("share");
        if (!m.remote && !Downloads.isOnline(m)) { opts.add(getString(R.string.rename)); ids.add("rename"); }
        if (!m.remote && !Downloads.isOnline(m)) { opts.add(getString(R.string.delete_file)); ids.add("delete"); }
        opts.add(getString(R.string.properties)); ids.add("props");
        if (Downloads.isOnline(m)) { opts.add(getString(R.string.download)); ids.add("download"); }
        Dialogs.builder(this)
                .setTitle(m.title)
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    switch (ids.get(w)) {
                        case "frame": saveFrame(); break;
                        case "karaoke": {
                            boolean on = !Prefs.karaoke(this);
                            Prefs.setKaraoke(this, on);
                            flash(getString(on ? R.string.karaoke_now_on : R.string.karaoke_now_off));
                            break;
                        }
                        case "end":
                            Dialogs.builder(this).setTitle(R.string.video_end)
                                    .setSingleChoiceItems(ends, Prefs.videoEnd(this), (d2, w2) -> {
                                        Prefs.setVideoEnd(this, w2);
                                        applyVideoEnd();
                                        d2.dismiss();
                                        flash(ends[w2]);
                                    }).setNegativeButton(R.string.cancel, null).show();
                            break;
                        case "bg": backgroundPlay(); break;
                        case "sound": AudioExtract.save(this, m); break;
                        case "zoom": setZoom(!zoomed); break;
                        case "add": Dialogs.addToPlaylist(this, m); break;
                        case "share": Share.send(this, m); break;
                        case "props":
                            FileOps.properties(this, m, deleter, gone -> removeCurrent(), null);
                            break;
                        case "download": Downloads.start(this, m.uri, m.title); break;
                        case "rename": FileOps.rename(this, m, null); break;
                        case "delete": {
                            List<MediaInfo> one = new ArrayList<>();
                            one.add(m);
                            deleter.delete(one, gone -> removeCurrent());
                            break;
                        }
                    }
                })
                .show();
    }

    /** After deleting the video that is playing: next one, or close. */
    private void removeCurrent() {
        int i = player.getCurrentMediaItemIndex();
        if (i < 0 || i >= items.size()) return;
        items.remove(i);
        if (items.isEmpty()) { finish(); return; }
        player.removeMediaItem(i);
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

    /** Hands the videos to the music player: sound keeps playing with the screen off; controls in the notification. */
    private void backgroundPlay() {
        final int index = player.getCurrentMediaItemIndex();
        final long pos = player.getCurrentPosition();
        final float speed = player.getPlaybackParameters().speed;
        final List<MediaInfo> list = new ArrayList<>(items);
        player.pause();
        ListenableFuture<MediaController> f = Media.connect(getApplicationContext());
        f.addListener(() -> {
            try {
                MediaController c = f.get();
                c.setMediaItems(Media.items(list), index, pos);
                c.setPlaybackParameters(new PlaybackParameters(speed));
                c.prepare();
                c.play();
                new Handler(Looper.getMainLooper()).postDelayed(() -> MediaController.releaseFuture(f), 1500);
            } catch (Exception e) {
                Ui.toast(getApplicationContext(), getString(R.string.cant_play));
            }
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            finish();
        }, ui::post);
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            VideoSize vs = player.getVideoSize();
            Rational r = vs.width > 0 && vs.height > 0 ? new Rational(vs.width, vs.height) : new Rational(16, 9);
            float f = r.floatValue();
            if (f > 2.39f) r = new Rational(239, 100);
            if (f < 0.42f) r = new Rational(42, 100);
            hideControls.run();
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(r).build());
        } catch (Exception e) {
            flash(getString(R.string.pip_unavailable));
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        inPip = isInPip;
        if (isInPip) hideControls.run();
        indicator.setVisibility(View.GONE);
    }

    // ── Our control layer ────────────────────────────────────────────────────

    private final Runnable hideControls = () -> {
        controls.setVisibility(View.GONE);
        Ui.fullScreen(this);
    };

    /** Show the controls and hide them again after a few seconds of no touching (while playing). */
    private void keepControls() {
        if (inPip) return;
        controls.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideControls);
        if (player != null && player.isPlaying()) ui.postDelayed(hideControls, 3500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() { updateControls(); ui.postDelayed(this, 500); }
    };

    private void updateControls() {
        if (player == null) return;
        boolean end = atEnd() && !scrubbing;
        findViewById(R.id.vid_prev).setVisibility(player.hasPreviousMediaItem() ? View.VISIBLE : View.INVISIBLE);
        findViewById(R.id.vid_next).setVisibility(player.hasNextMediaItem() ? View.VISIBLE : View.INVISIBLE);
        playBtn.setImageResource(player.isPlaying() ? R.drawable.ic_pause : end ? R.drawable.ic_repeat : R.drawable.ic_play);
        if (!end && replayBtn.getVisibility() == View.VISIBLE) replayBtn.setVisibility(View.GONE);
        if (draggingBar) return;
        long dur = player.getDuration(), pos = player.getCurrentPosition();
        posText.setText(Ui.time(pos));
        durText.setText(dur > 0 ? Ui.time(dur) : "--:--");
        seekBar.setProgress(dur > 0 ? (int) (pos * 1000 / dur) : 0);
        seekBar.setSecondaryProgress(dur > 0 ? (int) (player.getBufferedPosition() * 1000 / dur) : 0);
        boolean playing = player.isPlaying();
        if (playing != wasPlaying) {               // started or paused: hide later / stay up
            wasPlaying = playing;
            ui.removeCallbacks(hideControls);
            if (playing && controls.getVisibility() == View.VISIBLE) ui.postDelayed(hideControls, 3500);
        }
    }

    private boolean wasPlaying = false;

    private void flash(String text) {
        indicator.setText(text);
        indicator.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideIndicator);
        ui.postDelayed(hideIndicator, 900);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.fullScreen(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (holding) endHold();
        if (player != null) player.pause();      // videos don't play in the background unless you choose Background play
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // a video opened from another app: Back shows the explorer at its folder
        if (!items.isEmpty() && ExternalOpen.isExternal(getIntent()) && ExternalOpen.back(this, items.get(0).uri)) return;
        super.onBackPressed();
    }
}
