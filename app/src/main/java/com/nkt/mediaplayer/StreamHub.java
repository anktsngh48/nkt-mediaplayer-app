package com.nkt.mediaplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stream: what the listeners get to see and hear. It follows whichever player is active (the music player, or the
 * video player when a video is on), keeps a picture of it for the web server, and passes the host's play, pause,
 * next and seek on to every listener. It also runs "Silence this phone".
 *
 * Everything that touches a player runs on the main thread; the server reads the saved picture only.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class StreamHub {

    private StreamHub() { }

    static volatile Context app;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Object lock = new Object();

    public static volatile boolean running = false;
    /** The host pressed STOP but let the listeners finish what is playing: nothing new is taken in or mirrored. */
    public static volatile boolean draining = false;
    /** While draining: true = listeners play the whole queue on, false = they only finish the current song. */
    public static volatile boolean drainAll = false;
    private static volatile long drainEndAt = 0;
    public static volatile String error = null;

    /** The video player, while the video screen is open (set by PlayerActivity). */
    private static volatile ExoPlayer videoPlayer;

    // ── What is playing right now ──

    static final class Snap {
        boolean has;
        String id = "", title = "", artist = "", album = "";
        long dur, pos, at;
        boolean playing, video, ok;
        float speed = 1f;
        int index, count;
    }

    private static volatile Snap snap = new Snap();
    private static int version = 1;
    private static String lastSig = "";
    private static int lastView = -1, lastStyle = -1, lastSpeedOpt = -1, lastSa = -2;
    private static boolean lastLib = false, lastDl = false, silenced = false;

    // Everything the listeners may ask for by id
    private static volatile Map<String, MediaInfo> queueMap = new HashMap<>();
    private static volatile String queueJson = "[]";
    private static volatile Map<String, MediaInfo> libMap = new HashMap<>();
    private static volatile String libJson = null;
    private static volatile List<MediaInfo> libFor = null;

    // Lyrics of the song that is playing
    private static volatile String lyricsId = "";
    private static volatile String lyricsJson = "{}";
    private static String lyricsAsked = "";

    // ── Listeners ──

    static final class Listener {
        final String id;
        String ip = "", agent = "", name = "";
        long first, last;
        String mode = "live", own = "", view = "";
        int sync;
        volatile boolean kicked;
        Listener(String id) { this.id = id; }
    }

    private static final Map<String, Listener> listeners = new ConcurrentHashMap<>();
    private static final java.util.Set<String> bannedIps = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Blocked for good (saved); kept across streams. */
    private static final java.util.Set<String> blockedIps = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ── Life cycle ──

    static void begin(Context c) {
        app = c.getApplicationContext();
        draining = false;
        drainAll = false;
        drainEndAt = 0;
        listeners.clear();
        bannedIps.clear();
        blockedIps.clear();
        for (String[] b : StreamPrefs.blocked(app)) blockedIps.add(b[0]);
        error = null;
        lastSig = "";
        lastView = lastStyle = lastSpeedOpt = -1;
        lastSa = -2;
        lyricsAsked = "";
        lyricsId = "";
        lyricsJson = "{}";
        queueMap = new HashMap<>();
        libMap = new HashMap<>();
        libJson = null;
        libFor = null;
        running = true;
        vizThread = new HandlerThread("nkt-stream-viz");
        vizThread.start();
        vizH = new Handler(vizThread.getLooper());
        main.post(tick);
        bump();
    }

    static void end() {
        running = false;
        draining = false;
        main.removeCallbacks(tick);
        main.post(() -> {
            for (Player p : attached) { try { p.removeListener(events); } catch (Throwable ignored) { } }
            attached.clear();
            if (silenced) {
                silenced = false;
                ExoPlayer a = audioPlayer();
                try { if (a != null && a.getVolume() == 0f) a.setVolume(1f); } catch (Throwable ignored) { }
                ExoPlayer v = videoPlayer;
                try { if (v != null && v.getVolume() == 0f) v.setVolume(1f); } catch (Throwable ignored) { }
            }
        });
        setViz(false);
        if (vizThread != null) { vizThread.quitSafely(); vizThread = null; vizH = null; }
        listeners.clear();
        synchronized (lock) { lock.notifyAll(); }
    }

    /** The video screen opened (player) or closed (null). */
    public static void onVideoPlayer(ExoPlayer p) {
        videoPlayer = p;
        if (running && !draining) main.post(() -> { attachAll(); capture(); });
    }

    private static ExoPlayer audioPlayer() {
        PlaybackService s = PlaybackService.instance;
        return s == null ? null : s.streamPlayer();
    }

    private static Player active() {
        ExoPlayer a = audioPlayer();
        ExoPlayer v = videoPlayer;
        boolean aHas = a != null && a.getMediaItemCount() > 0;
        boolean vHas = v != null && v.getMediaItemCount() > 0;
        if (vHas && (v.getPlayWhenReady() || !aHas || !a.getPlayWhenReady())) return v;
        return aHas ? a : (vHas ? v : null);
    }

    // ── Watching the players ──

    private static final List<Player> attached = new ArrayList<>();

    private static final Player.Listener events = new Player.Listener() {
        @Override public void onEvents(Player p, Player.Events e) {
            if (e.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) {
                if (e.contains(Player.EVENT_TIMELINE_CHANGED)) queueDirty = true;
                capture();
            }
        }
        @Override public void onVolumeChanged(float volume) {
            if (volume > 0f && StreamPrefs.silence(app)) applySilence();     // a sleep-timer fade must not undo the silence
        }
        @Override public void onPlayerError(PlaybackException error) { capture(); }
    };

    private static boolean queueDirty = true;

    /** What the video screen queues has no title or type of its own, so give it both. */
    private static final Map<String, String> names = new HashMap<>();

    private static MediaInfo infoOf(Player p, MediaItem it, long dur) {
        MediaInfo mi = Media.info(it, dur);
        if (p != videoPlayer || mi.video || mi.uri == null) return mi;
        String key = mi.key();
        String t = it.mediaMetadata.title == null ? names.get(key) : it.mediaMetadata.title.toString();
        if (t == null) {
            t = mi.uri.getLastPathSegment() == null ? "Video" : mi.uri.getLastPathSegment();
            try {
                if ("content".equals(mi.uri.getScheme())) {
                    try (android.database.Cursor c = app.getContentResolver().query(mi.uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (c != null && c.moveToFirst() && c.getString(0) != null) t = c.getString(0);
                    }
                }
            } catch (Throwable ignored) { }
            int dot = t.lastIndexOf('.');
            if (dot > 0) t = t.substring(0, dot);
            names.put(key, t);
        }
        MediaInfo v = new MediaInfo(mi.id, mi.uri, t, mi.artist, mi.album, -1, "", Math.max(0, dur), true);
        v.path = mi.path; v.fileName = mi.fileName;
        return v;
    }

    private static void attachAll() {
        for (Player p : new Player[]{audioPlayer(), videoPlayer}) {
            if (p == null || attached.contains(p)) continue;
            try { p.addListener(events); attached.add(p); } catch (Throwable ignored) { }
        }
        for (int i = attached.size() - 1; i >= 0; i--) {                    // a released video player goes away by itself
            if (attached.get(i) != audioPlayer() && attached.get(i) != videoPlayer) attached.remove(i);
        }
        applySilence();
    }

    private static final Runnable tick = new Runnable() {
        private int n = 0;
        @Override public void run() {
            if (!running) return;
            try {
                attachAll();
                Context c = app;
                int view = Prefs.npNow(c), style = Prefs.vizStyle(c), sp = Prefs.vizSpeed(c), sa = Prefs.saNote(c);
                boolean lib = StreamPrefs.libraryOn(c), dl = StreamPrefs.allowDownloads(c);
                boolean changed = view != lastView || style != lastStyle || sp != lastSpeedOpt || sa != lastSa || lib != lastLib || dl != lastDl;
                lastView = view; lastStyle = style; lastSpeedOpt = sp; lastSa = sa; lastLib = lib; lastDl = dl;
                applySilence();
                if (++n % 20 == 0) { capture(); changed = true; }                // every ~10 s: a fresh anchor for everyone
                else if (!changed) capture();
                if (changed) bump();
                // the visualiser only analyses the sound while someone is looking at it
                boolean want = false;
                long now = System.currentTimeMillis();
                for (Listener l : listeners.values())
                    if (!l.kicked && now - l.last < 15_000 && "live".equals(l.mode) && "viz".equals(l.view)) want = true;
                setViz(want);
            } catch (Throwable ignored) { }
            main.postDelayed(this, 500);
        }
    };

    /** Looks at the active player and saves what it sees; tells the listeners when it changed. */
    static void capture() {
        Player p = active();
        Snap s = new Snap();
        if (p != null && p.getMediaItemCount() > 0) {
            MediaItem it = p.getCurrentMediaItem();
            long dur = p.getDuration();
            if (dur == C.TIME_UNSET || dur < 0) dur = 0;
            if (it != null) {
                MediaInfo mi = infoOf(p, it, dur);
                s.has = true;
                s.id = Lyrics.idOf(mi.key());
                s.title = mi.title;
                s.artist = mi.artist;
                s.album = mi.album;
                s.dur = dur;
                s.video = mi.video;
                String sc = mi.uri == null ? "" : String.valueOf(mi.uri.getScheme());
                s.ok = "content".equals(sc) || "file".equals(sc);
                s.pos = Math.max(0, p.getCurrentPosition());
                s.at = System.currentTimeMillis();
                s.playing = p.getPlayWhenReady() && p.getPlaybackState() != Player.STATE_ENDED && p.getPlaybackState() != Player.STATE_IDLE;    // buffering after a seek still counts as playing
                s.speed = p.getPlaybackParameters().speed;
                s.index = p.getCurrentMediaItemIndex();
                s.count = p.getMediaItemCount();
                if (queueDirty || !queueMap.containsKey(s.id)) buildQueue(p);
                if (s.ok && !s.id.equals(lyricsAsked)) fetchLyrics(s.id, mi);
            }
        }
        Snap old = snap;
        snap = s;
        String sig = s.has ? s.id + "|" + s.playing + "|" + s.speed + "|" + s.index + "|" + s.count + "|" + (s.pos / 1500)
                + "|" + (old.playing == s.playing ? "" : "p") : "none";
        boolean moved = s.has && old.has && s.id.equals(old.id) && s.playing && old.playing
                && Math.abs((s.pos - old.pos) - (s.at - old.at) * s.speed) > 400;     // a seek
        if (!s.has || !old.has || !s.id.equals(old.id) || s.playing != old.playing || s.speed != old.speed
                || s.count != old.count || moved) bump();
        lastSig = sig;
    }

    private static void buildQueue(Player p) {
        queueDirty = false;
        Map<String, MediaInfo> map = new HashMap<>();
        JSONArray q = new JSONArray();
        int n = Math.min(p.getMediaItemCount(), 500);
        try {
            for (int i = 0; i < n; i++) {
                MediaInfo mi = infoOf(p, p.getMediaItemAt(i), 0);
                String id = Lyrics.idOf(mi.key());
                map.put(id, mi);
                String sc = mi.uri == null ? "" : String.valueOf(mi.uri.getScheme());
                q.put(new JSONArray().put(id).put(mi.title).put(mi.artist).put(mi.video ? 1 : 0).put("content".equals(sc) || "file".equals(sc) ? 1 : 0));
            }
        } catch (Throwable ignored) { }
        queueMap = map;
        queueJson = q.toString();
    }

    private static void fetchLyrics(String id, MediaInfo mi) {
        lyricsAsked = id;
        final MediaInfo m = mi;
        new Thread(() -> {
            String json = "{}";
            try {
                Lyrics.Result r = Lyrics.find(app, m, m.title, m.artist, m.album, m.durationMs);
                JSONObject o = new JSONObject();
                o.put("synced", r.synced);
                o.put("instrumental", r.instrumental);
                o.put("offset", Lyrics.offset(app, m));
                JSONArray lines = new JSONArray();
                for (Lyrics.Line l : r.lines) lines.put(new JSONArray().put(l.ms).put(l.text));
                o.put("lines", lines);
                json = o.toString();
            } catch (Throwable ignored) { }
            if (id.equals(snap.id)) { lyricsJson = json; lyricsId = id; bump(); }
        }, "nkt-stream-lyrics").start();
    }

    // ── Stop, but let the listeners finish the song ──

    /** Is there something a listener could be left to carry on with: more of the queue, or the rest of the song? */
    static boolean canDrain() { return canSong() || canQueue(); }

    private static boolean base() {
        Snap s = snap;
        return running && !draining && s.has && s.ok && listenerCount() > 0;
    }

    /** Is there still some of the current song to be left playing? */
    static boolean canSong() {
        Snap s = snap;
        return base() && s.dur > 0 && s.pos < s.dur - 3000;
    }

    /** Is there more of the queue after this song? */
    static boolean canQueue() {
        Snap s = snap;
        return base() && s.index + 1 < s.count;
    }

    /**
     * Stops following the host's player (its pause, next and seek no longer reach the listeners) and freezes what is
     * playing as a running clock. The listeners play that song out in step, then carry on down the queue each on their
     * own. Returns false when there is nothing to carry on with. Main thread.
     */
    static boolean freeze(boolean whole) {
        if (whole ? !canQueue() : !canSong()) return false;
        drainAll = whole;
        capture();
        Snap s = snap;
        if (!s.has) return false;
        long now = System.currentTimeMillis();
        s.pos = s.pos + (s.playing ? (long) ((now - s.at) * s.speed) : 0);
        s.at = now;
        s.playing = true;
        drainEndAt = now + 6L * 3600_000L;                      // a safety limit; normally it ends when everyone has left
        draining = true;
        main.removeCallbacks(tick);
        for (Player p : attached) { try { p.removeListener(events); } catch (Throwable ignored) { } }
        attached.clear();
        if (silenced) {                                         // the host's own phone is back to normal
            silenced = false;
            ExoPlayer a = audioPlayer();
            try { if (a != null && a.getVolume() == 0f) a.setVolume(1f); } catch (Throwable ignored) { }
            ExoPlayer v = videoPlayer;
            try { if (v != null && v.getVolume() == 0f) v.setVolume(1f); } catch (Throwable ignored) { }
        }
        setViz(false);
        bump();
        return true;
    }

    /** True when it is time to switch off: nobody is left (they finished the queue or went away), or the safety limit. */
    static boolean drainDone() {
        if (!draining) return false;
        long now = System.currentTimeMillis();
        if (now > drainEndAt) return true;
        for (Listener l : listeners.values()) if (!l.kicked && now - l.last < 25_000) return false;
        return true;
    }

    // ── Versions, for the listeners' long poll ──

    static void bump() {
        synchronized (lock) { version++; lock.notifyAll(); }
    }

    static int version() { synchronized (lock) { return version; } }

    /** Waits until the version moves past {@code have}, or the time is up. */
    static int waitPast(int have, long ms) {
        synchronized (lock) {
            long end = System.currentTimeMillis() + ms;
            while (version == have && running) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try { lock.wait(left); } catch (InterruptedException e) { break; }
            }
            return version;
        }
    }

    // ── What the listener page is told ──

    static String stateJson() {
        Snap s = snap;
        Context c = app;
        JSONObject o = new JSONObject();
        try {
            o.put("v", version());
            o.put("now", System.currentTimeMillis());
            o.put("host", deviceName());
            o.put("on", running);
            o.put("drain", draining);
            o.put("drainAll", draining && drainAll);
            o.put("has", s.has);
            if (s.has) {
                o.put("id", s.id);
                o.put("title", s.title);
                o.put("artist", s.artist);
                o.put("album", s.album);
                o.put("dur", s.dur);
                o.put("pos", s.pos);
                o.put("at", s.at);
                o.put("playing", s.playing);
                o.put("speed", s.speed);
                o.put("video", s.video);
                o.put("ok", s.ok);
                o.put("index", s.index);
                o.put("count", s.count);
                o.put("lyr", s.id.equals(lyricsId));
            }
            o.put("view", Prefs.npNow(c));          // 0 visualiser, 1 lyrics, 2 art: what the host is looking at
            o.put("style", Prefs.vizStyle(c));      // 0 windscreen, 1 gamaka, 2 steady
            o.put("speedOpt", Prefs.vizSpeed(c));
            o.put("sa", Prefs.saNote(c));
            o.put("lib", StreamPrefs.libraryOn(c) && !draining);
            o.put("dl", StreamPrefs.allowDownloads(c) && !draining);
            o.put("q", new JSONArray(queueJson));
        } catch (Exception ignored) { }
        return o.toString();
    }

    private static final Map<String, String> lyricsCache = Collections.synchronizedMap(new LinkedHashMap<String, String>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 30; }
    });

    static String lyricsFor(String id) {
        if (id == null) return "{}";
        if (id.equals(lyricsId)) return lyricsJson;
        if (!draining) return "{}";
        // the listeners are carrying on with the queue on their own: look up the lyrics of whatever they reach
        String hit = lyricsCache.get(id);
        if (hit != null) return hit;
        MediaInfo m = queueMap.get(id);
        if (m == null) return "{}";
        String json = "{}";
        try {
            Lyrics.Result r = Lyrics.find(app, m, m.title, m.artist, m.album, m.durationMs);
            JSONObject o = new JSONObject();
            o.put("synced", r.synced);
            o.put("instrumental", r.instrumental);
            o.put("offset", Lyrics.offset(app, m));
            JSONArray lines = new JSONArray();
            for (Lyrics.Line l : r.lines) lines.put(new JSONArray().put(l.ms).put(l.text));
            o.put("lines", lines);
            json = o.toString();
        } catch (Throwable ignored) { }
        lyricsCache.put(id, json);
        return json;
    }

    // ── Library for listeners ──

    /** Runs on a server thread. */
    static synchronized String library() {
        List<MediaInfo> items = StreamShare.items(app);
        if (libJson == null || libFor != items) {
            Map<String, MediaInfo> map = new LinkedHashMap<>();
            JSONArray arr = new JSONArray();
            for (MediaInfo m : items) {
                String sc = m.uri == null ? "" : String.valueOf(m.uri.getScheme());
                if (!"content".equals(sc) && !"file".equals(sc)) continue;
                String id = Lyrics.idOf(m.key());
                if (map.containsKey(id)) continue;
                map.put(id, m);
                arr.put(new JSONArray().put(id).put(m.title).put(m.artist).put(m.durationMs / 1000).put(m.video ? 1 : 0).put(m.size).put(m.folder));
            }
            JSONArray pls = new JSONArray();
            try {
                for (String name : Playlists.names(app)) {
                    JSONArray ids = new JSONArray();
                    for (MediaInfo m : Playlists.items(app, name)) {
                        String id = Lyrics.idOf(m.key());
                        if (map.containsKey(id)) ids.put(id);
                    }
                    if (ids.length() > 0) pls.put(new JSONObject().put("name", name).put("ids", ids));
                }
            } catch (Exception ignored) { }
            JSONObject o = new JSONObject();
            try { o.put("items", arr); o.put("playlists", pls); } catch (Exception ignored) { }
            libMap = map;
            libJson = o.toString();
            libFor = items;
        }
        return libJson;
    }

    /** A song or video a listener may play: what is in the queue, or (when allowed) the shared library. */
    static MediaInfo resolve(String id) {
        if (id == null) return null;
        if (draining) return queueMap.get(id);                 // only what was queued
        MediaInfo m = queueMap.get(id);
        if (m != null) return m;
        if (StreamPrefs.libraryOn(app)) {
            m = libMap.get(id);
            if (m == null) { library(); m = libMap.get(id); }
            return m;
        }
        return null;
    }

    // ── Pictures ──

    private static final Map<String, byte[]> artCache = new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) { return size() > 12; }
    };

    /** The album art (or the app logo) as a JPEG. Not on the main thread. */
    static byte[] artJpeg(String id) {
        MediaInfo m = resolve(id);
        if (m == null) return null;
        String key = id + "/" + ArtStore.version.get();
        synchronized (artCache) { byte[] b = artCache.get(key); if (b != null) return b; }
        try {
            Bitmap bmp = NotifArt.bitmapFor(app, m, 640);
            if (bmp == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 86, out);
            byte[] b = out.toByteArray();
            synchronized (artCache) { artCache.put(key, b); }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Visualiser: the host's own beat and pitch, stamped with the song's time ──

    private static HandlerThread vizThread;
    private static Handler vizH;
    private static boolean vizOn = false;
    private static final int RING = 240;
    private static final long[] sT = new long[RING];
    private static final float[] sHz = new float[RING], sLvl = new float[RING];
    private static final int[] sBeat = new int[RING];
    private static int sN = 0;
    private static int songNoSeen = 0;

    private static synchronized void setViz(boolean on) {
        if (on == vizOn) return;
        vizOn = on;
        if (on) {
            AudioAnalyzer.start();
            sN = 0;
            if (vizH != null) vizH.post(sampler);
        } else {
            AudioAnalyzer.stop();
            if (vizH != null) vizH.removeCallbacks(sampler);
        }
    }

    private static final Runnable sampler = new Runnable() {
        @Override public void run() {
            Handler h = vizH;
            if (h == null || !vizOn) return;
            Snap s = snap;
            if (s.has && s.playing) {
                long pos = s.pos + Math.round((System.currentTimeMillis() - s.at) * s.speed);
                synchronized (StreamHub.class) {
                    int i = sN % RING;
                    sT[i] = pos;
                    sHz[i] = AudioAnalyzer.pitchHz;
                    sBeat[i] = AudioAnalyzer.beatCount;
                    sLvl[i] = AudioAnalyzer.level;
                    sN++;
                }
            }
            h.postDelayed(this, 40);
        }
    };

    /** Samples newer than {@code after} (a song time in ms), as JSON. */
    static synchronized String vizJson(long after) {
        JSONObject o = new JSONObject();
        JSONArray a = new JSONArray();
        try {
            int from = Math.max(0, sN - RING);
            for (int k = from; k < sN; k++) {
                int i = k % RING;
                if (sT[i] <= after) continue;
                a.put(new JSONArray().put(sT[i]).put(Math.round(sHz[i] * 10) / 10.0).put(sBeat[i]).put(Math.round(sLvl[i] * 100) / 100.0));
            }
            o.put("s", a);
            o.put("bpm", Math.round(AudioAnalyzer.bpm));
            o.put("autoSa", AudioAnalyzer.autoSa);
            o.put("song", AudioAnalyzer.songNo);
            o.put("on", vizOn);
        } catch (Exception ignored) { }
        return o.toString();
    }

    // ── Control from the Stream screen: same as pressing the buttons on the player ──

    public static void playPause() {
        Player p = active();
        if (p == null) return;
        if (p.isPlaying()) p.pause(); else Media.play(p);
    }

    public static void next() {
        Player p = active();
        if (p != null && p.hasNextMediaItem()) p.seekToNextMediaItem();
    }

    public static void previous() {
        Player p = active();
        if (p != null) p.seekToPrevious();
    }

    public static boolean isPlaying() { Snap s = snap; return s.has && s.playing; }
    public static String nowTitle() { Snap s = snap; return s.has ? s.title : ""; }
    public static String nowArtist() { Snap s = snap; return s.has ? s.artist : ""; }
    public static long nowPos() { Snap s = snap; return !s.has ? 0 : s.playing ? s.pos + Math.round((System.currentTimeMillis() - s.at) * s.speed) : s.pos; }
    public static long nowDur() { Snap s = snap; return s.has ? s.dur : 0; }
    public static boolean hasItem() { return snap.has; }
    public static boolean nowOk() { Snap s = snap; return !s.has || s.ok; }

    /** The picture for the Stream screen (small); not on the main thread. */
    public static Bitmap nowArt() {
        Snap s = snap;
        if (!s.has) return null;
        MediaInfo m = queueMap.get(s.id);
        if (m == null) return null;
        try { return NotifArt.bitmapFor(app, m, 200); } catch (Throwable t) { return null; }
    }

    public static String nowId() { return snap.id; }

    // ── Silence this phone ──

    /** Volume 0 on the player: the stream carries on, this phone makes no sound. */
    static void applySilence() {
        if (app == null) return;
        boolean sil = running && !draining && StreamPrefs.silence(app);
        for (Player p : new Player[]{audioPlayer(), videoPlayer}) {
            if (p == null) continue;
            try {
                if (sil) { if (p.getVolume() != 0f) p.setVolume(0f); }
                else if (silenced && p.getVolume() == 0f) p.setVolume(1f);
            } catch (Throwable ignored) { }
        }
        silenced = sil;
    }

    /** The switch on the Stream screen. */
    public static void setSilence(Context c, boolean on) {
        if (app == null) app = c.getApplicationContext();
        StreamPrefs.setSilence(c, on);
        main.post(() -> { applySilence(); bump(); });
    }

    // ── Listeners ──

    /** Registers or refreshes a listener. Returns null when it may not listen (removed, or the limit is reached). */
    static Listener touch(String id, String ip, String agent) {
        if (id == null || id.isEmpty()) return null;
        if (bannedIps.contains(ip) || blockedIps.contains(ip)) return null;
        Listener l = listeners.get(id);
        long now = System.currentTimeMillis();
        if (l == null) {
            if (draining) return null;
            int limit = StreamPrefs.limit(app);
            if (limit > 0 && activeCount() >= limit) return null;
            l = new Listener(id);
            l.first = now;
            listeners.put(id, l);
        }
        if (l.kicked) return null;
        l.ip = ip;
        if (agent != null && !agent.equals(l.agent)) { l.agent = agent; l.name = deviceOf(agent); }
        l.last = now;
        return l;
    }

    static boolean kickedOrBanned(String id, String ip) {
        Listener l = id == null ? null : listeners.get(id);
        return bannedIps.contains(ip) || blockedIps.contains(ip) || (l != null && l.kicked);
    }

    static boolean isBlocked(String ip) { return ip != null && blockedIps.contains(ip); }

    static boolean limitReached(String id) {
        int limit = StreamPrefs.limit(app);
        return limit > 0 && !listeners.containsKey(id) && activeCount() >= limit;
    }

    static int activeCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Listener l : listeners.values()) if (!l.kicked && now - l.last < 15_000) n++;
        return n;
    }

    public static List<String[]> listenerRows() {
        long now = System.currentTimeMillis();
        List<Listener> list = new ArrayList<>();
        for (Listener l : listeners.values()) if (!l.kicked && now - l.last < 15_000) list.add(l);
        Collections.sort(list, (a, b) -> Long.compare(a.first, b.first));
        List<String[]> out = new ArrayList<>();
        for (Listener l : list) {
            String sub = l.ip + " · " + ("own".equals(l.mode) ? "own pick" + (l.own.isEmpty() ? "" : ": " + l.own)
                    : "following you" + (Math.abs(l.sync) < 150 ? " · in sync" : ""));
            out.add(new String[]{l.id, l.name.isEmpty() ? "Browser" : l.name, sub});
        }
        return out;
    }

    public static int listenerCount() { return activeCount(); }

    public static void kick(String id) {
        Listener l = listeners.get(id);
        if (l == null) return;
        l.kicked = true;
        if (l.ip != null && !l.ip.isEmpty()) bannedIps.add(l.ip);
        bump();
    }

    /** Removes a device and blocks its address for every stream from now on, until the host unblocks it. */
    public static void block(String id) {
        Listener l = listeners.get(id);
        if (l == null || l.ip == null || l.ip.isEmpty()) return;
        l.kicked = true;
        blockedIps.add(l.ip);
        if (app != null) StreamPrefs.addBlocked(app, l.ip, l.name);
        bump();
    }

    /** Lets a blocked address in again (the next stream, or this one). */
    public static void unblock(Context c, String ip) {
        StreamPrefs.removeBlocked(c, ip);
        blockedIps.remove(ip);
        bannedIps.remove(ip);
        for (Listener l : listeners.values()) if (ip.equals(l.ip)) l.kicked = false;
        bump();
    }

    /** Devices the host removed: [id, name, ip]. */
    public static List<String[]> kickedRows() {
        List<Listener> list = new ArrayList<>();
        for (Listener l : listeners.values()) if (l.kicked && !blockedIps.contains(l.ip)) list.add(l);
        Collections.sort(list, (a, b) -> Long.compare(b.last, a.last));
        List<String[]> out = new ArrayList<>();
        for (Listener l : list) out.add(new String[]{l.id, l.name.isEmpty() ? "Browser" : l.name, l.ip});
        return out;
    }

    /** Lets a kicked device back in. It has to open the link again. */
    public static void unkick(String id) {
        Listener l = listeners.get(id);
        if (l == null) return;
        l.kicked = false;
        if (l.ip != null) bannedIps.remove(l.ip);
        for (Listener o : listeners.values()) if (o != l && o.kicked && l.ip != null && l.ip.equals(o.ip)) o.kicked = false;
        l.last = 0;
        bump();
    }

    // ── Names ──

    static String deviceName() {
        String name = null;
        try {
            if (Build.VERSION.SDK_INT >= 25)
                name = android.provider.Settings.Global.getString(app.getContentResolver(), android.provider.Settings.Global.DEVICE_NAME);
        } catch (Throwable ignored) { }
        if (name == null || name.isEmpty()) name = Build.MANUFACTURER + " " + Build.MODEL;
        return name;
    }

    static String deviceOf(String ua) {
        if (ua == null) return "Browser";
        if (ua.startsWith("NKT Listen")) return ua.replaceFirst("^NKT Listen \\(Android\\) ?", "NKT app · ");
        String os = ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad" : ua.contains("CrKey") ? "Chromecast"
                : ua.matches("(?i).*(smart-?tv|tizen|web0s|hbbtv|aft[a-z]|bravia).*") ? "TV"
                : ua.contains("Android") ? "Android" : ua.contains("Windows") ? "Windows"
                : ua.contains("Macintosh") ? "Mac" : ua.contains("CrOS") ? "Chromebook" : ua.contains("Linux") ? "Linux" : "";
        String br = ua.contains("Edg") ? "Edge" : ua.contains("OPR") || ua.contains("Opera") ? "Opera"
                : ua.contains("Firefox") || ua.contains("FxiOS") ? "Firefox" : ua.contains("SamsungBrowser") ? "Samsung Internet"
                : ua.contains("CriOS") || ua.contains("Chrome") ? "Chrome" : ua.contains("Safari") ? "Safari" : "Browser";
        return os.isEmpty() ? br : br + " on " + os;
    }
}
