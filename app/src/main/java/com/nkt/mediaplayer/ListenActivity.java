package com.nkt.mediaplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
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
import android.view.View;
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
    private LinearLayout lyrBox, viewPills, stylePills, followRow;
    private final TextView[] lyrTv = new TextView[5];
    private TextView bpmChip;
    private Switch followSw;
    private final TextView[] viewPill = new TextView[3], stylePill = new TextView[3];
    private boolean follow = true;
    private int localView = V_ART, localStyle = 0;

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
        bpmChip = text("", 11, R.color.accent, true);
        bpmChip.setLetterSpacing(0.1f);
        bpmChip.setBackground(stroked(0xB305080F, 0x4000E5FF, 12));
        bpmChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        bpmChip.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(WRAP, WRAP);
        bl.gravity = Gravity.TOP | Gravity.END;
        bl.topMargin = dp(8);
        bl.setMarginEnd(dp(10));
        stage.addView(bpmChip, bl);
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
        liveBox.addView(bar, lp(MATCH, 6, 18, 0));
        LinearLayout times = new LinearLayout(this);
        tCur = text("0:00", 12, R.color.muted, false);
        tDur = text("0:00", 12, R.color.muted, false);
        times.addView(tCur, new LinearLayout.LayoutParams(0, WRAP, 1f));
        times.addView(tDur);
        liveBox.addView(times, lp(MATCH, WRAP, 6, 0));

        LinearLayout ctl = group();
        liveBox.addView(ctl, lp(MATCH, WRAP, 18, 0));
        volT = text("", 14, R.color.accent, true);
        ctl.addView(stepRow(getString(R.string.listen_volume), volT, () -> { stepVolume(-0.1f); }, () -> { stepVolume(0.1f); }));
        nudT = text("", 14, R.color.accent, true);
        ctl.addView(stepRow(getString(R.string.listen_nudge), nudT, () -> stepNudge(-50), () -> stepNudge(50)));
        liveBox.addView(text(getString(R.string.listen_nudge_hint), 12, R.color.muted, false), lp(MATCH, WRAP, 8, 0));

        stylePills = new LinearLayout(this);
        String[] sn = {getString(R.string.listen_style_wind), getString(R.string.listen_style_steady), getString(R.string.listen_style_gamaka)};
        final int[] sv = {VizView.WINDSCREEN, VizView.STEADY, VizView.GAMAKA};
        for (int i = 0; i < 3; i++) {
            final int style = sv[i];
            stylePill[i] = pill(sn[i], () -> { localStyle = style; follow = false; update(); });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (i > 0) pl.setMarginStart(dp(6));
            stylePills.addView(stylePill[i], pl);
        }
        liveBox.addView(stylePills, lp(MATCH, WRAP, 14, 0));

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
        ui.removeCallbacks(tick);
        ui.removeCallbacks(feed);
        vizPolling = false;
        ListenService.viewTag = "art";
        if (viz != null) viz.setVisibility(View.GONE);
        AudioAnalyzer.external = false;
        if (finder != null) { finder.stop(); finder = null; }
        found.clear();
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
        ExoPlayer p = ListenService.player;
        videoView.setVisibility(vid ? View.VISIBLE : View.GONE);
        stage.setVisibility(vid ? View.GONE : View.VISIBLE);
        viewPills.setVisibility(vid ? View.GONE : View.VISIBLE);
        followRow.setVisibility(vid ? View.GONE : View.VISIBLE);
        int v = follow ? ListenService.hostView : localView;
        if (v < 0 || v > 2) v = V_ART;
        int style = follow ? ListenService.hostStyle : localStyle;
        if (followSw.isChecked() != follow) followSw.setChecked(follow);
        art.setVisibility(v == V_ART ? View.VISIBLE : View.GONE);
        lyrScroll.setVisibility(v == V_LYR ? View.VISIBLE : View.GONE);
        boolean showViz = v == V_VIZ && !vid;
        viz.setVisibility(showViz ? View.VISIBLE : View.GONE);
        bpmChip.setVisibility(showViz ? View.VISIBLE : View.GONE);
        stylePills.setVisibility(showViz ? View.VISIBLE : View.GONE);
        if (viz.getStyle() != style) viz.setStyle(style);
        viz.setSteadySpeed(ListenService.hostSpeed);
        for (int i = 0; i < 3; i++) setPill(viewPill[i], i == v);
        setPill(stylePill[0], style == VizView.WINDSCREEN);
        setPill(stylePill[1], style == VizView.STEADY);
        setPill(stylePill[2], style == VizView.GAMAKA);
        ListenService.viewTag = vid ? "art" : v == V_VIZ ? "viz" : v == V_LYR ? "lyr" : "art";
        bpmChip.setText(vizBpm > 0 ? getString(R.string.listen_bpm, vizBpm) : getString(R.string.listen_bpm_none));
        if (showViz && has && !vizPolling) startVizPolling();
        if (!showViz) vizPolling = false;
        if (v == V_LYR) drawLyrics(p);
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
        tCur.setText(Ui.time(pos));
        tDur.setText(Ui.time(dur));
        volT.setText(Math.round(ListenService.volume(this) * 100) + "%");
        int n = ListenService.nudgeMs;
        nudT.setText((n > 0 ? "+" : "") + n + " ms");
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
