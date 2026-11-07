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
import android.os.HandlerThread;
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
import androidx.media3.session.MediaController;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Full-screen video. Controls stay hidden until you tap; they all sit at the bottom.
 * Gestures: swipe up/down left = brightness, right = volume; slide sideways = fine seek (with preview);
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
    private TextView titleView, indicator, speedBtn, holdBadge, seekTime, posText, durText;
    private View seekPreview, controls;
    private SeekBar seekBar;
    private ImageButton playBtn;
    private boolean draggingBar = false;
    private ImageView seekImg;
    private ImageButton rotateBtn, favBtn;
    private AudioManager audio;
    private boolean zoomed = false, scaling = false, holding = false, inPip = false;
    private int scrollMode = 0;          // 0 = undecided, 1 = brightness, 2 = volume, 3 = seek
    private float volumeAcc = 0, scaleAcc = 1, speedBeforeHold = 1f;
    private long seekStartPos = 0, seekTarget = -1;
    private final List<MediaInfo> items = new ArrayList<>();

    // timeline preview frames
    private HandlerThread frameThread;
    private Handler frames;
    private MediaMetadataRetriever retriever;
    private String retrieverFor;

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
        seekPreview = findViewById(R.id.seek_preview);
        seekImg = findViewById(R.id.seek_img);
        seekImg.setClipToOutline(true);
        seekTime = findViewById(R.id.seek_time);

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
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        rotateBtn.setOnClickListener(v -> { keepControls(); chooseOrientation(); });
        speedBtn.setOnClickListener(v -> { keepControls(); openSpeed(); });
        favBtn.setOnClickListener(v -> { keepControls(); toggleFavourite(); });
        findViewById(R.id.btn_more).setOnClickListener(v -> { keepControls(); moreMenu(); });
        playBtn.setOnClickListener(v -> {
            keepControls();
            if (player.isPlaying()) player.pause();
            else {
                if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
                player.play();
            }
        });
        findViewById(R.id.ctl_prev).setOnClickListener(v -> { keepControls(); player.seekToPrevious(); });
        findViewById(R.id.ctl_next).setOnClickListener(v -> { keepControls(); if (player.getNextMediaItemIndex() != C.INDEX_UNSET) player.seekToNextMediaItem(); });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser || player.getDuration() <= 0) return;
                long pos = player.getDuration() * p / 1000;
                posText.setText(Ui.time(pos));
                showPreview(pos, sb);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { draggingBar = true; ui.removeCallbacks(hideControls); }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                draggingBar = false;
                seekPreview.setVisibility(View.GONE);
                if (player.getDuration() > 0) player.seekTo(player.getDuration() * sb.getProgress() / 1000);
                keepControls();
            }
        });
        View pip = findViewById(R.id.btn_pip);
        if (Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature("android.software.picture_in_picture"))
            pip.setOnClickListener(v -> enterPip());
        else pip.setVisibility(View.GONE);
        updateRotateIcon();

        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), /* handleAudioFocus= */ true)   // pauses the music player while a video plays
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.setPauseAtEndOfMediaItems(!autoNext());
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(MediaItem item, int reason) { onNewItem(); }
            @Override public void onPlayerError(PlaybackException error) { flash(getString(R.string.cant_play)); }
            @Override public void onEvents(Player p, Player.Events e) { updateControls(); }
        });
        ui.post(tick);

        frameThread = new HandlerThread("frames");
        frameThread.start();
        frames = new Handler(frameThread.getLooper());

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

    private boolean autoNext() { return Prefs.sp(this).getBoolean("video_autonext", true); }

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
                    showPreview(target, null);
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
                if (scrollMode == 3 && seekTarget >= 0) {
                    if (act == MotionEvent.ACTION_UP) player.seekTo(seekTarget);
                    seekTarget = -1;
                    seekPreview.setVisibility(View.GONE);
                    ui.postDelayed(hideIndicator, 600);
                }
            }
            pinch.onTouchEvent(ev);
            scaling = pinch.isInProgress() || ev.getPointerCount() > 1;
            if (!scaling) taps.onTouchEvent(ev);
            return true;
        });
    }

    // ── Timeline preview ─────────────────────────────────────────────────────

    /** Shows the frame at pos. anchor = the seek bar (preview follows the finger) or null (centred). */
    private void showPreview(long pos, View anchor) {
        seekTime.setText(Ui.time(pos));
        seekPreview.setVisibility(View.VISIBLE);
        seekPreview.post(() -> {
            float w = seekPreview.getWidth(), screen = ((View) seekPreview.getParent()).getWidth();
            float x;
            if (anchor != null && player.getDuration() > 0) {
                int[] loc = new int[2];
                anchor.getLocationInWindow(loc);
                x = loc[0] + anchor.getWidth() * (pos / (float) player.getDuration()) - w / 2;
            } else x = (screen - w) / 2;
            seekPreview.setTranslationX(Math.max(8, Math.min(screen - w - 8, x)));
        });
        final MediaInfo m = current();
        if (m == null) return;
        frames.removeCallbacksAndMessages(null);
        frames.post(() -> {
            Bitmap b = frameAt(m, pos);
            if (b != null) ui.post(() -> { if (seekPreview.getVisibility() == View.VISIBLE) seekImg.setImageBitmap(b); });
        });
    }

    /** Runs on the frame thread. */
    private Bitmap frameAt(MediaInfo m, long pos) {
        try {
            if (!m.key().equals(retrieverFor)) {
                if (retriever != null) try { retriever.release(); } catch (Exception ignored) { }
                retriever = new MediaMetadataRetriever();
                String scheme = m.uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) retriever.setDataSource(m.uri.toString(), new HashMap<>());
                else retriever.setDataSource(this, m.uri);
                retrieverFor = m.key();
            }
            long us = pos * 1000L;
            if (Build.VERSION.SDK_INT >= 27)
                return retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 352, 198);
            return retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Throwable t) {
            retrieverFor = null;
            return null;
        }
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
        String[] opts = new String[]{
                getString(R.string.background_play),
                getString(R.string.save_sound),
                getString(autoNext() ? R.string.autonext_on : R.string.autonext_off),
                getString(zoomed ? R.string.zoom_fit_menu : R.string.zoom_fill_menu),
                getString(R.string.add_to_playlist),
                getString(R.string.share),
                getString(R.string.details),
                getString(R.string.download)};
        if (!Downloads.isOnline(m)) opts = java.util.Arrays.copyOf(opts, opts.length - 1);
        Dialogs.builder(this)
                .setTitle(m.title)
                .setItems(opts, (d, w) -> {
                    switch (w) {
                        case 0: backgroundPlay(); break;
                        case 1: AudioExtract.save(this, m); break;
                        case 2: {
                            boolean on = !autoNext();
                            Prefs.sp(this).edit().putBoolean("video_autonext", on).apply();
                            player.setPauseAtEndOfMediaItems(!on);
                            flash(getString(on ? R.string.autonext_now_on : R.string.autonext_now_off));
                            break;
                        }
                        case 3: setZoom(!zoomed); break;
                        case 4: Dialogs.addToPlaylist(this, m); break;
                        case 5: Share.send(this, m); break;
                        case 6: Dialogs.details(this, m); break;
                        case 7: Downloads.start(this, m.uri, m.title); break;
                    }
                })
                .show();
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
        seekPreview.setVisibility(View.GONE);
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
        playBtn.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
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
        if (frames != null) frames.removeCallbacksAndMessages(null);
        if (frameThread != null) {
            final MediaMetadataRetriever r = retriever;
            frames.post(() -> { if (r != null) try { r.release(); } catch (Exception ignored) { } });
            frameThread.quitSafely();
        }
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
