package com.nkt.mediaplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Listen: play another phone's Stream in this app. Phones that are streaming on the same Wi-Fi show up by themselves;
 * the address can also be typed or pasted. Playing goes on in the background (the notification has STOP).
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class ListenActivity extends Activity {

    private static final int REQ_NOTIF = 32;
    private static final String PREF_LAST = "listen_last";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private StreamNsd.Finder finder;

    private LinearLayout idleBox, liveBox, foundRows;
    private TextView searching, problem, liveTitle, liveArtist, liveStatus, liveHost, tCur, tDur, volT, nudT;
    private ImageView art;
    private PlayerView videoView;
    private ProgressBar bar;
    private EditText address;
    private Bitmap shownArt;
    private boolean busy = false;
    private final Map<String, String[]> found = new LinkedHashMap<>();   // key -> label, url
    private ExoPlayer attached;

    // What is shown while listening: the host's visualiser, lyrics or art
    private static final int V_VIZ = 0, V_LYR = 1, V_ART = 2;
    private FrameLayout stage;
    private VizView viz;
    private ScrollView lyrScroll;
    private LinearLayout lyrBox, viewPills, followRow, libRow;
    private View libBtn, backLiveBtn, fsBtn;
    private LinearLayout ownRow, autoRow, timesRow, ctlGroup, liveRow;
    private final TextView[] liveBtn = new TextView[4];    // back 10 s, play/pause, forward 10 s, go live (the host's live video)
    private TextView fsFwd, fsGo;
    private ImageView waitArt;
    private View nudgeHint, dlBtn;
    private final TextView[] ownBtn = new TextView[5];     // previous, -10 s, play/pause, +10 s, next
    private Switch autoSw;
    private android.widget.SeekBar fsSeek;
    private TextView fsPlay;
    private boolean fsSeeking = false;
    private long seenLibOff = 0;
    private ImageView photoView;
    private Bitmap photoBmp;
    private String photoShownId = "", photoAsking = "";
    private final TextView[] lyrTv = new TextView[5];
    private Switch followSw;
    private final TextView[] viewPill = new TextView[3];
    private boolean follow = true;
    private int localView = V_ART;

    // full screen for video and photos
    private FrameLayout fsRoot;
    private LinearLayout fsBar;
    private View fsView;
    private ViewGroup fsParent;
    private int fsIndex;
    private ViewGroup.LayoutParams fsLp;
    private boolean fsVideo;
    private final Runnable fsHide = () -> { if (fsBar != null && !fsSeeking) fsBar.setVisibility(View.GONE); };

    // lyrics of the song being shown
    private String lyrId = "", lyrAsking = "";
    private long[] lyrT = new long[0];
    private String[] lyrS = new String[0];
    private boolean lyrSynced, lyrInstr;
    private long lyrOffset;
    private int lyrShown = -9;

    // the host's beat and pitch, played back in time with the sound
    private final java.util.ArrayList<double[]> samples = new java.util.ArrayList<>();
    private volatile boolean vizPolling = false;
    private volatile int vizGen = 0;
    private volatile long vizAfter = -1;
    private volatile int vizSong = -1;
    private volatile int vizBpm = 0, vizAutoSa = -1;
    private int seenBeat = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() { update(); ui.postDelayed(this, 500); }
    };

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), 0, dp(20), dp(28));
        root.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        Ui.padForSystemBars(root);

        // top row
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setBackgroundResource(R.drawable.bg_round_btn);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(getColor(R.color.accent));
        back.setContentDescription(getString(R.string.cd_back));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text(getString(R.string.listen_caps), 13, R.color.accent, false);
        title.setTypeface(Ui.orbitron(this));
        title.setLetterSpacing(0.2f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(14));
        top.addView(title, tl);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.topMargin = dp(8);
        col.addView(top, topLp);

        // ── not listening ──
        idleBox = new LinearLayout(this);
        idleBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(idleBox, lp(MATCH, WRAP, 0, 0));

        problem = text("", 13, R.color.text, false);
        problem.setBackground(stroked(0x14FF4F6A, 0x66FF4F6A, 10));
        problem.setPadding(dp(12), dp(10), dp(12), dp(10));
        problem.setVisibility(View.GONE);
        idleBox.addView(problem, lp(MATCH, WRAP, 18, 0));

        idleBox.addView(label(getString(R.string.listen_nearby_caps)), lp(MATCH, WRAP, 22, 0));
        foundRows = new LinearLayout(this);
        foundRows.setOrientation(LinearLayout.VERTICAL);
        idleBox.addView(foundRows, lp(MATCH, WRAP, 8, 0));
        searching = text(getString(R.string.listen_searching), 12, R.color.muted, false);
        idleBox.addView(searching, lp(MATCH, WRAP, 8, 0));

        idleBox.addView(label(getString(R.string.listen_type_caps)), lp(MATCH, WRAP, 26, 0));
        address = field(Prefs.sp(this).getString(PREF_LAST, ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        address.setHint(R.string.listen_hint);
        address.setHintTextColor(getColor(R.color.muted));
        idleBox.addView(address, lp(MATCH, 50, 8, 0));
        LinearLayout btns = new LinearLayout(this);
        View paste = outlined(getString(R.string.paste_caps), v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (t != null) address.setText(t.toString().trim());
            }
        });
        btns.addView(paste, new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView go = new TextView(this);
        go.setText(R.string.connect_caps);
        go.setGravity(Gravity.CENTER);
        go.setTypeface(Ui.orbitron(this));
        go.setTextSize(13);
        go.setLetterSpacing(0.14f);
        go.setTextColor(getColor(R.color.bg));
        go.setBackground(rounded(getColor(R.color.accent), 23));
        go.setOnClickListener(v -> connect(address.getText().toString()));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, dp(46), 1.3f);
        gl.setMarginStart(dp(10));
        btns.addView(go, gl);
        idleBox.addView(btns, lp(MATCH, WRAP, 10, 0));
        idleBox.addView(text(getString(R.string.listen_note), 12, R.color.muted, false), lp(MATCH, WRAP, 14, 0));

        // ── listening ──
        liveBox = new LinearLayout(this);
        liveBox.setOrientation(LinearLayout.VERTICAL);
        liveBox.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(liveBox, lp(MATCH, WRAP, 18, 0));

        liveHost = text("", 11, R.color.success, true);
        liveHost.setLetterSpacing(0.1f);
        liveHost.setGravity(Gravity.CENTER);
        liveHost.setBackground(stroked(0x1F00E5A0, 0x6600E5A0, 14));
        liveHost.setPadding(dp(12), dp(6), dp(12), dp(6));
        liveBox.addView(liveHost, new LinearLayout.LayoutParams(WRAP, WRAP));

        // while the host plays nothing: the logo, one yellow line, and stop
        waitArt = new ImageView(this);
        waitArt.setImageResource(R.drawable.nkt_logo);
        waitArt.setScaleType(ImageView.ScaleType.FIT_CENTER);
        waitArt.setBackground(rounded(0xFF0B1222, 18));
        waitArt.setPadding(dp(46), dp(46), dp(46), dp(46));
        waitArt.setClipToOutline(true);
        waitArt.setVisibility(View.GONE);
        LinearLayout.LayoutParams wal = lp(dp(260), 260, 24, 0);
        wal.width = dp(260);
        wal.gravity = Gravity.CENTER_HORIZONTAL;
        liveBox.addView(waitArt, wal);

        libRow = new LinearLayout(this);
        libBtn = outlined(getString(R.string.listen_library_caps), v -> startActivity(new Intent(this, ListenLibraryActivity.class)));
        backLiveBtn = outlined(getString(R.string.listen_back_live_caps), v -> ListenService.backToLive(this));
        libRow.addView(libBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams bll = new LinearLayout.LayoutParams(0, dp(46), 1f);
        bll.setMarginStart(dp(8));
        libRow.addView(backLiveBtn, bll);
        libRow.setVisibility(View.GONE);
        liveBox.addView(libRow, lp(MATCH, WRAP, 14, 0));

        viewPills = new LinearLayout(this);
        String[] vn = {getString(R.string.listen_view_viz), getString(R.string.listen_view_lyr), getString(R.string.listen_view_art)};
        for (int i = 0; i < 3; i++) {
            final int vi = i;
            viewPill[i] = pill(vn[i], () -> { localView = vi; follow = false; update(); });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (i > 0) pl.setMarginStart(dp(6));
            viewPills.addView(viewPill[i], pl);
        }
        liveBox.addView(viewPills, lp(MATCH, WRAP, 14, 0));

        stage = new FrameLayout(this);
        liveBox.addView(stage, lp(MATCH, 280, 12, 0));
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(rounded(0xFF0B1222, 18));
        art.setClipToOutline(true);
        resetArt();
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(dp(260), dp(260));
        al.gravity = Gravity.CENTER;
        stage.addView(art, al);
        viz = new VizView(this);
        viz.setVisibility(View.GONE);
        stage.addView(viz, new FrameLayout.LayoutParams(MATCH, MATCH));
        photoView = new ImageView(this);
        photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoView.setBackground(rounded(0xFF000000, 18));
        photoView.setClipToOutline(true);
        photoView.setVisibility(View.GONE);
        stage.addView(photoView, new FrameLayout.LayoutParams(MATCH, MATCH));
        lyrScroll = new ScrollView(this);
        lyrScroll.setBackground(stroked(0xFF111827, 0x1F00E5FF, 16));
        lyrScroll.setFillViewport(true);
        lyrScroll.setVisibility(View.GONE);
        lyrBox = new LinearLayout(this);
        lyrBox.setOrientation(LinearLayout.VERTICAL);
        lyrBox.setGravity(Gravity.CENTER);
        lyrBox.setPadding(dp(16), dp(16), dp(16), dp(16));
        for (int i = 0; i < 5; i++) {
            lyrTv[i] = text("", 17, R.color.muted, false);
            lyrTv[i].setGravity(Gravity.CENTER);
            lyrBox.addView(lyrTv[i], lp(MATCH, WRAP, i == 0 ? 0 : 14, 0));
        }
        lyrScroll.addView(lyrBox, new ViewGroup.LayoutParams(MATCH, WRAP));
        stage.addView(lyrScroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        videoView = new PlayerView(this);
        videoView.setUseController(false);
        videoView.setBackgroundColor(Color.BLACK);
        videoView.setVisibility(View.GONE);
        liveBox.addView(videoView, lp(MATCH, 220, 18, 0));
        setupVideoGestures();                                   // seek, volume, brightness and 2× on the picture, small view and full screen
        fsBtn = outlined(getString(R.string.listen_full_caps), v -> {         // full screen is only entered with this button
            if (ListenService.has && ListenService.video) enterFullscreen(videoView, true);
            else if (photoBmp != null && photoShownId.equals(ListenService.shownPhoto())) enterFullscreen(photoView, false);
        });
        fsBtn.setVisibility(View.GONE);
        liveBox.addView(fsBtn, lp(MATCH, 44, 10, 0));
        dlBtn = outlined(getString(R.string.listen_download_caps), v -> ListenService.download(this));
        dlBtn.setVisibility(View.GONE);
        liveBox.addView(dlBtn, lp(MATCH, 44, 10, 0));

        liveTitle = text("", 20, R.color.text, true);
        liveTitle.setGravity(Gravity.CENTER);
        liveBox.addView(liveTitle, lp(MATCH, WRAP, 18, 0));
        liveArtist = text("", 14, R.color.muted, false);
        liveArtist.setGravity(Gravity.CENTER);
        liveBox.addView(liveArtist, lp(MATCH, WRAP, 4, 0));

        liveStatus = text("", 11, R.color.success, true);
        liveStatus.setLetterSpacing(0.1f);
        liveStatus.setGravity(Gravity.CENTER);
        liveStatus.setPadding(dp(14), dp(7), dp(14), dp(7));
        liveBox.addView(liveStatus, lp(WRAP, WRAP, 14, 0));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF33405A));
        bar.setPadding(0, dp(11), 0, dp(11));
        bar.setOnTouchListener((v, e) -> {                 // a pick of your own can be dragged; the host's song follows the host
            if (!ListenService.own) {                      // the host's live video can be taken back and returned to the live edge
                if (!(ListenService.has && ListenService.video) || ListenService.durMs <= 0) return false;
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.getParent().requestDisallowInterceptTouchEvent(true);
                if (e.getActionMasked() == MotionEvent.ACTION_UP)
                    ListenService.shiftTo(this, (long) (ListenService.durMs * Math.max(0f, Math.min(1f, e.getX() / Math.max(1, v.getWidth())))));
                return true;
            }
            ExoPlayer pp = ListenService.player;
            long d = pp == null ? 0 : pp.getDuration();
            if (d > 0 && e.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.getParent().requestDisallowInterceptTouchEvent(true);
                pp.seekTo((long) (d * Math.max(0f, Math.min(1f, e.getX() / Math.max(1, v.getWidth())))));
            }
            return true;
        });
        liveBox.addView(bar, lp(MATCH, 28, 7, 0));
        LinearLayout times = timesRow = new LinearLayout(this);
        tCur = text("0:00", 12, R.color.muted, false);
        tDur = text("0:00", 12, R.color.muted, false);
        times.addView(tCur, new LinearLayout.LayoutParams(0, WRAP, 1f));
        times.addView(tDur);
        liveBox.addView(times, lp(MATCH, WRAP, 0, 0));

        // what you picked from the library: previous, back 10 s, play/pause, forward 10 s, next
        ownRow = new LinearLayout(this);
        String[] ownNames = {getString(R.string.listen_prev_caps), "−10", getString(R.string.listen_play_caps), "+10", getString(R.string.listen_next_caps)};
        for (int i = 0; i < 5; i++) {
            final int k = i;
            ownBtn[i] = (TextView) outlined(ownNames[i], x -> ownTap(k));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(46), 1f);
            if (i > 0) bl.setMarginStart(dp(6));
            ownRow.addView(ownBtn[i], bl);
        }
        ownRow.setVisibility(View.GONE);
        liveBox.addView(ownRow, lp(MATCH, WRAP, 14, 0));
        // the host's live video: back 10 s, play/pause, forward 10 s, and GO LIVE
        liveRow = new LinearLayout(this);
        String[] liveNames = {"−10", getString(R.string.listen_pause_caps), "+10", getString(R.string.listen_go_live_caps)};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            liveBtn[i] = (TextView) outlined(liveNames[i], x -> liveTap(k));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(46), i == 3 ? 1.5f : 1f);
            if (i > 0) bl.setMarginStart(dp(6));
            liveRow.addView(liveBtn[i], bl);
        }
        liveRow.setVisibility(View.GONE);
        liveBox.addView(liveRow, lp(MATCH, WRAP, 14, 0));
        autoRow = new LinearLayout(this);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout at = new LinearLayout(this);
        at.setOrientation(LinearLayout.VERTICAL);
        at.addView(text(getString(R.string.listen_autonext), 14, R.color.text, false));
        at.addView(text(getString(R.string.listen_autonext_sub), 12, R.color.muted, false), lp(MATCH, WRAP, 2, 0));
        autoRow.addView(at, new LinearLayout.LayoutParams(0, WRAP, 1f));
        autoSw = new Switch(this);
        autoSw.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        autoSw.setChecked(ListenService.autoNext(this));
        autoSw.setOnCheckedChangeListener((b, on2) -> { if (ListenService.ownKind == 2) ListenService.setAutoNextPhoto(this, on2); else ListenService.setAutoNext(this, on2); });
        autoRow.addView(autoSw);
        autoRow.setVisibility(View.GONE);
        liveBox.addView(autoRow, lp(MATCH, WRAP, 14, 0));

        LinearLayout ctl = ctlGroup = group();
        liveBox.addView(ctl, lp(MATCH, WRAP, 18, 0));
        volT = text("", 14, R.color.accent, true);
        ctl.addView(stepRow(getString(R.string.listen_volume), volT, () -> { stepVolume(-0.1f); }, () -> { stepVolume(0.1f); }));
        nudT = text("", 14, R.color.accent, true);
        ctl.addView(stepRow(getString(R.string.listen_nudge), nudT, () -> stepNudge(-50), () -> stepNudge(50)));
        nudgeHint = text(getString(R.string.listen_nudge_hint), 12, R.color.muted, false);
        liveBox.addView(nudgeHint, lp(MATCH, WRAP, 8, 0));

        followRow = new LinearLayout(this);
        followRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ft = new LinearLayout(this);
        ft.setOrientation(LinearLayout.VERTICAL);
        ft.addView(text(getString(R.string.listen_follow), 14, R.color.text, false));
        ft.addView(text(getString(R.string.listen_follow_sub), 12, R.color.muted, false), lp(MATCH, WRAP, 2, 0));
        followRow.addView(ft, new LinearLayout.LayoutParams(0, WRAP, 1f));
        followSw = new Switch(this);
        followSw.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        followSw.setChecked(true);
        followSw.setOnCheckedChangeListener((b, on) -> { follow = on; update(); });
        followRow.addView(followSw);
        liveBox.addView(followRow, lp(MATCH, WRAP, 14, 0));

        TextView stop = new TextView(this);
        stop.setText(R.string.listen_stop_caps);
        stop.setGravity(Gravity.CENTER);
        stop.setTextSize(13);
        stop.setLetterSpacing(0.14f);
        stop.setTypeface(null, android.graphics.Typeface.BOLD);
        stop.setTextColor(0xFFFF4F6A);
        stop.setBackground(stroked(0x14FF4F6A, 0x80FF4F6A, 27));
        stop.setOnClickListener(v -> ListenService.stop(this));
        liveBox.addView(stop, lp(MATCH, 54, 20, 0));
    }

    // ── Gestures on the video (the small view and full screen), the same as the video player ──
    // slide sideways = seek (the live video: go back / forward to it), up/down on the left = brightness, on the right = volume,
    // double-tap left/right = −/+10 s, press and hold = 2× while held

    private int vMode = 0;                          // 0 undecided, 1 brightness, 2 volume, 3 seek, 4 nothing
    private boolean vHolding = false;
    private long vSeekStart = 0, vSeekTarget = -1;
    private float vVolAcc = 0;
    private TextView vFlashT;
    private final Runnable vFlashHide = () -> { if (vFlashT != null) vFlashT.setVisibility(View.GONE); };

    private void vFlash(String t, long ms) {
        if (vFlashT == null) return;
        vFlashT.setText(t);
        vFlashT.setVisibility(View.VISIBLE);
        ui.removeCallbacks(vFlashHide);
        if (ms > 0) ui.postDelayed(vFlashHide, ms);
    }

    private long vPos() { ExoPlayer p = ListenService.player; return p == null ? 0 : Math.max(0, p.getCurrentPosition()); }

    private long vDur() {
        if (ListenService.own) { ExoPlayer p = ListenService.player; return p == null ? 0 : Math.max(0, p.getDuration()); }
        return ListenService.durMs;
    }

    private boolean vCanSeek() { return vDur() > 0 && (ListenService.own || (ListenService.has && ListenService.video)); }

    private void vSeekTo(long t) {
        if (ListenService.own) { ExoPlayer p = ListenService.player; if (p != null) p.seekTo(Math.max(0, t)); }
        else ListenService.shiftTo(this, Math.max(0, t));
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setupVideoGestures() {
        final float density = getResources().getDisplayMetrics().density;
        vFlashT = text("", 16, R.color.accent, true);
        vFlashT.setBackgroundColor(0xAA000000);
        vFlashT.setPadding(dp(14), dp(8), dp(14), dp(8));
        vFlashT.setGravity(Gravity.CENTER);
        vFlashT.setVisibility(View.GONE);
        FrameLayout ov = videoView.getOverlayFrameLayout();
        if (ov != null) ov.addView(vFlashT, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        final android.view.GestureDetector gd = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { vMode = 0; vVolAcc = 0; vSeekTarget = -1; return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (fsView != null) showFsBar();               // in the small view a tap does nothing: full screen is only entered with the button
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (!vCanSeek()) return true;
                boolean fwd = e.getX() > videoView.getWidth() / 2f;
                if (ListenService.own) {
                    long d = vDur(), t = vPos() + (fwd ? 10_000 : -10_000);
                    vSeekTo(d > 0 ? Math.min(t, d) : t);
                } else {
                    if (fwd && !ListenService.timeshift) return true;       // nothing ahead of the live moment
                    ListenService.shiftBy(ListenActivity.this, fwd ? 10_000 : -10_000);
                }
                vFlash(getString(fwd ? R.string.fwd_10 : R.string.back_10), 800);
                return true;
            }

            @Override public void onLongPress(MotionEvent e) { if (vMode == 0) startVHold(); }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null || vHolding) return false;
                if (vMode == 0) {
                    if (Math.abs(e2.getY() - e1.getY()) > Math.abs(e2.getX() - e1.getX())) vMode = e1.getX() < videoView.getWidth() / 2f ? 1 : 2;
                    else if (vCanSeek()) { vMode = 3; vSeekStart = vPos(); }
                    else vMode = 4;
                }
                if (vMode == 3) {                              // fine seeking: about 1 second for every 12 dp
                    long delta = Math.round((e2.getX() - e1.getX()) / density / 12f) * 1000L;
                    long d = vDur();
                    long target = Math.max(0, Math.min(d, vSeekStart + delta));
                    vSeekTarget = target;
                    vFlash((delta >= 0 ? "+" : "−") + Ui.time(Math.abs(delta)) + " · " + Ui.time(target), 0);
                    return true;
                }
                float delta = dy / Math.max(1, videoView.getHeight());      // finger up = positive
                if (vMode == 1) vChangeBrightness(delta * 1.4f);
                else if (vMode == 2) vChangeVolume(delta * 1.4f);
                return true;
            }
        });
        videoView.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN && v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
            if (vHolding) {
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) endVHold();
                return true;
            }
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && vMode == 3) {
                if (a == MotionEvent.ACTION_UP && vSeekTarget >= 0) vSeekTo(vSeekTarget);
                vSeekTarget = -1;
                ui.postDelayed(vFlashHide, 600);
            }
            gd.onTouchEvent(ev);
            return true;
        });
    }

    private void startVHold() {
        if (!ListenService.holdFast(true)) {
            if (!ListenService.own) vFlash(getString(R.string.listen_already_live), 900);
            return;
        }
        vHolding = true;
        vFlash(getString(R.string.hold_2x), 0);
        videoView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }

    private void endVHold() {
        vHolding = false;
        ListenService.holdFast(false);
        ui.removeCallbacks(vFlashHide);
        ui.post(vFlashHide);
    }

    private void vChangeBrightness(float delta) {
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        float b = lp.screenBrightness < 0 ? 0.5f : lp.screenBrightness;
        b = Math.max(0.02f, Math.min(1f, b + delta));
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);
        vFlash(getString(R.string.brightness_pct, Math.round(b * 100)), 900);
    }

    private void vChangeVolume(float delta) {
        vVolAcc += delta;
        if (Math.abs(vVolAcc) >= 0.01f) {
            ListenService.setVolume(this, ListenService.volume(this) + vVolAcc);
            vVolAcc = 0;
            update();
        }
        vFlash(getString(R.string.volume_pct, Math.round(ListenService.volume(this) * 100)), 900);
    }

    // ── Full screen: the video (landscape, with volume and sync) or the host's photo ──

    private void enterFullscreen(View v, boolean video) {
        if (fsView != null || v == null || !(v.getParent() instanceof ViewGroup)) return;
        fsView = v;
        fsVideo = video;
        fsParent = (ViewGroup) v.getParent();
        fsIndex = fsParent.indexOfChild(v);
        fsLp = v.getLayoutParams();
        fsParent.removeView(v);
        fsRoot = new FrameLayout(this);
        fsRoot.setBackgroundColor(Color.BLACK);
        fsRoot.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));
        fsBar = new LinearLayout(this);
        fsBar.setOrientation(LinearLayout.VERTICAL);
        fsBar.setBackgroundColor(0x99000000);
        fsBar.setPadding(dp(12), dp(10), dp(12), dp(10));
        final boolean mine = ListenService.own;
        fsSeek = null;
        fsPlay = null;
        final boolean liveVid = !mine && video && ListenService.has;      // the host's live video: can be taken back, with GO LIVE
        fsFwd = null;
        fsGo = null;
        if ((mine || liveVid) && video) {                       // your own video, or the live one, can be dragged to any point
            fsSeek = new android.widget.SeekBar(this);
            fsSeek.setMax(1000);
            fsSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
            fsSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
            fsSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int v2, boolean user) {
                    if (!ListenService.own) return;
                    ExoPlayer pp = ListenService.player;
                    long d = pp == null ? 0 : pp.getDuration();
                    if (user && d > 0) pp.seekTo(d * v2 / 1000);
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { fsSeeking = true; ui.removeCallbacks(fsHide); }
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                    if (!ListenService.own && ListenService.durMs > 0) ListenService.shiftTo(ListenActivity.this, ListenService.durMs * sb.getProgress() / 1000);
                    fsSeeking = false;
                    showFsBar();
                }
            });
            fsBar.addView(fsSeek, new LinearLayout.LayoutParams(MATCH, dp(36)));
        }
        LinearLayout fsRow = new LinearLayout(this);
        fsRow.setGravity(Gravity.CENTER);
        fsBar.addView(fsRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        fsRow.addView(outlined(getString(R.string.listen_exit_full_caps), x -> exitFullscreen()), new LinearLayout.LayoutParams(0, dp(44), 1.3f));
        if (mine) {                                             // previous, back 10 s, play/pause, forward 10 s, next
            int[] ks = video ? new int[]{0, 1, 2, 3, 4} : new int[]{0, 4};
            for (int k : ks) {
                final int kk = k;
                String[] nm = {getString(R.string.listen_prev_caps), "−10", getString(R.string.listen_play_caps), "+10", getString(R.string.listen_next_caps)};
                TextView t = (TextView) outlined(nm[k], x -> { ownTap(kk); showFsBar(); });
                if (k == 2) fsPlay = t;
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, dp(44), 1f);
                l.setMarginStart(dp(8));
                fsRow.addView(t, l);
            }
        }
        if (liveVid) {                                          // back 10 s, play/pause, forward 10 s, go live
            String[] nm = {"−10", getString(R.string.listen_pause_caps), "+10", getString(R.string.listen_go_live_caps)};
            for (int k = 0; k < 4; k++) {
                final int kk = k;
                TextView t = (TextView) outlined(nm[k], x -> { liveTap(kk); showFsBar(); });
                if (k == 1) fsPlay = t;
                if (k == 2) fsFwd = t;
                if (k == 3) fsGo = t;
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, dp(44), 1f);
                l.setMarginStart(dp(8));
                fsRow.addView(t, l);
            }
        }
        if (video) {
            String[] names = mine ? new String[]{"VOL −", "VOL +"} : new String[]{"VOL −", "VOL +", "SYNC −", "SYNC +"};
            for (int i = 0; i < names.length; i++) {
                final int k = i;
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, dp(44), 1f);
                l.setMarginStart(dp(8));
                fsRow.addView(outlined(names[i], x -> {
                    if (k == 0) stepVolume(-0.1f); else if (k == 1) stepVolume(0.1f); else if (k == 2) stepNudge(-50); else stepNudge(50);
                    showFsBar();
                }), l);
            }
        }
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(MATCH, WRAP);
        bp.gravity = Gravity.BOTTOM;
        fsRoot.addView(fsBar, bp);
        v.setOnClickListener(x -> showFsBar());
        addContentView(fsRoot, new ViewGroup.LayoutParams(MATCH, MATCH));
        if (video) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        immersive(true);
        showFsBar();
    }

    private void exitFullscreen() {
        View v = fsView;
        if (v == null) return;
        ui.removeCallbacks(fsHide);
        fsView = null;
        fsRoot.removeView(v);
        if (fsRoot.getParent() instanceof ViewGroup) ((ViewGroup) fsRoot.getParent()).removeView(fsRoot);
        fsParent.addView(v, fsIndex, fsLp);
        final boolean video = fsVideo;
        v.setOnClickListener(null);
        v.setClickable(false);
        fsRoot = null;
        fsBar = null;
        fsSeek = null;
        fsPlay = null;
        fsFwd = null;
        fsGo = null;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        immersive(false);
    }

    /** Previous, back 10 s, play/pause, forward 10 s, next: for what you picked from the library. */
    private void ownTap(int k) {
        if (!ListenService.own) return;
        ExoPlayer p = ListenService.player;
        if (k == 0) { ListenService.step(this, -1); return; }
        if (k == 4) { ListenService.step(this, 1); return; }
        if (p == null || ListenService.ownKind == 2) return;
        long d = p.getDuration();
        if (k == 1) p.seekTo(Math.max(0, p.getCurrentPosition() - 10_000));
        else if (k == 3) p.seekTo(d > 0 ? Math.min(Math.max(0, d - 300), p.getCurrentPosition() + 10_000) : p.getCurrentPosition() + 10_000);
        else if (k == 2) {
            if (p.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED) { p.seekTo(0); p.setPlayWhenReady(true); }
            else p.setPlayWhenReady(!p.getPlayWhenReady());
        }
        update();
    }

    /** Back 10 s, play/pause, forward 10 s, go live: for the host's live video. */
    private void liveTap(int k) {
        if (ListenService.own || !ListenService.has || !ListenService.video) return;
        if (k == 0) ListenService.shiftBy(this, -10_000);
        else if (k == 1) ListenService.shiftToggle(this);
        else if (k == 2) { if (ListenService.timeshift) ListenService.shiftBy(this, 10_000); }
        else ListenService.goLive(this);
        ui.postDelayed(this::update, 150);
    }

    private void showFsBar() {
        if (fsBar == null) return;
        fsBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(fsHide);
        ui.postDelayed(fsHide, 3500);
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (on) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else c.show(WindowInsets.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(on
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (fsView != null) { exitFullscreen(); return; }
        super.onBackPressed();
    }

    // ── The photo the host is showing ──

    private void loadPhoto(final String pid) {
        if (pid.equals(photoShownId) || pid.equals(photoAsking)) return;
        photoAsking = pid;
        new Thread(() -> {
            byte[] b = ListenService.fetchBytes("/ph/" + pid, 20000);
            final Bitmap bmp = b == null ? null : android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length);
            ui.post(() -> {
                if (!pid.equals(photoAsking)) return;
                photoAsking = "";
                if (bmp != null && pid.equals(ListenService.shownPhoto())) {
                    photoBmp = bmp;
                    photoShownId = pid;
                    photoView.setImageBitmap(bmp);
                }
            });
        }, "nkt-listen-photo").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        finder = new StreamNsd.Finder(this, new StreamNsd.Found() {
            @Override public void found(String key, String label, String url) {
                if (StreamService.running && url.equals(StreamService.address(ListenActivity.this))) return;   // this phone itself
                found.put(key, new String[]{label, url});
                drawFound();
            }
            @Override public void lost(String key) { found.remove(key); drawFound(); }
        });
        finder.start();
        AudioAnalyzer.external = true;         // the visualiser here is fed by the host
        ui.post(tick);
        ui.post(feed);
    }

    @Override
    protected void onPause() {
        if (vHolding) endVHold();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(feed);
        vizPolling = false;
        ListenService.viewTag = "art";
        if (viz != null) viz.setVisibility(View.GONE);
        AudioAnalyzer.external = false;
        if (finder != null) { finder.stop(); finder = null; }
        found.clear();
        exitFullscreen();
        if (videoView != null) videoView.setPlayer(null);     // sound carries on without the picture
        attached = null;
        super.onPause();
    }

    // ── Finding and connecting ───────────────────────────────────────────────

    private void drawFound() {
        foundRows.removeAllViews();
        for (String[] f : found.values()) {
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackgroundResource(R.drawable.bg_row);
            item.setPadding(dp(12), dp(12), dp(12), dp(12));
            ImageView ic = new ImageView(this);
            ic.setImageResource(R.drawable.ic_phone);
            ic.setColorFilter(getColor(R.color.success));
            item.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.addView(text(f[0], 14, R.color.text, true));
            t.addView(text(f[1], 12, R.color.muted, false), lp(MATCH, WRAP, 2, 0));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tl.setMarginStart(dp(12));
            item.addView(t, tl);
            TextView join = text(getString(R.string.listen_join_caps), 11, R.color.accent, true);
            join.setLetterSpacing(0.14f);
            item.addView(join);
            final String url = f[1];
            item.setOnClickListener(v -> connect(url));
            foundRows.addView(item, lp(MATCH, WRAP, 8, 0));
        }
        searching.setText(found.isEmpty() ? R.string.listen_searching : R.string.listen_more);
    }

    /** "192.168.1.5:8765/abcd", "http://…" or a pasted link, down to scheme://host:port/name. */
    private static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return null;
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        try {
            Uri u = Uri.parse(s);
            if (u.getHost() == null || u.getPathSegments().isEmpty()) return null;
            int port = u.getPort() == -1 ? 8765 : u.getPort();
            return u.getScheme() + "://" + u.getHost() + ":" + port + "/" + u.getPathSegments().get(0);
        } catch (Throwable t) {
            return null;
        }
    }

    private void connect(String raw) {
        if (busy) return;
        final String base = normalize(raw);
        if (base == null) { Ui.toast(this, R.string.listen_bad_address); return; }
        if (StreamService.running && base.equals(StreamService.address(this))) { Ui.toast(this, R.string.listen_this_phone); return; }
        busy = true;
        problem.setVisibility(View.GONE);
        Ui.toast(this, R.string.listen_connecting);
        new Thread(() -> {
            boolean pin = false, ok = false;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/info").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                if (c.getResponseCode() == 200) {
                    pin = new JSONObject(read(c)).optBoolean("pin");
                    ok = true;
                }
                c.disconnect();
            } catch (Throwable ignored) { }
            final boolean needPin = pin, reached = ok;
            ui.post(() -> {
                busy = false;
                if (!reached) { Ui.toast(this, R.string.listen_cant_reach); return; }
                if (needPin) askPin(base); else go(base, "");
            });
        }).start();
    }

    private void askPin(final String base) {
        final EditText pin = field("", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(pin, new LinearLayout.LayoutParams(MATCH, dp(48)));
        AlertDialog d = Dialogs.builder(this)
                .setTitle(R.string.listen_pin_title)
                .setView(box)
                .setPositiveButton(R.string.connect_caps, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String given = pin.getText().toString();
            if (!given.matches("\\d{4}")) { Ui.toast(this, R.string.stream_bad_pin); return; }
            d.dismiss();
            new Thread(() -> {
                String cookie = null;
                int code = 0;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/pin").openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.getOutputStream().write(("pin=" + given).getBytes("UTF-8"));
                    code = c.getResponseCode();
                    String sc = c.getHeaderField("Set-Cookie");
                    if (code == 200 && sc != null) cookie = sc.split(";")[0];
                    c.disconnect();
                } catch (Throwable ignored) { }
                final String ck = cookie;
                final int cd = code;
                ui.post(() -> {
                    if (ck != null) go(base, ck);
                    else if (cd == 429) Ui.toast(this, R.string.listen_wait);
                    else if (cd == 403) Ui.toast(this, R.string.listen_pin_wrong);
                    else Ui.toast(this, R.string.listen_cant_reach);
                });
            }).start();
        }));
        d.show();
    }

    private void go(String base, String cookie) {
        Prefs.sp(this).edit().putString(PREF_LAST, base).apply();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);   // optional; listening starts anyway
        ListenService.start(this, base, cookie);
        ui.postDelayed(this::update, 400);
    }

    private static String read(HttpURLConnection c) throws java.io.IOException {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    // ── The host's beat and pitch ────────────────────────────────────────────

    private void startVizPolling() {
        vizPolling = true;
        final int gen = ++vizGen;
        new Thread(() -> {
            while (vizPolling && gen == vizGen && ListenService.running) {
                String body = ListenService.fetch("/api/viz?after=" + vizAfter, 4000);
                if (body != null) {
                    try {
                        JSONObject j = new JSONObject(body);
                        int song = j.optInt("song", 0);
                        synchronized (samples) {
                            if (song != vizSong) { vizSong = song; samples.clear(); vizAfter = -1; AudioAnalyzer.songNo = song; }
                            JSONArray a = j.optJSONArray("s");
                            for (int i = 0; a != null && i < a.length(); i++) {
                                JSONArray x = a.getJSONArray(i);
                                samples.add(new double[]{x.getLong(0), x.getDouble(1), x.getInt(2), x.getDouble(3)});
                                if (x.getLong(0) > vizAfter) vizAfter = x.getLong(0);
                            }
                            if (samples.size() > 400) samples.subList(0, samples.size() - 400).clear();
                        }
                        vizBpm = j.optInt("bpm", 0);
                        vizAutoSa = j.optInt("autoSa", -1);
                    } catch (Throwable ignored) { }
                }
                try { Thread.sleep(160); } catch (InterruptedException e) { return; }
            }
            if (gen == vizGen) vizPolling = false;
        }, "nkt-listen-viz").start();
    }

    /** Hands the visualiser the sample that belongs to the moment now playing here. */
    private final Runnable feed = new Runnable() {
        @Override public void run() {
            ExoPlayer p = ListenService.player;
            if (vizPolling && p != null && p.isPlaying()) {
                long pos = p.getCurrentPosition() - ListenService.nudgeMs;
                double[] last = null;
                synchronized (samples) {
                    while (!samples.isEmpty() && samples.get(0)[0] <= pos) last = samples.remove(0);
                    if (!samples.isEmpty() && samples.get(0)[0] > pos + 4000) samples.clear();     // a jump: wait for new ones
                }
                if (last != null) {
                    long now = android.os.SystemClock.uptimeMillis();
                    AudioAnalyzer.pitchHz = (float) last[1];
                    int beat = (int) last[2];
                    if (beat != seenBeat) { if (seenBeat != -1) AudioAnalyzer.lastBeatMs = now; seenBeat = beat; }
                    AudioAnalyzer.beatCount = beat;
                    AudioAnalyzer.level = (float) last[3];
                    AudioAnalyzer.lastAudioMs = now;
                    AudioAnalyzer.bpm = vizBpm;
                    AudioAnalyzer.autoSa = ListenService.hostSa >= 0 ? ListenService.hostSa : vizAutoSa;
                }
            }
            ui.postDelayed(this, 30);
        }
    };

    // ── Lyrics ───────────────────────────────────────────────────────────────

    private void setLyrMsg(String msg) {
        for (int i = 0; i < 5; i++) lyrTv[i].setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        lyrTv[2].setText(msg);
        lyrTv[2].setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        lyrTv[2].setTextColor(getColor(R.color.muted));
        lyrShown = -9;
    }

    private void parseLyrics(String forId, String body) {
        lyrId = forId;
        lyrT = new long[0];
        lyrS = new String[0];
        lyrSynced = false;
        lyrInstr = false;
        lyrOffset = 0;
        lyrShown = -9;
        try {
            JSONObject j = new JSONObject(body == null ? "{}" : body);
            lyrSynced = j.optBoolean("synced");
            lyrInstr = j.optBoolean("instrumental");
            lyrOffset = j.optLong("offset", 0);
            JSONArray a = j.optJSONArray("lines");
            if (a != null) {
                lyrT = new long[a.length()];
                lyrS = new String[a.length()];
                for (int i = 0; i < a.length(); i++) {
                    JSONArray x = a.getJSONArray(i);
                    lyrT[i] = x.getLong(0);
                    lyrS[i] = x.getString(1);
                }
            }
        } catch (Throwable ignored) { }
    }

    private void drawLyrics(ExoPlayer p) {
        String id = ListenService.id;
        if (!ListenService.has || id.isEmpty()) { setLyrMsg(getString(R.string.listen_waiting)); return; }
        if (!id.equals(lyrId)) {
            if (!ListenService.hostLyr && !ListenService.drain) { setLyrMsg(getString(R.string.listen_lyrics_looking)); return; }
            if (!id.equals(lyrAsking)) {
                lyrAsking = id;
                final String want = id;
                new Thread(() -> {
                    final String body = ListenService.fetch("/api/lyrics?id=" + want, 15000);
                    ui.post(() -> parseLyrics(want, body));
                }).start();
            }
            setLyrMsg(getString(R.string.listen_lyrics_looking));
            return;
        }
        if (lyrInstr) { setLyrMsg(getString(R.string.listen_lyrics_instr)); return; }
        if (lyrS.length == 0) { setLyrMsg(getString(R.string.listen_lyrics_none)); return; }
        if (!lyrSynced) {
            if (lyrShown == -1) return;
            lyrShown = -1;
            StringBuilder b = new StringBuilder();
            for (String l : lyrS) b.append(l).append('\n');
            for (int i = 0; i < 5; i++) lyrTv[i].setVisibility(i == 2 ? View.VISIBLE : View.GONE);
            lyrTv[2].setText(b.toString().trim());
            lyrTv[2].setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            lyrTv[2].setTextColor(getColor(R.color.muted));
            return;
        }
        long t = (p == null ? 0 : p.getCurrentPosition()) - ListenService.nudgeMs + lyrOffset;
        int at = -1;
        for (int k = 0; k < lyrT.length; k++) { if (lyrT[k] <= t) at = k; else break; }
        if (at == lyrShown) return;
        lyrShown = at;
        for (int i = 0; i < 5; i++) {
            int k = at - 2 + i;
            lyrTv[i].setVisibility(View.VISIBLE);
            lyrTv[i].setText(i == 2 && at < 0 ? "\u266A" : k >= 0 && k < lyrS.length ? (lyrS[k].isEmpty() ? "\u266A" : lyrS[k]) : " ");
            boolean cur = i == 2;
            lyrTv[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, cur ? 24 : i == 1 || i == 3 ? 17 : 16);
            lyrTv[i].setTypeface(null, cur ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            lyrTv[i].setTextColor(cur ? getColor(R.color.accent) : i == 1 || i == 3 ? getColor(R.color.muted) : 0xFF33405A);
        }
    }

    private TextView pill(String label, Runnable tap) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(11);
        t.setLetterSpacing(0.08f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setOnClickListener(v -> tap.run());
        setPill(t, false);
        return t;
    }

    private void setPill(TextView t, boolean on) {
        t.setTextColor(on ? getColor(R.color.accent) : getColor(R.color.muted));
        t.setBackground(on ? stroked(0x1A00E5FF, getColor(R.color.accent), 20) : stroked(0xFF111827, 0x1F00E5FF, 20));
    }

    // ── Screen ───────────────────────────────────────────────────────────────

    private void update() {
        boolean on = ListenService.running;
        idleBox.setVisibility(on ? View.GONE : View.VISIBLE);
        liveBox.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            String note = ListenService.endNote;
            problem.setText(note);
            problem.setVisibility(note == null || note.isEmpty() ? View.GONE : View.VISIBLE);
            return;
        }
        boolean has = ListenService.has;
        liveHost.setText(getString(R.string.listen_from_caps, ListenService.host.toUpperCase()));
        liveHost.setVisibility(ListenService.host.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        liveTitle.setText(has ? ListenService.title : getString(R.string.listen_waiting));
        liveArtist.setText(has ? ListenService.artist : "");
        String st = ListenService.status;
        liveStatus.setText(st);
        boolean good = st.startsWith("IN SYNC");
        liveStatus.setTextColor(good ? getColor(R.color.success) : 0xFFFFC850);
        liveStatus.setBackground(good ? stroked(0x1F00E5A0, 0x6600E5A0, 16) : stroked(0x1AFFC850, 0x66FFC850, 16));

        boolean vid = has && ListenService.video;
        boolean own = ListenService.own;
        boolean ownPhoto = own && ListenService.ownKind == 2;
        boolean photoOn = !ListenService.shownPhoto().isEmpty();
        boolean tabs = !vid && !own && !photoOn;              // the view tabs are for the host's song
        ExoPlayer p = ListenService.player;
        videoView.setVisibility(vid ? View.VISIBLE : View.GONE);
        stage.setVisibility(vid ? View.GONE : View.VISIBLE);
        viewPills.setVisibility(tabs ? View.VISIBLE : View.GONE);
        followRow.setVisibility(tabs ? View.VISIBLE : View.GONE);
        libRow.setVisibility(ListenService.libOn || own ? View.VISIBLE : View.GONE);
        libBtn.setVisibility(ListenService.libOn || own ? View.VISIBLE : View.GONE);
        backLiveBtn.setVisibility(own ? View.VISIBLE : View.GONE);
        if (!own && !has && photoOn) {                          // only a photo is being shown
            liveTitle.setText(ListenService.photoTitle);
            liveArtist.setText(ListenService.photoOf > 1 ? getString(R.string.listen_photo_n, ListenService.photoN, ListenService.photoOf) : "");
        } else if (!has && ListenService.priv) {
            liveTitle.setText(R.string.listen_private);
            liveArtist.setText(R.string.listen_private_sub);
        }
        int v = own ? V_ART : follow ? ListenService.hostView : localView;
        if (v < 0 || v > 2) v = V_ART;
        int style = ListenService.hostStyle;
        if (followSw.isChecked() != follow) followSw.setChecked(follow);
        photoView.setVisibility(photoOn ? View.VISIBLE : View.GONE);
        art.setVisibility(!photoOn && v == V_ART ? View.VISIBLE : View.GONE);
        lyrScroll.setVisibility(!photoOn && v == V_LYR ? View.VISIBLE : View.GONE);
        boolean showViz = !photoOn && v == V_VIZ && !vid && !own;
        viz.setVisibility(showViz ? View.VISIBLE : View.GONE);
        if (viz.getStyle() != style) viz.setStyle(style);
        viz.setSteadySpeed(ListenService.hostSpeed);
        for (int i = 0; i < 3; i++) setPill(viewPill[i], i == v);
        ListenService.viewTag = vid || own || photoOn ? "art" : v == V_VIZ ? "viz" : v == V_LYR ? "lyr" : "art";
        if (showViz && has && !vizPolling) startVizPolling();
        if (!showViz) vizPolling = false;
        if (v == V_LYR && !photoOn && !own) drawLyrics(p);
        if (photoOn) loadPhoto(ListenService.shownPhoto());
        if (fsView != null && ((fsVideo && !vid) || (!fsVideo && !photoOn))) exitFullscreen();
        fsBtn.setVisibility(vid || (photoOn && photoBmp != null && photoShownId.equals(ListenService.shownPhoto())) ? View.VISIBLE : View.GONE);
        if (ownPhoto) liveArtist.setText(ListenService.ownQueue.size() > 1 ? getString(R.string.listen_photo_n, ListenService.ownIdx + 1, ListenService.ownQueue.size()) : "");
        ownRow.setVisibility(own ? View.VISIBLE : View.GONE);
        boolean liveVideo = !own && vid;
        liveRow.setVisibility(liveVideo ? View.VISIBLE : View.GONE);
        boolean shifted = ListenService.timeshift;
        liveBtn[2].setEnabled(shifted); liveBtn[2].setAlpha(shifted ? 1f : 0.35f);
        liveBtn[3].setEnabled(shifted); liveBtn[3].setAlpha(shifted ? 1f : 0.35f);
        if (fsFwd != null) { fsFwd.setEnabled(shifted); fsFwd.setAlpha(shifted ? 1f : 0.35f); }
        if (fsGo != null) { fsGo.setEnabled(shifted); fsGo.setAlpha(shifted ? 1f : 0.35f); }
        autoRow.setVisibility(own ? View.VISIBLE : View.GONE);
        bar.setVisibility(ownPhoto ? View.GONE : View.VISIBLE);
        timesRow.setVisibility(ownPhoto ? View.GONE : View.VISIBLE);
        for (int i = 1; i <= 3; i++) ownBtn[i].setVisibility(ownPhoto ? View.GONE : View.VISIBLE);
        boolean hasPrev = ListenService.ownIdx > 0, hasNext = ListenService.ownIdx + 1 < ListenService.ownQueue.size();
        ownBtn[0].setEnabled(hasPrev); ownBtn[0].setAlpha(hasPrev ? 1f : 0.35f);
        ownBtn[4].setEnabled(hasNext); ownBtn[4].setAlpha(hasNext ? 1f : 0.35f);
        boolean playing = p != null && p.getPlayWhenReady() && p.getPlaybackState() != androidx.media3.common.Player.STATE_ENDED;
        ownBtn[2].setText(getString(playing ? R.string.listen_pause_caps : R.string.listen_play_caps));
        liveBtn[1].setText(getString(playing ? R.string.listen_pause_caps : R.string.listen_play_caps));
        if (fsPlay != null) fsPlay.setText(getString(playing ? R.string.listen_pause_caps : R.string.listen_play_caps));
        boolean autoNow = ListenService.ownKind == 2 ? ListenService.autoNextPhoto(this) : ListenService.autoNext(this);     // photos have a switch of their own, off at first
        if (autoSw.isChecked() != autoNow) autoSw.setChecked(autoNow);
        if (ListenService.libOffAt != seenLibOff) {                     // the host switched the library off
            seenLibOff = ListenService.libOffAt;
            android.widget.Toast.makeText(this, R.string.listen_lib_off_note, android.widget.Toast.LENGTH_LONG).show();
        }
        if (vid && p != attached) { videoView.setPlayer(p); attached = p; }
        if (!vid && attached != null) { videoView.setPlayer(null); attached = null; }

        Bitmap b = ListenService.art;
        if (b != shownArt) {
            shownArt = b;
            if (b == null) resetArt();
            else { art.setColorFilter(null); art.setPadding(0, 0, 0, 0); art.setImageBitmap(b); }
        }

        long pos = p == null ? 0 : Math.max(0, p.getCurrentPosition());
        long dur = ListenService.durMs > 0 ? ListenService.durMs : (p == null ? 0 : Math.max(0, p.getDuration()));
        bar.setProgress(dur > 0 ? (int) Math.min(1000, pos * 1000 / dur) : 0);
        if (fsSeek != null && !fsSeeking) fsSeek.setProgress(dur > 0 ? (int) Math.min(1000, pos * 1000 / dur) : 0);
        tCur.setText(Ui.time(pos));
        tDur.setText(Ui.time(dur));
        volT.setText(Math.round(ListenService.volume(this) * 100) + "%");
        int n = ListenService.nudgeMs;
        nudT.setText((n > 0 ? "+" : "") + n + " ms");

        // nothing is playing: the same plain screen as the browser (logo, one yellow line, stop)
        boolean waiting = !has && !own && !photoOn;
        dlBtn.setVisibility(!waiting && ListenService.dlOn && !ListenService.downloadId().isEmpty() ? View.VISIBLE : View.GONE);
        View[] normal = {libRow, viewPills, stage, videoView, fsBtn, liveTitle, liveArtist, bar, timesRow, ownRow, liveRow, autoRow, ctlGroup, nudgeHint, followRow};
        for (View nv : normal) if (waiting) nv.setVisibility(View.GONE);
        if (!waiting) { ctlGroup.setVisibility(View.VISIBLE); nudgeHint.setVisibility(View.VISIBLE); }
        waitArt.setVisibility(waiting ? View.VISIBLE : View.GONE);
        if (waiting) liveStatus.setText((ListenService.priv ? getString(R.string.listen_private) : ListenService.status).toUpperCase());
        if (waiting && fsView != null) exitFullscreen();
    }

    private void resetArt() {
        art.setImageResource(R.drawable.ic_music);
        art.setColorFilter(getColor(R.color.accent));
        art.setPadding(dp(60), dp(60), dp(60), dp(60));
        shownArt = null;
    }

    private void stepVolume(float d) {
        ListenService.setVolume(this, ListenService.volume(this) + d);
        update();
    }

    private void stepNudge(int d) {
        ListenService.setNudge(this, ListenService.nudgeMs + d);
        update();
    }

    // ── Small view helpers (the same look as the Stream screen) ──────────────

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private LinearLayout.LayoutParams lp(int w, int h, int topDp, int startDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h > 0 ? dp(h) : h);
        p.topMargin = dp(topDp);
        p.setMarginStart(dp(startDp));
        return p;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 10, R.color.muted, false);
        t.setLetterSpacing(0.18f);
        return t;
    }

    private View outlined(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12);
        t.setLetterSpacing(0.14f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(getColor(R.color.accent));
        t.setBackground(stroked(Color.TRANSPARENT, getColor(R.color.accent), 23));
        t.setOnClickListener(l);
        return t;
    }

    private android.graphics.drawable.GradientDrawable rounded(int fill, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private android.graphics.drawable.GradientDrawable stroked(int fill, int stroke, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = rounded(fill, radiusDp);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private EditText field(String value, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(getColor(R.color.text));
        e.setTextSize(15);
        e.setBackground(stroked(0xFF0B1222, 0x1F00E5FF, 10));
        e.setPadding(dp(12), 0, dp(12), 0);
        return e;
    }

    private LinearLayout group() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackgroundResource(R.drawable.bg_options_bar);
        g.setPadding(dp(6), dp(6), dp(6), dp(6));
        return g;
    }

    private View stepRow(String name, TextView value, Runnable minus, Runnable plus) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(60));
        r.setPadding(dp(10), dp(6), dp(10), dp(6));
        r.addView(text(name, 14, R.color.text, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
        r.addView(stepButton("−", name, minus), new LinearLayout.LayoutParams(dp(44), dp(44)));
        value.setGravity(Gravity.CENTER);
        r.addView(value, new LinearLayout.LayoutParams(dp(72), WRAP));
        r.addView(stepButton("+", name, plus), new LinearLayout.LayoutParams(dp(44), dp(44)));
        return r;
    }

    private TextView stepButton(String sym, String what, Runnable tap) {
        TextView b = new TextView(this);
        b.setText(sym);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(22);
        b.setTextColor(getColor(R.color.accent));
        b.setBackground(stroked(0x1400E5FF, 0x4000E5FF, 22));
        b.setContentDescription(what + " " + sym);
        b.setOnClickListener(v -> tap.run());
        return b;
    }
}
