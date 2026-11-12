package com.nkt.mediaplayer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Looks for a song's album art on the internet: iTunes, Deezer and the Cover Art Archive. Nothing is generated. */
public final class ArtFinder {

    private ArtFinder() { }

    private static final ExecutorService AUTO = Executors.newSingleThreadExecutor();

    public static class Hit {
        public final String source, label, thumbUrl, fullUrl;
        Hit(String source, String label, String thumbUrl, String fullUrl) {
            this.source = source; this.label = label; this.thumbUrl = thumbUrl; this.fullUrl = fullUrl;
        }
    }

    /** All sources, best first. Call off the main thread. */
    public static List<Hit> search(String title, String artist, String album) {
        if (Net.off()) return new ArrayList<>();
        String t = Lyrics.cleanTitle(title), a = artist == null ? "" : artist.trim();
        if (a.isEmpty() && t.contains(" - ")) {
            int i = t.indexOf(" - ");
            a = t.substring(0, i).trim();
            t = t.substring(i + 3).trim();
        }
        List<Hit> out = new ArrayList<>();
        try { itunes(t, a, out); } catch (Throwable ignored) { }
        try { deezer(t, a, out); } catch (Throwable ignored) { }
        try { archive(t, a, album == null ? "" : album.trim(), out); } catch (Throwable ignored) { }
        return out;
    }

    private static void itunes(String t, String a, List<Hit> out) throws Exception {
        String body = get("https://itunes.apple.com/search?media=music&entity=song&limit=6&term=" + enc((a + " " + t).trim()));
        if (body == null) return;
        JSONArray r = new JSONObject(body).optJSONArray("results");
        if (r == null) return;
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            String u = o.optString("artworkUrl100", "");
            if (u.isEmpty()) continue;
            out.add(new Hit("iTunes", o.optString("trackName") + " · " + o.optString("artistName"),
                    u.replace("100x100bb", "200x200bb"), u.replace("100x100bb", "800x800bb")));
        }
    }

    private static void deezer(String t, String a, List<Hit> out) throws Exception {
        String body = get("https://api.deezer.com/search?limit=6&q=" + enc((a + " " + t).trim()));
        if (body == null) return;
        JSONArray r = new JSONObject(body).optJSONArray("data");
        if (r == null) return;
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            JSONObject al = o.optJSONObject("album");
            if (al == null) continue;
            String big = al.optString("cover_xl", al.optString("cover_big", ""));
            if (big.isEmpty()) continue;
            JSONObject ar = o.optJSONObject("artist");
            out.add(new Hit("Deezer", o.optString("title") + " · " + (ar == null ? "" : ar.optString("name")),
                    al.optString("cover_medium", big), big));
        }
    }

    private static void archive(String t, String a, String album, List<Hit> out) throws Exception {
        String q = !album.isEmpty() ? "release:\"" + album + "\"" : "recording:\"" + t + "\"";
        if (!a.isEmpty()) q += " AND artist:\"" + a + "\"";
        String body = get("https://musicbrainz.org/ws/2/release/?fmt=json&limit=4&query=" + enc(q));
        if (body == null) return;
        JSONArray r = new JSONObject(body).optJSONArray("releases");
        if (r == null) return;
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            String id = o.optString("id", "");
            if (id.isEmpty()) continue;
            out.add(new Hit("Cover Art Archive", o.optString("title"),
                    "https://coverartarchive.org/release/" + id + "/front-250",
                    "https://coverartarchive.org/release/" + id + "/front-500"));
        }
    }

    /** Downloads one picture. Call off the main thread. Null on failure. */
    public static byte[] download(String url) {
        if (Net.off()) return null;
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(20_000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "NKT Media Player 1.0 (Android)");
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    if (b.size() > 8 * 1024 * 1024) return null;
                }
                return b.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static final Object LOCK = new Object();

    /** Settings → ALBUM ART switch: a song that is playing and has no art gets some, quietly. */
    public static void auto(Context c, MediaInfo m) {
        final Context app = c.getApplicationContext();
        if (!Prefs.autoArt(app) || m == null || m.video || m.image || m.other) return;
        AUTO.execute(() -> ensure(app, m));
    }

    /**
     * Finds and saves art for a song that has none (blocking: call off the main thread).
     * True when the song now has a downloaded picture.
     */
    static boolean ensure(Context app, MediaInfo m) {
        if (!Prefs.autoArt(app) || Net.off() || m == null || m.video || m.image || m.other) return false;
        synchronized (LOCK) {
            try {
                if (ArtStore.has(app, m)) return true;
                if (ArtStore.recentlyMissed(app, m)) return false;
                if (Thumbs.fetch(app, m, 256) != null) return false;          // it already has art of its own
                List<Hit> hits = search(m.title, m.artist, m.album);
                for (Hit h : hits) {
                    byte[] data = download(h.fullUrl);
                    if (data != null && ArtStore.save(app, m, data)) return true;
                }
                if (!hits.isEmpty() || isOnline(app)) ArtStore.markMiss(app, m);
            } catch (Throwable ignored) { }
            return false;
        }
    }

    private static boolean isOnline(Context c) {
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            return cm != null && cm.getActiveNetwork() != null;
        } catch (Throwable t) {
            return true;
        }
    }

    private static String get(String url) {
        if (Net.off()) return null;
        byte[] d = download(url);
        return d == null ? null : new String(d, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
