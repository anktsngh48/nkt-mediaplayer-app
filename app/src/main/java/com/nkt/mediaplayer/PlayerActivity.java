package com.nkt.mediaplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen video.
 * Gestures: swipe up/down on the left = brightness, on the right = volume,
 * double-tap left/right = back/forward 10 s, pinch = zoom to fill / fit, tap = show controls,
 * press and hold anywhere = 2× while held.
 * Top bar: back, title, speed, zoom, rotation lock.
 */
public class PlayerActivity extends Activity {

    private static final String EXTRA_URIS = "uris", EXTRA_TITLES = "titles", EXTRA_INDEX = "index";

    public static void open(Context c, List<MediaInfo> list, int index) {
        String[] uris = new String[list.size()], titles = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            uris[i] = list.get(i).uri.toString();
            titles[i] = list.get(i).title;
        }
        Intent i = new Intent(c, PlayerActivity.class)
                .putExtra(EXTRA_URIS, uris)
                .putExtra(EXTRA_TITLES, titles)
                .putExtra(EXTRA_INDEX, index);
        c.startActivity(i);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private PlayerView playerView;
    private View topBar;
    private TextView titleView, indicator, speedBtn, holdBadge;
    private boolean holding = false;
    private float speedBeforeHold = 1f;
    private ImageButton rotateBtn, zoomBtn;
    private AudioManager audio;
    private boolean rotationLocked = false, zoomed = false, scaling = false;
    private int scrollMode = 0;          // 0 = undecided, 1 = brightness, 2 = volume, 3 = horizontal (ignored)
    private float volumeAcc = 0, scaleAcc = 1;
    private String[] titles = new String[0];

    private final Runnable hideIndicator = () -> indicator.setVisibility(View.GONE);

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.fullScreen(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);

        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        playerView = findViewById(R.id.player_view);
        topBar = findViewById(R.id.top_bar);
        titleView = findViewById(R.id.video_title);
        indicator = findViewById(R.id.indicator);
        rotateBtn = findViewById(R.id.btn_rotate);
        zoomBtn = findViewById(R.id.btn_zoom);
        indicator.setTypeface(Ui.orbitron(this));
        speedBtn = findViewById(R.id.btn_speed);
        holdBadge = findViewById(R.id.hold_badge);
        holdBadge.setTypeface(Ui.orbitron(this));
        speedBtn.setOnClickListener(v -> openSpeed());

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        rotateBtn.setOnClickListener(v -> toggleRotationLock());
        zoomBtn.setOnClickListener(v -> setZoom(!zoomed));

        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), /* handleAudioFocus= */ true)   // pauses the music player while a video plays
                .setHandleAudioBecomingNoisy(true)
                .build();
        playerView.setPlayer(player);
        playerView.setControllerVisibilityListener(new PlayerView.ControllerVisibilityListener() {
            @Override public void onVisibilityChanged(int visibility) {
                topBar.setVisibility(visibility);
                if (visibility != View.VISIBLE) Ui.fullScreen(PlayerActivity.this);
            }
        });
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(MediaItem item, int reason) { updateTitle(); }
            @Override public void onPlayerError(PlaybackException error) { flash(getString(R.string.cant_play)); }
        });

        load(getIntent());
        setupGestures();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        load(intent);
    }

    private void load(Intent i) {
        List<MediaItem> items = new ArrayList<>();
        int index = 0;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            // "Open with NKT Media Player" on a video file
            Uri u = i.getData();
            titles = new String[]{Ui.displayName(this, u)};
            items.add(new MediaItem.Builder().setUri(u).setMediaId(u.toString()).build());
        } else {
            String[] uris = i.getStringArrayExtra(EXTRA_URIS);
            String[] t = i.getStringArrayExtra(EXTRA_TITLES);
            if (uris == null || uris.length == 0) { finish(); return; }
            titles = t != null && t.length == uris.length ? t : new String[uris.length];
            for (String s : uris) items.add(new MediaItem.Builder().setUri(Uri.parse(s)).setMediaId(s).build());
            index = Math.max(0, Math.min(i.getIntExtra(EXTRA_INDEX, 0), uris.length - 1));
        }
        player.setMediaItems(items, index, 0);
        player.prepare();
        player.play();
        updateTitle();
    }

    private void updateTitle() {
        int i = player.getCurrentMediaItemIndex();
        String t = i >= 0 && i < titles.length ? titles[i] : null;
        titleView.setText(t == null ? "" : t);
    }

    // ── Gestures ─────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private void setupGestures() {
        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { scrollMode = 0; volumeAcc = 0; return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (playerView.isControllerFullyVisible()) playerView.hideController();
                else playerView.showController();
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

            @Override public void onLongPress(MotionEvent e) { startHold(); }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null || scaling) return false;
                if (scrollMode == 0) {
                    if (Math.abs(dy) > Math.abs(dx)) scrollMode = e1.getX() < playerView.getWidth() / 2f ? 1 : 2;
                    else scrollMode = 3;
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
            int act = ev.getActionMasked();
            if (holding && (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL)) { endHold(); return true; }
            if (holding) return true;
            pinch.onTouchEvent(ev);
            scaling = pinch.isInProgress() || ev.getPointerCount() > 1;
            if (!scaling) taps.onTouchEvent(ev);
            return true;
        });
    }

    // ── Speed ────────────────────────────────────────────────────────────────

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
        speedBtn.setTextColor(getColor(Math.abs(s - 1f) < 0.01f ? R.color.text : R.color.accent));
    }

    private void startHold() {
        if (player == null || scaling || !player.isPlaying()) return;
        holding = true;
        speedBeforeHold = player.getPlaybackParameters().speed;
        player.setPlaybackParameters(new PlaybackParameters(2f));
        holdBadge.setVisibility(View.VISIBLE);
        playerView.hideController();
        playerView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }

    private void endHold() {
        holding = false;
        holdBadge.setVisibility(View.GONE);
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(speedBeforeHold));
    }

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
        zoomBtn.setColorFilter(getColor(z ? R.color.accent : R.color.text));
        flash(getString(z ? R.string.zoom_fill : R.string.zoom_fit));
    }

    private void toggleRotationLock() {
        rotationLocked = !rotationLocked;
        setRequestedOrientation(rotationLocked ? ActivityInfo.SCREEN_ORIENTATION_LOCKED : ActivityInfo.SCREEN_ORIENTATION_USER);
        rotateBtn.setImageResource(rotationLocked ? R.drawable.ic_lock : R.drawable.ic_lock_open);
        rotateBtn.setColorFilter(getColor(rotationLocked ? R.color.accent : R.color.text));
        flash(getString(rotationLocked ? R.string.rotation_locked : R.string.rotation_auto));
    }

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
        if (player != null) player.pause();      // videos don't play in the background
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
