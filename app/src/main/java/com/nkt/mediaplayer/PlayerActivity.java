package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Full-screen video player with its own controls, gestures, live scrubbing on the main picture, and PiP. */
public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView pv;
    private FrameLayout root;
    private View topBar, bottomBar;
    private TextView titleView, posView, durView, popup, speedBtn, holdBadge;
    private SeekBar seek;
    private ImageButton playBtn, loveBtn, rotBtn;
    private List<MediaInfo> items = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean controlsShown, scrubbing, inPip, zoomed;
    private float speed = 1f, heldSpeed;
    private long lastScrubSeek;

    // gestures
    private float downX, downY, startBright, startVol;
    private long startPos;
    private int gesture; // 0 none, 1 seek, 2 brightness, 3 volume
    private GestureDetector gd;
    private ScaleGestureDetector sgd;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int orient = Store.num(this, "video_orient", 0);
        setRequestedOrientation(orient == 1 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : orient == 2 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_USER);
        buildUi();
        setContentView(root);
        immersive();
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        pv.setPlayer(player);
        player.addListener(listener);
        load(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        load(i);
    }

    private void load(Intent in) {
        items = new ArrayList<>(Media.handoff);
        Media.handoff = new ArrayList<>();
        int idx = in.getIntExtra("index", 0);
        long position = in.getLongExtra("position", 0);
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            items = Collections.singletonList(Media.fromUri(this, in.getData(), MediaInfo.VIDEO));
            idx = 0;
        } else if (items.isEmpty() && in.getStringExtra("uri") != null) {
            MediaInfo m = new MediaInfo();
            m.uri = in.getStringExtra("uri");
            m.title = in.getStringExtra("title") == null ? "Video" : in.getStringExtra("title");
            m.kind = MediaInfo.VIDEO;
            items = Collections.singletonList(m);
        }
        if (items.isEmpty()) { finish(); return; }
        List<MediaItem> mi = new ArrayList<>();
        for (MediaInfo m : items) mi.add(new MediaItem.Builder().setUri(m.uri).setMediaId(m.uri).build());
        player.setMediaItems(mi, Math.max(0, Math.min(idx, items.size() - 1)), position);
        applyEndMode();
        player.prepare();
        player.play();
        hideControls();
    }

    /** 0 = play next video, 1 = go back, 2 = stop on the last frame. */
    private int endMode() { return Store.num(this, "video_end", 0); }

    private void applyEndMode() { if (player != null) player.setPauseAtEndOfMediaItems(endMode() != 0); }

    private MediaInfo current() {
        int i = player == null ? -1 : player.getCurrentMediaItemIndex();
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            MediaInfo m = current();
            if (m != null) {
                titleView.setText(m.title.isEmpty() ? m.name : m.title);
                Store.addHistory(PlayerActivity.this, m);
                updateLove();
            }
        }

        @Override public void onPlayWhenReadyChanged(boolean pwr, int reason) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (endMode() == 1) finish();
                else showControls(false);
            }
            updatePlayIcon();
        }

        @Override public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED) {
                if (endMode() == 1) finish(); else showControls(false);
            }
            updatePlayIcon();
        }

        @Override public void onIsPlayingChanged(boolean playing) { updatePlayIcon(); }

        @Override public void onPlayerError(@NonNull PlaybackException e) { Ui.msg(PlayerActivity.this, "Can't play this video"); }

        @Override public void onVideoSizeChanged(@NonNull VideoSize vs) { if (Build.VERSION.SDK_INT >= 26) updatePipParams(); }
    };

    // ---------------------------------------------------------------- UI
    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        pv = new PlayerView(this);
        pv.setUseController(false);
        pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        pv.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        pv.setKeepContentOnPlayerReset(true);
        root.addView(pv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        View touch = new View(this);
        root.addView(touch, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        setupGestures(touch);

        LinearLayout top = Ui.row(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0}));
        top.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 28));
        ImageButton back = Ui.iconBtn(this, "back", 44, 0xFFFFFFFF);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        titleView = Ui.single(Ui.text(this, "", 15, 0xFFFFFFFF));
        titleView.setPadding(Ui.dp(this, 8), 0, 0, 0);
        top.addView(titleView, Ui.lpw(0, Ui.WRAP, 1));
        topBar = top;
        root.addView(top, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        LinearLayout bottom = Ui.col(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0}));
        bottom.setPadding(Ui.dp(this, 16), Ui.dp(this, 36), Ui.dp(this, 16), Ui.dp(this, 10));
        LinearLayout r1 = Ui.row(this);
        posView = Ui.text(this, "0:00", 13, 0xFFFFFFFF);
        durView = Ui.text(this, "0:00", 13, 0xFFFFFFFF);
        seek = new SeekBar(this);
        Ui.styleSeek(seek);
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x54FFFFFF));
        seek.setSecondaryProgressTintList(android.content.res.ColorStateList.valueOf(0x87FFFFFF));
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user || player == null) return;
                long target = player.getDuration() * p / 1000;
                scrubTo(target, target - startPos);
            }
            public void onStartTrackingTouch(SeekBar s) { startScrub(); }
            public void onStopTrackingTouch(SeekBar s) { endScrub(player.getDuration() * s.getProgress() / 1000); }
        });
        r1.addView(posView);
        r1.addView(seek, Ui.lpw(0, Ui.WRAP, 1));
        r1.addView(durView);
        bottom.addView(r1);
        LinearLayout r2 = Ui.row(this);
        rotBtn = ctrl(r2, "rotate", v -> rotationDialog());
        speedBtn = Ui.label(this, "1×", 13, 0xFFFFFFFF, 0f);
        speedBtn.setGravity(Gravity.CENTER);
        speedBtn.setOnClickListener(v -> { touchControls(); NowPlayingActivity.speedSheet(this, false); });
        r2.addView(speedBtn, Ui.lpw(0, Ui.dp(this, 46), 1));
        ctrl(r2, "prev", v -> { if (player != null) player.seekToPrevious(); touchControls(); });
        playBtn = ctrl(r2, "pause", v -> { if (player != null) { if (player.isPlaying()) player.pause(); else { if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0); player.play(); } } touchControls(); });
        playBtn.setImageDrawable(new Icon(this, "pause", Ui.ACCENT));
        ctrl(r2, "next", v -> { if (player != null) player.seekToNext(); touchControls(); });
        ctrl(r2, "camera", v -> saveFrame());
        loveBtn = ctrl(r2, "heart", v -> {
            MediaInfo m = current();
            if (m == null) return;
            boolean on = Store.toggleFav(this, m);
            Ui.msg(this, on ? "Added to ♥ Favourite videos" : "Removed from ♥ Favourite videos");
            updateLove();
        });
        ImageButton pip = ctrl(r2, "pip", v -> enterPip());
        if (Build.VERSION.SDK_INT < 26 || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) pip.setVisibility(View.GONE);
        ctrl(r2, "more", v -> menu());
        bottom.addView(r2);
        bottomBar = bottom;
        root.addView(bottom, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        popup = Ui.label(this, "", 15, Ui.ACCENT, 0f);
        popup.setAllCaps(false);
        popup.setGravity(Gravity.CENTER);
        popup.setBackground(Ui.rect(this, 0xCC05080F, 24, 0x5900E5FF, 1));
        popup.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        popup.setVisibility(View.GONE);
        root.addView(popup, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER));

        holdBadge = Ui.label(this, "2× »", 13, Ui.ACCENT, 0.1f);
        holdBadge.setBackground(Ui.rect(this, 0xCC05080F, 24, 0x5900E5FF, 1));
        holdBadge.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        holdBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams hb = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hb.topMargin = Ui.dp(this, 24);
        root.addView(holdBadge, hb);
        updateRotIcon();
    }

    private ImageButton ctrl(LinearLayout row, String icon, View.OnClickListener l) {
        ImageButton b = Ui.iconBtn(this, icon, 46, 0xFFFFFFFF);
        b.setOnClickListener(l);
        row.addView(b, Ui.lpw(0, Ui.dp(this, 46), 1));
        return b;
    }

    private void updatePlayIcon() {
        if (player == null) return;
        boolean playing = player.isPlaying() || (player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_BUFFERING);
        playBtn.setImageDrawable(new Icon(this, playing ? "pause" : "play", Ui.ACCENT));
        if (!playing) showControls(false);
    }

    private void updateLove() {
        MediaInfo m = current();
        boolean on = m != null && Store.isFav(this, m);
        loveBtn.setImageDrawable(new Icon(this, on ? "heart_filled" : "heart", on ? Ui.ACCENT : 0xFFFFFFFF));
    }

    private void updateRotIcon() {
        int o = Store.num(this, "video_orient", 0);
        rotBtn.setImageDrawable(new Icon(this, o == 0 ? "rotate" : "lock", o == 0 ? 0xFFFFFFFF : Ui.ACCENT));
    }

    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (player != null && !scrubbing) {
                long d = Math.max(0, player.getDuration()), p = player.getCurrentPosition();
                posView.setText(Ui.time(p));
                durView.setText(Ui.time(d));
                seek.setProgress(d > 0 ? (int) (p * 1000 / d) : 0);
                seek.setSecondaryProgress(d > 0 ? (int) (player.getBufferedPosition() * 1000 / d) : 0);
            }
            h.postDelayed(this, 500);
        }
    };

    private final Runnable autoHide = () -> { if (player != null && player.isPlaying() && !scrubbing) hideControls(); };

    private void showControls(boolean autoHideToo) {
        if (inPip) return;
        controlsShown = true;
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        h.removeCallbacks(autoHide);
        if (autoHideToo) h.postDelayed(autoHide, 3500);
    }

    private void touchControls() { showControls(true); }

    private void hideControls() {
        controlsShown = false;
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    private void pop(String s) {
        popup.setText(s);
        popup.setVisibility(View.VISIBLE);
        h.removeCallbacks(hidePop);
        h.postDelayed(hidePop, 900);
    }

    private final Runnable hidePop = () -> popup.setVisibility(View.GONE);

    // ---------------------------------------------------------------- scrubbing on the main picture
    private void startScrub() {
        if (player == null) return;
        scrubbing = true;
        startPos = player.getCurrentPosition();
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
        h.removeCallbacks(autoHide);
    }

    /** Moves the actual video to the target so the main picture shows that frame. */
    private void scrubTo(long target, long delta) {
        long d = Math.max(0, player.getDuration());
        target = Math.max(0, Math.min(d, target));
        long now = System.currentTimeMillis();
        if (now - lastScrubSeek > 90) {
            player.seekTo(target);
            lastScrubSeek = now;
        }
        posView.setText(Ui.time(target));
        String sign = delta >= 0 ? "» +" : "« −";
        popup.setText(sign + Ui.time(Math.abs(delta)) + "\n" + Ui.time(target) + " / " + Ui.time(d));
        popup.setVisibility(View.VISIBLE);
        h.removeCallbacks(hidePop);
    }

    private void endScrub(long target) {
        if (player == null) return;
        player.setSeekParameters(SeekParameters.EXACT);
        player.seekTo(Math.max(0, target));
        scrubbing = false;
        h.postDelayed(hidePop, 400);
        touchControls();
    }

    // ---------------------------------------------------------------- gestures
    private void setupGestures(View v) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (controlsShown) hideControls(); else touchControls();
                return true;
            }
            @Override public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (player == null) return true;
                boolean right = e.getX() > v.getWidth() / 2f;
                player.seekTo(Math.max(0, player.getCurrentPosition() + (right ? 10_000 : -10_000)));
                pop(right ? "» 10 s" : "« 10 s");
                return true;
            }
            @Override public void onLongPress(@NonNull MotionEvent e) {
                if (player == null || gesture != 0 || !player.isPlaying()) return;
                heldSpeed = speed;
                player.setPlaybackParameters(new PlaybackParameters(2f));
                holdBadge.setVisibility(View.VISIBLE);
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                hideControls();
                gesture = 9;
            }
        });
        sgd = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(@NonNull ScaleGestureDetector d) {
                if (d.getScaleFactor() > 1.08f && !zoomed) setZoom(true);
                else if (d.getScaleFactor() < 0.92f && zoomed) setZoom(false);
                return true;
            }
        });
        v.setOnTouchListener((view, e) -> {
            if (inPip) return true;
            sgd.onTouchEvent(e);
            if (e.getPointerCount() > 1) { gesture = 8; return true; }
            gd.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); gesture = 0;
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    startBright = lp.screenBrightness < 0 ? 0.5f : lp.screenBrightness;
                    startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    startPos = player == null ? 0 : player.getCurrentPosition();
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    int slop = Ui.dp(this, 14);
                    if (gesture == 0 && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        if (Math.abs(dy) > Math.abs(dx)) gesture = downX < view.getWidth() / 2f ? 2 : 3;
                        else { gesture = 1; startScrub(); }
                    }
                    if (gesture == 1 && player != null) {
                        long delta = (long) (dx / Ui.dp(this, 12) * 1000);
                        scrubTo(startPos + delta, delta);
                    } else if (gesture == 2) {
                        float b = Math.max(0.02f, Math.min(1f, startBright - dy / view.getHeight()));
                        WindowManager.LayoutParams p = getWindow().getAttributes();
                        p.screenBrightness = b;
                        getWindow().setAttributes(p);
                        pop("Brightness " + Math.round(b * 100) + "%");
                    } else if (gesture == 3) {
                        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        int vol = Math.round(Math.max(0, Math.min(max, startVol - dy / view.getHeight() * max * 1.2f)));
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
                        pop("Volume " + Math.round(vol * 100f / max) + "%");
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (gesture == 1 && player != null) {
                        long delta = (long) ((e.getX() - downX) / Ui.dp(this, 12) * 1000);
                        endScrub(startPos + delta);
                    } else if (gesture == 9 && player != null) {
                        player.setPlaybackParameters(new PlaybackParameters(heldSpeed));
                        holdBadge.setVisibility(View.GONE);
                    }
                    gesture = 0;
                    break;
            }
            return true;
        });
    }

    private void setZoom(boolean fill) {
        if (zoomed == fill) return;
        zoomed = fill;
        pv.setResizeMode(fill ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        pop(fill ? "Zoom to fill" : "Fit to screen");
    }

    // ---------------------------------------------------------------- speed (used by the shared sheet)
    public float speed() { return speed; }

    public void setSpeed(float s) {
        speed = s;
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(s));
        speedBtn.setText(NowPlayingActivity.fmtSpeed(s).replace(".0×", "×"));
        speedBtn.setTextColor(Math.abs(s - 1f) < 0.01f ? 0xFFFFFFFF : Ui.ACCENT);
    }

    // ---------------------------------------------------------------- menu + actions
    private void menu() {
        touchControls();
        MediaInfo m = current();
        if (m == null) return;
        String[] ends = {"Play next video", "Go back", "Stop on last frame"};
        List<String> o = new ArrayList<>();
        o.add("Save this frame as image");
        o.add("Background play (sound only)");
        o.add("When this video ends: " + ends[endMode()]);
        o.add(zoomed ? "Zoom: fit to screen" : "Zoom: fill screen");
        o.add("Speed");
        o.add("Add to playlist");
        o.add("Share");
        if (m.isOnline()) o.add("Download");
        o.add("Details");
        new AlertDialog.Builder(this).setItems(o.toArray(new String[0]), (d, w) -> {
            String s = o.get(w);
            if (s.startsWith("Save this frame")) saveFrame();
            else if (s.startsWith("Background")) background();
            else if (s.startsWith("When this video ends")) endDialog();
            else if (s.startsWith("Zoom")) setZoom(!zoomed);
            else if (s.equals("Speed")) NowPlayingActivity.speedSheet(this, false);
            else if (s.equals("Add to playlist")) Actions.addToPlaylist(this, Collections.singletonList(m));
            else if (s.equals("Share")) Media.share(this, Collections.singletonList(m));
            else if (s.equals("Download")) Downloads.get(this, m.uri, m.title);
            else Actions.details(this, m, null);
        }).show();
    }

    private void endDialog() {
        String[] ends = {"Play next video", "Go back", "Stop on last frame"};
        new AlertDialog.Builder(this).setTitle("When a video ends").setSingleChoiceItems(ends, endMode(), (d, w) -> {
            Store.put(this, "video_end", w);
            applyEndMode();
            d.dismiss();
        }).show();
    }

    private void rotationDialog() {
        touchControls();
        String[] o = {"Auto rotate", "Lock portrait", "Lock landscape"};
        new AlertDialog.Builder(this).setTitle("Video rotation").setSingleChoiceItems(o, Store.num(this, "video_orient", 0), (d, w) -> {
            Store.put(this, "video_orient", w);
            setRequestedOrientation(w == 1 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    : w == 2 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_USER);
            updateRotIcon();
            d.dismiss();
        }).show();
    }

    /** Hands the video list to the music service and keeps playing the sound. */
    private void background() {
        if (player == null) return;
        int idx = player.getCurrentMediaItemIndex();
        long p = player.getCurrentPosition();
        float s = speed;
        List<MediaInfo> l = new ArrayList<>(items);
        player.pause();
        List<MediaItem> mi = new ArrayList<>();
        for (MediaInfo m : l) { m.kind = MediaInfo.VIDEO; mi.add(Media.item(m)); }
        Media.get(this, c -> {
            c.setMediaItems(mi, idx, p);
            c.setPlaybackParameters(new PlaybackParameters(s));
            c.prepare();
            c.play();
        });
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    /** Saves the frame on screen as a JPEG in Pictures/NKT MP. */
    private void saveFrame() {
        MediaInfo m = current();
        if (m == null || player == null) return;
        long posUs = player.getCurrentPosition() * 1000L;
        Ui.msg(this, "Saving this frame…");
        new Thread(() -> {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                Uri u = m.u();
                if ("content".equals(u.getScheme())) r.setDataSource(this, u);
                else if (m.isOnline()) r.setDataSource(m.uri, new java.util.HashMap<>());
                else r.setDataSource(m.path.isEmpty() ? u.getPath() : m.path);
                Bitmap b = r.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (b == null) throw new Exception("no frame");
                String name = (m.title.replaceAll("[\\\\/:*?\"<>|]", "_") + " " + Ui.time(posUs / 1000).replace(':', '.')) + ".jpg";
                saveJpeg(b, name);
                runOnUiThread(() -> Ui.msg(this, "Frame saved in Pictures/NKT MP"));
            } catch (Exception e) {
                runOnUiThread(() -> Ui.msg(this, "Couldn't save this frame"));
            } finally {
                try { r.release(); } catch (Exception ignored) {}
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private void saveJpeg(Bitmap b, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NKT MP");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("insert failed");
            try (OutputStream o = getContentResolver().openOutputStream(u)) { b.compress(Bitmap.CompressFormat.JPEG, 95, o); }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(u, v, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NKT MP");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, name);
            try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 95, o); }
            MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
        }
    }

    // ---------------------------------------------------------------- picture-in-picture
    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) return;
        try { enterPictureInPictureMode(pipParams()); } catch (Exception ignored) {}
    }

    private PictureInPictureParams pipParams() {
        Rational r = new Rational(16, 9);
        if (player != null) {
            VideoSize vs = player.getVideoSize();
            if (vs.width > 0 && vs.height > 0) {
                float a = (float) vs.width / vs.height;
                a = Math.max(0.42f, Math.min(2.39f, a));
                r = new Rational(Math.round(a * 1000), 1000);
            }
        }
        return new PictureInPictureParams.Builder().setAspectRatio(r).build();
    }

    private void updatePipParams() { try { setPictureInPictureParams(pipParams()); } catch (Exception ignored) {} }

    @Override
    public void onPictureInPictureModeChanged(boolean pip, Configuration cfg) {
        super.onPictureInPictureModeChanged(pip, cfg);
        inPip = pip;
        if (pip) { hideControls(); popup.setVisibility(View.GONE); }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (player != null && player.isPlaying() && Build.VERSION.SDK_INT >= 26 && Store.bool(this, "auto_pip", false)) enterPip();
    }

    // ---------------------------------------------------------------- lifecycle
    @Override protected void onStart() { super.onStart(); h.post(progress); }

    @Override
    protected void onStop() {
        super.onStop();
        h.removeCallbacks(progress);
        if (player != null) player.pause();
        if (inPip) finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        boolean pip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
        if (!pip && player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Actions.onResult(this, req, res);
    }

}
