package com.nkt.mediaplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Locale;

/**
 * Listen: plays another phone's Stream inside this app, and keeps playing with the screen off. It follows the host
 * the same way the browser page does: the host's play, pause, next and seek are mirrored, and the sound is kept in
 * step (small drift by speeding up or slowing down a little, large drift by jumping).
 *
 * The state of the host is fetched on a background thread; everything that touches the player runs on the main thread.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class ListenService extends Service {

    public static final String ACTION_STOP = "com.nkt.mediaplayer.STOP_LISTEN";
    public static final String ACTION_OWN = "com.nkt.mediaplayer.OWN_LISTEN";
    public static final String ACTION_LIVE = "com.nkt.mediaplayer.LIVE_LISTEN";
    public static final String ACTION_STEP = "com.nkt.mediaplayer.STEP_LISTEN";
    public static final String ACTION_SHIFT = "com.nkt.mediaplayer.SHIFT_LISTEN";
    public static final String EXTRA_BASE = "base";
    public static final String EXTRA_COOKIE = "cookie";
    private static final String CHANNEL = "nkt_listen";
    private static final int NOTIF_ID = 44;
    private static final long GRACE_MS = 15_000;     // a Wi-Fi hiccup is waited out this long before giving up

    // What the Listen screen shows
    public static volatile boolean running = false;
    public static volatile ExoPlayer player;
    public static volatile String status = "", title = "", artist = "", host = "", id = "", endNote = "";
    public static volatile boolean video, has;
    public static volatile long durMs;
    public static volatile Bitmap art;
    public static volatile int nudgeMs;

    /** A song or video picked from the host's library: played on this phone only, not in step with the host. */
    public static volatile boolean own;
    /** Gone back in the host's live video: not kept in step until GO LIVE. */
    public static volatile boolean timeshift;
    public static volatile String ownId = "";
    /** The host is playing something it keeps to itself; the host's library is on; the photo the host is showing. */
    public static volatile boolean priv, libOn;
    public static volatile String photoId = "", photoTitle = "";
    public static volatile int photoN, photoOf;

    /** The host allows downloads (only then the DOWNLOAD button shows). */
    public static volatile boolean dlOn;

    /** The id of what is on this screen: your own pick, the host's song or video, or the host's photo. */
    public static String downloadId() { return own ? ownId : (has && !id.isEmpty() ? id : photoId); }

    /** Saves what is on screen to this phone's Downloads (in the NKT folder), in the background. */
    public static void download(android.content.Context c) { download(c, downloadId()); }

    /** Saves the item with this id (a library item or what is on screen). */
    public static void download(android.content.Context c, final String did) {
        if (did == null || did.isEmpty() || baseUrl.isEmpty()) return;
        final android.content.Context app = c.getApplicationContext();
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> android.widget.Toast.makeText(app, R.string.listen_downloading, android.widget.Toast.LENGTH_SHORT).show());
        new Thread(() -> {
            String msg;
            try {
                HttpURLConnection c2 = (HttpURLConnection) new URL(baseUrl + "/dl/" + did + "?c=" + clientId).openConnection();
                c2.setConnectTimeout(5000);
                c2.setReadTimeout(30000);
                c2.setUseCaches(false);
                c2.setRequestProperty("User-Agent", "NKT Listen (Android) " + Build.MODEL);
                if (!cookieStr.isEmpty()) c2.setRequestProperty("Cookie", cookieStr);
                if (c2.getResponseCode() != 200) { c2.disconnect(); throw new IOException("refused"); }
                String name = "download";
                String cd = c2.getHeaderField("Content-Disposition");
                if (cd != null) {
                    int k = cd.indexOf("filename*=UTF-8''");
                    if (k >= 0) { try { name = java.net.URLDecoder.decode(cd.substring(k + 17).split(";")[0].trim().replace("+", "%2B"), "UTF-8"); } catch (Throwable ignored) { } }
                    else { int q = cd.indexOf("filename=\""); if (q >= 0) { int e = cd.indexOf('"', q + 10); if (e > q) name = cd.substring(q + 10, e); } }
                }
                name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                String mime = c2.getContentType() == null ? "application/octet-stream" : c2.getContentType().split(";")[0];
                java.io.OutputStream os;
                android.net.Uri pending = null;
                java.io.File legacy = null;
                if (Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/NKT Media Player");
                    cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                    pending = app.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (pending == null) throw new IOException("no place to save");
                    os = app.getContentResolver().openOutputStream(pending);
                } else {
                    java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "NKT Media Player");
                    if (!dir.isDirectory() && !dir.mkdirs() || !dir.canWrite()) dir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                    legacy = new java.io.File(dir, name);
                    os = new java.io.FileOutputStream(legacy);
                }
                try (InputStream in = c2.getInputStream(); java.io.OutputStream o2 = os) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) o2.write(buf, 0, n);
                }
                c2.disconnect();
                if (pending != null) {
                    android.content.ContentValues done = new android.content.ContentValues();
                    done.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                    app.getContentResolver().update(pending, done, null, null);
                }
                msg = app.getString(R.string.listen_saved, name);
            } catch (Throwable t) {
                msg = app.getString(R.string.listen_dl_failed);
            }
            final String shown = msg;
            h.post(() -> android.widget.Toast.makeText(app, shown, android.widget.Toast.LENGTH_LONG).show());
        }, "nkt-listen-dl").start();
    }

    /** Copies the item into this app's cache (for sharing with other apps). Background threads only; null when it could not be got. */
    public static java.io.File fetchToCache(android.content.Context c, String did, java.io.File dir) {
        try {
            if (did == null || did.isEmpty() || baseUrl.isEmpty()) return null;
            HttpURLConnection c2 = (HttpURLConnection) new URL(baseUrl + "/dl/" + did + "?c=" + clientId).openConnection();
            c2.setConnectTimeout(5000);
            c2.setReadTimeout(30000);
            c2.setUseCaches(false);
            c2.setRequestProperty("User-Agent", "NKT Listen (Android) " + Build.MODEL);
            if (!cookieStr.isEmpty()) c2.setRequestProperty("Cookie", cookieStr);
            if (c2.getResponseCode() != 200) { c2.disconnect(); return null; }
            String name = "file";
            String cd = c2.getHeaderField("Content-Disposition");
            if (cd != null) {
                int k = cd.indexOf("filename*=UTF-8''");
                if (k >= 0) { try { name = java.net.URLDecoder.decode(cd.substring(k + 17).split(";")[0].trim().replace("+", "%2B"), "UTF-8"); } catch (Throwable ignored) { } }
                else { int q = cd.indexOf("filename=\""); if (q >= 0) { int e = cd.indexOf('"', q + 10); if (e > q) name = cd.substring(q + 10, e); } }
            }
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (!dir.isDirectory() && !dir.mkdirs()) { c2.disconnect(); return null; }
            java.io.File f = new java.io.File(dir, name);
            for (int i = 1; f.exists(); i++) f = new java.io.File(dir, i + "_" + name);
            try (InputStream in = c2.getInputStream(); java.io.OutputStream o2 = new java.io.FileOutputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o2.write(buf, 0, n);
            }
            c2.disconnect();
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** What you picked: the list it came from ({id, title, kind: 0 song, 1 video, 2 photo}) and where you are in it. */
    public static volatile java.util.List<String[]> ownQueue = new java.util.ArrayList<>();
    public static volatile int ownIdx = -1, ownKind = 0;
    /** The host set the volume for everyone (0..1), or -1 while each listener sets their own. */
    public static volatile float hostVol = -1f;
    /** Your own level while the host shares theirs: kept until the host moves theirs again, or -1. */
    public static volatile float hostVolLocal = -1f;
    /** Press and hold on the picture: 2× (only your own pick, or the live video after going back to catch up). */
    public static volatile boolean fast;
    /** Moves when the host switches the library off while you were playing a pick of your own. */
    public static volatile long libOffAt = 0;
    /** Changes with every new connection (the library screen forgets its place when it does). */
    public static volatile long startedAt = 0;

    /** Plays one of the host's library items here (not synced). */
    public static void playOwn(android.content.Context c, String id, String title, boolean video) {
        playOwn(c, java.util.Collections.singletonList(new String[]{id, title, video ? "1" : "0"}), 0);
    }

    /** Plays item {@code index} of {@code queue} here (not synced); the others are what previous and next move through. */
    public static void playOwn(android.content.Context c, java.util.List<String[]> queue, int index) {
        ownQueue = new java.util.ArrayList<>(queue);
        c.startService(new Intent(c, ListenService.class).setAction(ACTION_OWN).putExtra("index", index));
    }

    /** Previous (-1) or next (+1) in the list of your own picks. */
    public static void step(android.content.Context c, int delta) {
        c.startService(new Intent(c, ListenService.class).setAction(ACTION_STEP).putExtra("delta", delta));
    }

    public static boolean autoNext(android.content.Context c) { return Prefs.sp(c).getBoolean("listen_autonext", true); }
    /** Photos move on by themselves only when asked: off until switched on. */
    public static boolean autoNextPhoto(android.content.Context c) { return Prefs.sp(c).getBoolean("listen_autonext_photo", false); }
    public static void setAutoNextPhoto(android.content.Context c, boolean on) { Prefs.sp(c).edit().putBoolean("listen_autonext_photo", on).apply(); }
    public static void setAutoNext(android.content.Context c, boolean on) { Prefs.sp(c).edit().putBoolean("listen_autonext", on).apply(); }

    /** The photo on this screen: the one you picked, or the one the host is showing. */
    public static String shownPhoto() { return own ? (ownKind == 2 ? ownId : "") : photoId; }

    /** Live video only: move the picture to an exact time (ms). */
    public static void shiftTo(android.content.Context c, long ms) {
        c.startService(new Intent(c, ListenService.class).setAction(ACTION_SHIFT).putExtra("op", 0).putExtra("ms", ms));
    }
    /** Live video only: go back or forward by some milliseconds. */
    public static void shiftBy(android.content.Context c, long delta) {
        c.startService(new Intent(c, ListenService.class).setAction(ACTION_SHIFT).putExtra("op", 1).putExtra("ms", delta));
    }
    /** Live video only: pause or play your own view of it. */
    public static void shiftToggle(android.content.Context c) {
        c.startService(new Intent(c, ListenService.class).setAction(ACTION_SHIFT).putExtra("op", 2));
    }
    /** Live video only: jump back to what the host is showing right now. */
    public static void goLive(android.content.Context c) {
        c.startService(new Intent(c, ListenService.class).setAction(ACTION_SHIFT).putExtra("op", 3));
    }

    /** Back to following the host. */
    public static void backToLive(android.content.Context c) {
        c.startService(new Intent(c, ListenService.class).setAction(ACTION_LIVE));
    }

    /** GET a picture or other bytes from the host (background threads only), or null. */
    public static byte[] fetchBytes(String path, int readMs) {
        try {
            String b = baseUrl;
            if (b.isEmpty()) return null;
            HttpURLConnection c = (HttpURLConnection) new URL(b + path + (path.contains("?") ? "&" : "?") + "c=" + clientId).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(readMs);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "NKT Listen (Android) " + Build.MODEL);
            if (!cookieStr.isEmpty()) c.setRequestProperty("Cookie", cookieStr);
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > 16 * 1024 * 1024) break; }
            }
            c.disconnect();
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    // The host's own view of the song, for the Listen screen (visualiser, lyrics, art)
    public static volatile int hostView = 2, hostStyle = 0, hostSpeed = 40, hostSa = -1;
    public static volatile boolean hostLyr, drain, drainAll;
    /** What this phone is showing, told to the host: "viz", "lyr" or "art" (the host only analyses sound for "viz"). */
    public static volatile String viewTag = "art";
    private static volatile String baseUrl = "", cookieStr = "", clientId = "";

    /** GET from the host (background threads only): the body, or null. */
    public static String fetch(String path, int readMs) {
        try {
            String b = baseUrl;
            if (b.isEmpty()) return null;
            HttpURLConnection c = (HttpURLConnection) new URL(b + path + (path.contains("?") ? "&" : "?") + "c=" + clientId).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(readMs);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "NKT Listen (Android) " + Build.MODEL);
            if (!cookieStr.isEmpty()) c.setRequestProperty("Cookie", cookieStr);
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            String s = read(c);
            c.disconnect();
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int session = 0;
    private String base = "", cookie = "", cid = "", ua = "";
    private volatile long offset = 0;           // how far the host's clock is ahead of ours
    private volatile int ver = -1;
    private volatile int syncErr = 0;
    private volatile HttpURLConnection cur;

    // The host's player, as last told
    private boolean stHas, stOk, stPlaying, stVideo;
    private String stId = "", loadedId = "", stTitle = "", stArtist = "";
    private long stDur, stPos, stAt;
    private float stSpeed = 1f;
    private long lastLoadAt = 0;
    private org.json.JSONArray drainQ;                 // the host's queue, once it has stopped
    private boolean localQueue = false, anchorPending = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── Controls used by the screen ──

    public static void setVolume(android.content.Context c, float v) {
        v = Math.max(0f, Math.min(1f, v));
        if (hostVol >= 0f) hostVolLocal = v;       // the host shares theirs: yours holds until the host changes theirs
        else Prefs.sp(c).edit().putFloat("listen_vol", v).apply();
        ExoPlayer p = player;
        if (p != null) p.setVolume(v);
    }

    public static float volume(android.content.Context c) { return hostVol >= 0f ? (hostVolLocal >= 0f ? hostVolLocal : hostVol) : Prefs.sp(c).getFloat("listen_vol", 1f); }

    /** Press and hold = 2×. True when it applies (a pick of your own; the live video once you have gone back), false when there is nothing to speed up. */
    public static boolean holdFast(boolean on) {
        ExoPlayer p = player;
        if (p == null) return false;
        if (!on) {
            fast = false;
            if (own) p.setPlaybackSpeed(1f);
            return true;
        }
        if (own) { if (!p.isPlaying()) return false; p.setPlaybackSpeed(2f); fast = true; return true; }
        if (!timeshift || !p.isPlaying()) return false;
        fast = true;
        p.setPlaybackSpeed(2f);
        return true;
    }

    public static void setNudge(android.content.Context c, int ms) {
        ms = Math.max(-500, Math.min(500, ms));
        Prefs.sp(c).edit().putInt("listen_nudge", ms).apply();
        nudgeMs = ms;
    }

    public static int nudge(android.content.Context c) { return Prefs.sp(c).getInt("listen_nudge", 0); }

    public static void start(android.content.Context c, String base, String cookie) {
        Intent i = new Intent(c, ListenService.class).putExtra(EXTRA_BASE, base).putExtra(EXTRA_COOKIE, cookie == null ? "" : cookie);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void stop(android.content.Context c) {
        c.startService(new Intent(c, ListenService.class).setAction(ACTION_STOP));
    }

    private boolean slideArmed = false;
    private final Runnable slide = () -> {
        slideArmed = false;
        if (running && own && ownKind == 2 && autoNextPhoto(this) && ownIdx + 1 < ownQueue.size()) stepOwn(1);
    };

    private void startOwnAt(int i) {
        java.util.List<String[]> q = ownQueue;
        if (i < 0 || i >= q.size()) return;
        String[] it = q.get(i);
        int k = 0;
        try { k = Integer.parseInt(it[2]); } catch (Throwable ignored) { }
        ownIdx = i;
        startOwn(it[0], it[1], k);
    }

    private void stepOwn(int d) {
        if (!own) return;
        int n = ownIdx + d;
        if (n < 0 || n >= ownQueue.size()) return;
        startOwnAt(n);
    }

    private void startOwn(String pid, String ptitle, int kind) {
        ExoPlayer p = player;
        if (p == null || !running) return;
        main.removeCallbacks(slide);
        slideArmed = false;
        own = true;
        timeshift = false;
        ownId = pid;
        ownKind = kind;
        loadedId = "own:" + pid;
        title = ptitle; artist = ""; video = kind == 1; durMs = 0; id = pid; has = true; art = null;
        p.setPlaybackSpeed(1f);
        if (kind == 2) {                           // a photo: nothing to play, it is shown by the screen
            p.stop();
            p.clearMediaItems();
        } else {
            fetchArt(pid);
            p.setMediaItem(MediaItem.fromUri(base + "/m/" + pid + "?c=" + cid));
            p.prepare();
            p.setPlayWhenReady(true);
        }
        status = getString(R.string.listen_own_status);
        notifyNow();
    }

    private void leaveOwn() {
        if (!own) return;
        own = false;
        timeshift = false;
        ownId = "";
        ownKind = 0;
        ownIdx = -1;
        main.removeCallbacks(slide);
        slideArmed = false;
        loadedId = "";
        lastLoadAt = 0;
        art = null;
        ExoPlayer p = player;
        if (p != null) { p.stop(); p.clearMediaItems(); p.setPlaybackSpeed(1f); }
        title = stHas ? stTitle : ""; artist = stHas ? stArtist : ""; video = stHas && stVideo; durMs = stHas ? stDur : 0; id = stHas ? stId : ""; has = stHas;
        notifyNow();
        sync();
    }

    // ── Life cycle ──

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            endNote = "";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_OWN.equals(intent.getAction())) {
            if (running) startOwnAt(intent.getIntExtra("index", 0));
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STEP.equals(intent.getAction())) {
            if (running) stepOwn(intent.getIntExtra("delta", 1));
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SHIFT.equals(intent.getAction())) {
            if (running) shift(intent.getIntExtra("op", 0), intent.getLongExtra("ms", 0));
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_LIVE.equals(intent.getAction())) {
            if (running) leaveOwn();
            return START_NOT_STICKY;
        }
        String b = intent == null ? null : intent.getStringExtra(EXTRA_BASE);
        if (b == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground(getString(R.string.listen_connecting), "");
        teardown();
        base = b;
        cookie = intent.getStringExtra(EXTRA_COOKIE) == null ? "" : intent.getStringExtra(EXTRA_COOKIE);
        begin();
        return START_NOT_STICKY;
    }

    /** This phone's own music was playing when listening began: it carries on when listening ends. */
    private static boolean resumeMain = false;

    @Override
    public void onDestroy() {
        teardown();
        if (resumeMain) {
            resumeMain = false;
            PlaybackService ps = PlaybackService.instance;
            if (ps != null) new Handler(Looper.getMainLooper()).postDelayed(ps::resumeNow, 400);
        }
        super.onDestroy();
    }

    private void begin() {
        final int my = ++session;
        running = true;
        startedAt = System.nanoTime();
        endNote = "";
        status = getString(R.string.listen_connecting);
        has = false; video = false; title = ""; artist = ""; id = ""; durMs = 0; art = null; host = "";
        stHas = false; stOk = false; stPlaying = false; loadedId = ""; ver = -1; syncErr = 0;
        drain = false; drainAll = false; drainQ = null; localQueue = false; anchorPending = false; viewTag = "art"; timeshift = false;
        own = false; ownId = ""; ownKind = 0; ownIdx = -1; hostVol = -1f; hostVolLocal = -1f; fast = false; priv = false; libOn = false; dlOn = false; photoId = ""; photoTitle = ""; photoN = 0; photoOf = 0;
        hostView = 2; hostStyle = 0; hostSpeed = 40; hostSa = -1; hostLyr = false;
        baseUrl = base; cookieStr = cookie; clientId = cid;
        cid = Long.toString(System.nanoTime(), 36) + Integer.toString((int) (Math.random() * 100000), 36);
        ua = "NKT Listen (Android) " + Build.MODEL;
        nudgeMs = nudge(this);

        DefaultHttpDataSource.Factory hf = new DefaultHttpDataSource.Factory().setUserAgent(ua).setConnectTimeoutMs(6000).setReadTimeoutMs(15000);
        if (!cookie.isEmpty()) hf.setDefaultRequestProperties(Collections.singletonMap("Cookie", cookie));
        ExoPlayer p = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(hf))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)   // calls don't pause it (KeepPlaying)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        p.setVolume(volume(this));
        p.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) {
                ExoPlayer pp = player;
                if (own) { status = getString(R.string.listen_cant_play); return; }
                if (drain && pp != null && advanceQueue(pp)) return;       // on their own: skip what can't be played
                status = getString(R.string.listen_cant_play);
                loadedId = "";      // loaded again on the next tick
            }
        });
        // another app took the sound for good (the user started their own music): step aside. Calls don't count.
        KeepPlaying.attach(this, p, () -> { if (running && player == p) finish(getString(R.string.listen_focus)); });
        player = p;
        // what this phone was playing before listening comes back when listening ends
        resumeMain = PlaybackService.instance != null && PlaybackService.instance.playingNow();

        new Thread(() -> netLoop(my), "nkt-listen-net").start();
        new Thread(() -> helloLoop(my), "nkt-listen-hello").start();
        main.post(syncTick);
    }

    private void teardown() {
        session++;
        running = false;
        baseUrl = "";
        drain = false;
        own = false;
        main.removeCallbacksAndMessages(null);
        try { HttpURLConnection c = cur; if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        ExoPlayer p = player;
        player = null;
        if (p != null) { try { p.release(); } catch (Throwable ignored) { } }
        has = false;
        status = "";
    }

    private void finish(String note) {
        endNote = note;
        stopSelf();
    }

    // ── Talking to the host (background threads) ──

    private HttpURLConnection open(String path, int readMs) throws IOException {
        String sep = path.contains("?") ? "&" : "?";
        HttpURLConnection c = (HttpURLConnection) new URL(base + path + sep + "c=" + cid).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(readMs);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", ua);
        if (!cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        return c;
    }

    private static String read(HttpURLConnection c) throws IOException {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private void calibrate(int n) {
        long bestRtt = Long.MAX_VALUE, est = offset;
        for (int i = 0; i < n; i++) {
            try {
                long t0 = System.currentTimeMillis();
                HttpURLConnection c = open("/api/time", 4000);
                JSONObject j = new JSONObject(read(c));
                long t1 = System.currentTimeMillis(), rtt = t1 - t0;
                c.disconnect();
                if (rtt < bestRtt) { bestRtt = rtt; est = j.getLong("t") + rtt / 2 - t1; }
                Thread.sleep(60);
            } catch (Throwable ignored) { }
        }
        offset = est;
    }

    private void netLoop(int my) {
        calibrate(6);
        long lastCal = System.currentTimeMillis(), failSince = 0;
        while (running && session == my) {
            try {
                if (System.currentTimeMillis() - lastCal > 60_000) { calibrate(4); lastCal = System.currentTimeMillis(); }
                HttpURLConnection c = open("/api/state?v=" + ver, 32_000);
                cur = c;
                int code = c.getResponseCode();
                if (code == 401) { c.disconnect(); end(my, R.string.listen_wrong_pin); return; }
                if (code == 403) { c.disconnect(); end(my, R.string.listen_removed); return; }
                if (code == 503) {
                    c.disconnect();
                    status = getString(R.string.listen_full);
                    Thread.sleep(3000);
                    continue;
                }
                final JSONObject j = new JSONObject(read(c));
                c.disconnect();
                failSince = 0;
                if (!j.optBoolean("on", true)) { end(my, R.string.listen_ended); return; }
                ver = j.optInt("v", ver);
                main.post(() -> { if (running && session == my) onState(j); });
            } catch (Throwable t) {
                if (!running || session != my) return;
                long now = System.currentTimeMillis();
                if (failSince == 0) failSince = now;
                if (now - failSince > GRACE_MS) { end(my, R.string.listen_ended); return; }
                status = getString(R.string.listen_reconnecting);
                try { Thread.sleep(1200); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void end(int my, int res) {
        final String note = getString(res);
        main.post(() -> { if (running && session == my) finish(note); });
    }

    /** Tells the host who is here and how well in step we are (it shows in "Listening now"). */
    private void helloLoop(int my) {
        while (running && session == my) {
            try {
                HttpURLConnection c = open("/api/hello", 5000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                byte[] body = new JSONObject().put("c", cid).put("mode", own ? "own" : "live").put("own", own ? title : "").put("view", viewTag).put("sync", syncErr).toString().getBytes("UTF-8");
                c.getOutputStream().write(body);
                c.getResponseCode();
                c.disconnect();
            } catch (Throwable ignored) { }
            try { Thread.sleep(4000); } catch (InterruptedException e) { return; }
        }
    }

    // ── Following the host (main thread) ──

    private void onState(JSONObject j) {
        host = j.optString("host", host);
        priv = j.optBoolean("priv");
        libOn = j.optBoolean("lib");
        dlOn = j.optBoolean("dl");
        float hv = j.has("hv") ? (float) Math.max(0, Math.min(1, j.optDouble("hv", 0))) : -1f;
        if (hv != hostVol) {
            hostVol = hv;
            hostVolLocal = -1f;
            ExoPlayer pv = player;
            if (pv != null) pv.setVolume(volume(this));
        }
        if (!libOn && own) { libOffAt = System.currentTimeMillis(); leaveOwn(); }     // the host switched the library off: a pick of your own stops here at once
        JSONObject ph = j.optJSONObject("photo");
        if (ph != null) { photoId = ph.optString("id"); photoTitle = ph.optString("title"); photoN = ph.optInt("n"); photoOf = ph.optInt("of"); }
        else photoId = "";
        hostView = j.optInt("view", hostView);
        hostStyle = j.optInt("style", hostStyle);
        hostSpeed = j.optInt("speedOpt", hostSpeed);
        hostSa = j.optInt("sa", -1);
        drain = j.optBoolean("drain");
        if (!(drain && localQueue)) drainAll = j.optBoolean("drainAll");
        if (drain) drainQ = j.optJSONArray("q");
        if (drain && localQueue) return;          // already carrying on by ourselves: the host's frozen picture is old news
        hostLyr = j.optBoolean("lyr");
        stHas = j.optBoolean("has");
        if (stHas) {
            stOk = j.optBoolean("ok");
            stId = j.optString("id");
            stTitle = j.optString("title");
            stArtist = j.optString("artist");
            stDur = j.optLong("dur");
            stPos = j.optLong("pos");
            stAt = j.optLong("at");
            stPlaying = j.optBoolean("playing");
            stSpeed = (float) j.optDouble("speed", 1.0);
            stVideo = j.optBoolean("video");
            if (!own) { title = stTitle; artist = stArtist; video = stVideo; durMs = stDur; id = stId; }
        } else if (!own) {
            title = ""; artist = ""; video = false; durMs = 0; id = "";
        }
        if (own) return;                          // playing a pick of its own: the host's song carries on without us
        has = stHas;
        ExoPlayer p = player;
        if (p == null) return;
        if (stHas && stOk) {
            if (!stId.equals(loadedId)) loadCurrent(p);
        } else if (!loadedId.isEmpty()) {
            loadedId = "";
            p.stop();
            p.clearMediaItems();
            art = null;
            notifyNow();
        }
        sync();
    }

    /** The host has stopped and left us the queue: the next song that can be streamed, played from the start. */
    private boolean advanceQueue(ExoPlayer p) {
        org.json.JSONArray q = drainQ;
        if (!drain || !drainAll || q == null) return false;
        int i = -1;
        for (int k = 0; k < q.length(); k++) {
            org.json.JSONArray it = q.optJSONArray(k);
            if (it != null && stId.equals(it.optString(0))) { i = k; break; }
        }
        if (i < 0) return false;
        for (int k = i + 1; k < q.length(); k++) {
            org.json.JSONArray it = q.optJSONArray(k);
            if (it == null || it.optInt(4, 1) != 1) continue;     // not something that can be streamed
            stId = it.optString(0); stTitle = it.optString(1); stArtist = it.optString(2); stVideo = it.optInt(3) == 1;
            stOk = true; stHas = true; stDur = 0; stPos = 0; stSpeed = 1f; stPlaying = true;
            stAt = System.currentTimeMillis() + offset;
            localQueue = true; anchorPending = true;
            title = stTitle; artist = stArtist; video = stVideo; durMs = 0; id = stId; has = true; hostLyr = true;
            loadCurrent(p);
            return true;
        }
        return false;
    }

    /** Going back in the live video and returning to it. op: 0 seek to ms, 1 move by ms, 2 pause/play, 3 go live. */
    private void shift(int op, long v) {
        ExoPlayer p = player;
        if (p == null || own || !stHas || !stOk || !stVideo || loadedId.isEmpty()) return;
        long live = Math.max(0, expected() + nudgeMs);
        if (op == 3) { timeshift = false; status = getString(R.string.listen_syncing); main.post(this::notifyNowSafe); return; }
        if (op == 2) {
            timeshift = true;
            if (p.getPlaybackState() == Player.STATE_ENDED) p.seekTo(Math.max(0, live - 1000));
            p.setPlayWhenReady(!p.getPlayWhenReady());
            return;
        }
        long target = op == 1 ? p.getCurrentPosition() + v : v;
        if (target >= live - 2000) { timeshift = false; return; }       // back at the live edge
        timeshift = true;
        p.seekTo(Math.max(0, target));
        p.setPlayWhenReady(true);
    }

    private void loadCurrent(ExoPlayer p) {
        timeshift = false;
        loadedId = stId;
        lastLoadAt = System.currentTimeMillis();
        art = null;
        fetchArt(stId);
        p.setMediaItem(MediaItem.fromUri(base + "/m/" + stId + "?c=" + cid));
        p.prepare();
        p.setPlayWhenReady(stPlaying);
        notifyNow();
    }

    private long expected() {
        long p = stPos;
        if (stPlaying) p += (long) ((System.currentTimeMillis() + offset - stAt) * stSpeed);
        return p;
    }

    private final Runnable syncTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { sync(); } catch (Throwable ignored) { }
            main.postDelayed(this, 400);
        }
    };

    private void sync() {
        ExoPlayer p = player;
        if (p == null) return;
        if (own) {
            syncErr = 0;
            if (ownKind == 2) {                    // a photo: moves on by itself after a while, if that is wanted
                boolean want = autoNextPhoto(this) && ownIdx + 1 < ownQueue.size();
                if (want && !slideArmed) { slideArmed = true; main.postDelayed(slide, 5000); }
                else if (!want && slideArmed) { main.removeCallbacks(slide); slideArmed = false; }
                status = getString(R.string.listen_own_status);
                return;
            }
            int os = p.getPlaybackState();
            if (os == Player.STATE_ENDED && autoNext(this) && ownIdx + 1 < ownQueue.size()) { stepOwn(1); return; }
            status = getString(os == Player.STATE_BUFFERING ? R.string.listen_buffering : os == Player.STATE_ENDED ? R.string.listen_own_done : R.string.listen_own_status);
            return;
        }
        if (!stHas) { timeshift = false; status = getString(R.string.listen_waiting); if (p.getPlayWhenReady()) p.setPlayWhenReady(false); return; }
        if (!stOk) { status = getString(R.string.listen_cant_stream); return; }
        if (loadedId.isEmpty()) { if (System.currentTimeMillis() - lastLoadAt > 5000) loadCurrent(p); return; }
        if (timeshift && !stVideo) timeshift = false;
        if (!timeshift) fast = false;
        if (timeshift) {                          // gone back in the live video: left alone until GO LIVE
            long behind = Math.max(0, expected() + nudgeMs - p.getCurrentPosition());
            if (p.getPlaybackState() == Player.STATE_ENDED || behind < 1500) timeshift = false;
            else {
                float want = fast ? 2f : stSpeed;                 // 2× while held, to catch up with the live moment
                if (Math.abs(p.getPlaybackParameters().speed - want) > 0.001f) p.setPlaybackSpeed(want);
                status = getString(R.string.listen_behind, Ui.time(behind));
                return;
            }
        }
        if (anchorPending) {                      // a song of the queue just began: its own clock starts when it really plays
            if (p.getPlaybackState() == Player.STATE_READY && p.isPlaying()) {
                stPos = p.getCurrentPosition();
                stAt = System.currentTimeMillis() + offset;
                anchorPending = false;
            }
            status = getString(R.string.listen_syncing);
            return;
        }
        long exp = Math.max(0, expected() + nudgeMs);
        int state = p.getPlaybackState();
        long err = p.getCurrentPosition() - exp;
        syncErr = (int) err;
        if (!stPlaying) {
            if (p.getPlayWhenReady()) p.setPlayWhenReady(false);
            if (state == Player.STATE_READY && Math.abs(err) > 120) p.seekTo(exp);
            status = getString(R.string.listen_host_paused);
            return;
        }
        if (!p.getPlayWhenReady()) {
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) p.seekTo(exp);
            p.setPlayWhenReady(true);
            status = getString(R.string.listen_syncing);
            return;
        }
        if (state == Player.STATE_BUFFERING) { status = getString(R.string.listen_buffering); return; }
        if (state == Player.STATE_ENDED) {
            if (drain) { if (!advanceQueue(p)) finish(getString(drainAll ? R.string.listen_queue_done : R.string.listen_song_done)); return; }
            if (exp < stDur - 500) p.seekTo(exp);
            return;
        }
        long a = Math.abs(err);
        float speed = stSpeed;
        if (a > 900) { p.seekTo(exp); status = getString(R.string.listen_syncing); return; }
        if (a > 40) speed = stSpeed * (1f + Math.max(-0.08f, Math.min(0.08f, -err / 2500f)));
        if (Math.abs(p.getPlaybackParameters().speed - speed) > 0.001f) p.setPlaybackSpeed(speed);
        status = a < 150
                ? getString(R.string.listen_in_sync, String.format(Locale.US, "%s%.2f s", err >= 0 ? "+" : "−", a / 1000.0))
                : getString(R.string.listen_syncing);
    }

    private void fetchArt(final String forId) {
        new Thread(() -> {
            try {
                HttpURLConnection c = open("/art/" + forId, 6000);
                if (c.getResponseCode() == 200) {
                    Bitmap b;
                    try (InputStream in = c.getInputStream()) { b = BitmapFactory.decodeStream(in); }
                    if (b != null && (forId.equals(loadedId) || (own && forId.equals(ownId)))) {
                        art = b;
                        main.post(this::notifyNowSafe);
                    }
                }
                c.disconnect();
            } catch (Throwable ignored) { }
        }, "nkt-listen-art").start();
    }

    private void notifyNowSafe() { if (running) notifyNow(); }

    // ── Notification ──

    private Notification build(String t, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.listen), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 5, new Intent(this, ListenActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 6, new Intent(this, ListenService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_wifi)
                .setContentTitle(t)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop_caps), stop).build());
        Bitmap a = art;
        if (a != null) b.setLargeIcon(a);
        return b.build();
    }

    private void goForeground(String t, String text) {
        Notification n = build(t, text);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF_ID, n);
    }

    private void notifyNow() {
        String t = has && !title.isEmpty() ? title : getString(R.string.listen);
        String text = has && !artist.isEmpty() ? artist : (host.isEmpty() ? "" : getString(R.string.listen_from, host));
        try { getSystemService(NotificationManager.class).notify(NOTIF_ID, build(t, text)); } catch (Throwable ignored) { }
    }
}
