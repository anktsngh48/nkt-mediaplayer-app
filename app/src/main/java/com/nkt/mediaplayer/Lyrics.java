package com.nkt.mediaplayer;

import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Song lyrics. Looks in order: saved copy → a .lrc file next to the song → lyrics inside the song file →
 * LRCLIB (free online lyrics, with timings). Everything found is saved so it works offline next time.
 */
public final class Lyrics {

    private Lyrics() { }

    public static class Line {
        public final long ms;          // -1 for lyrics without timing
        public final String text;
        Line(long ms, String text) { this.ms = ms; this.text = text; }
    }

    public static class Result {
        public final List<Line> lines = new ArrayList<>();
        public boolean synced;
        public boolean instrumental;
        public boolean isEmpty() { return lines.isEmpty() && !instrumental; }
    }

    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    public static Result parse(String text) {
        Result r = new Result();
        if (text == null) return r;
        if (text.trim().equals("[instrumental]")) { r.instrumental = true; return r; }
        boolean anyTime = false;
        for (String raw : text.split("\\r?\\n")) {
            Matcher m = TAG.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1)), sec = Long.parseLong(m.group(2));
                String f = m.group(3);
                long frac = f == null ? 0 : f.length() == 1 ? Long.parseLong(f) * 100 : f.length() == 2 ? Long.parseLong(f) * 10 : Long.parseLong(f);
                times.add(min * 60_000 + sec * 1000 + frac);
                end = m.end();
            }
            String words = raw.substring(end).trim();
            if (!times.isEmpty()) {
                anyTime = true;
                for (long t : times) r.lines.add(new Line(t, words));
            } else if (!raw.trim().startsWith("[") || !raw.trim().endsWith("]")) {   // skip [ar:…] style tags
                r.lines.add(new Line(-1, words));
            }
        }
        if (anyTime) {
            List<Line> timed = new ArrayList<>();
            for (Line l : r.lines) if (l.ms >= 0) timed.add(l);
            Collections.sort(timed, (a, b) -> Long.compare(a.ms, b.ms));
            r.lines.clear();
            r.lines.addAll(timed);
            r.synced = true;
        } else {
            // trim empty lines at the ends
            while (!r.lines.isEmpty() && r.lines.get(0).text.isEmpty()) r.lines.remove(0);
            while (!r.lines.isEmpty() && r.lines.get(r.lines.size() - 1).text.isEmpty()) r.lines.remove(r.lines.size() - 1);
        }
        return r;
    }

    // ── Saved copies & sync offset ──

    private static String id(MediaInfo m) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-1");
            byte[] h = d.digest(m.key().getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) b.append(String.format(Locale.US, "%02x", h[i]));
            return b.toString();
        } catch (Exception e) { return String.valueOf(m.key().hashCode()); }
    }

    private static File cacheFile(Context c, MediaInfo m) {
        File dir = new File(c.getFilesDir(), "lyrics");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, id(m) + ".lrc");
    }

    private static void save(Context c, MediaInfo m, String text) {
        try (OutputStream o = new FileOutputStream(cacheFile(c, m))) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) { }
    }

    public static void forget(Context c, MediaInfo m) {
        //noinspection ResultOfMethodCallIgnored
        cacheFile(c, m).delete();
    }

    /** Milliseconds; positive shows lines sooner. */
    public static long offset(Context c, MediaInfo m) { return Prefs.sp(c).getLong("lyr_off_" + id(m), 0); }
    public static void setOffset(Context c, MediaInfo m, long ms) { Prefs.sp(c).edit().putLong("lyr_off_" + id(m), ms).apply(); }

    // ── Finding lyrics (call off the main thread) ──

    public static Result find(Context c, MediaInfo m, String title, String artist, String album, long durMs) {
        File cached = cacheFile(c, m);
        if (cached.exists()) {
            Result r = parse(read(cached));
            if (!r.isEmpty()) return r;
        }
        String path = pathOf(c, m);
        if (!path.isEmpty()) {
            int dot = path.lastIndexOf('.');
            File lrc = new File((dot > 0 ? path.substring(0, dot) : path) + ".lrc");
            if (lrc.canRead()) {
                String t = read(lrc);
                Result r = parse(t);
                if (!r.isEmpty()) { save(c, m, t); return r; }
            }
        }
        String inside = embedded(c, m);
        if (inside != null) {
            Result r = parse(inside);
            if (!r.isEmpty()) { save(c, m, inside); return r; }
        }
        return online(c, m, title, artist, album, durMs);
    }

    /** LRCLIB only (also used by "Search again" with edited title/artist). */
    public static Result online(Context c, MediaInfo m, String title, String artist, String album, long durMs) {
        String t = cleanTitle(title), a = artist == null ? "" : artist.trim();
        if (a.isEmpty() && t.contains(" - ")) {                  // "Artist - Song" file names
            int i = t.indexOf(" - ");
            a = t.substring(0, i).trim();
            t = t.substring(i + 3).trim();
        }
        try {
            if (!a.isEmpty() && durMs > 0) {
                String q = "track_name=" + enc(t) + "&artist_name=" + enc(a) + "&duration=" + (durMs / 1000)
                        + (album == null || album.isEmpty() ? "" : "&album_name=" + enc(album));
                String body = get("https://lrclib.net/api/get?" + q);
                if (body != null) {
                    Result r = fromJson(c, m, new JSONObject(body));
                    if (r != null) return r;
                }
            }
            String q = a.isEmpty() ? "q=" + enc(t) : "track_name=" + enc(t) + "&artist_name=" + enc(a);
            Result r = fromSearch(c, m, get("https://lrclib.net/api/search?" + q), durMs);
            if (r == null && !a.isEmpty()) r = fromSearch(c, m, get("https://lrclib.net/api/search?q=" + enc(t + " " + a)), durMs);
            if (r == null && !a.isEmpty()) r = fromSearch(c, m, get("https://lrclib.net/api/search?q=" + enc(t)), durMs);
            if (r != null) return r;
        } catch (Exception ignored) { }
        return new Result();
    }

    private static Result fromSearch(Context c, MediaInfo m, String body, long durMs) throws Exception {
        if (body == null) return null;
        JSONArray arr = new JSONArray(body);
        JSONObject best = null;
        double bestScore = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            boolean synced = !o.optString("syncedLyrics", "").isEmpty() && !o.isNull("syncedLyrics");
            boolean plain = !o.optString("plainLyrics", "").isEmpty() && !o.isNull("plainLyrics");
            if (!synced && !plain) continue;
            double score = (synced ? 10 : 0);
            if (durMs > 0 && o.has("duration")) score -= Math.min(10, Math.abs(o.optDouble("duration") - durMs / 1000.0) / 3);
            if (score > bestScore) { bestScore = score; best = o; }
        }
        return best == null ? null : fromJson(c, m, best);
    }

    private static Result fromJson(Context c, MediaInfo m, JSONObject o) {
        if (o.optBoolean("instrumental")) {
            save(c, m, "[instrumental]");
            Result r = new Result();
            r.instrumental = true;
            return r;
        }
        String synced = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", "");
        String plain = o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "");
        String text = !synced.isEmpty() ? synced : plain;
        if (text.isEmpty()) return null;
        save(c, m, text);
        return parse(text);
    }

    // ── helpers ──

    static String cleanTitle(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\.(mp3|m4a|flac|wav|ogg|aac|opus)$", "");
        t = t.replaceAll("\\([^)]*\\)|\\[[^]]*]", " ");
        t = t.replaceAll("(?i)\\b(official|lyrics?|lyrical|video|audio|full song|hd|4k)\\b", " ");
        t = t.replaceAll("(?i)\\s(ft\\.?|feat\\.?|featuring)\\s.*$", " ");
        t = t.replaceAll("\\s+", " ").trim();
        return t.replaceAll("[\\s|-]+$", "").trim();
    }

    @SuppressWarnings("deprecation")
    private static String pathOf(Context c, MediaInfo m) {
        if (!m.path.isEmpty()) return m.path;
        if ("file".equals(m.uri.getScheme()) && m.uri.getPath() != null) return m.uri.getPath();
        if (!"content".equals(m.uri.getScheme())) return "";
        try (Cursor cur = c.getContentResolver().query(m.uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) return cur.getString(0);
        } catch (Exception ignored) { }
        return "";
    }

    /** Lyrics stored inside the song file (Android 8+). */
    private static String embedded(Context c, MediaInfo m) {
        if (Build.VERSION.SDK_INT < 26) return null;
        String scheme = m.uri.getScheme();
        if (!"content".equals(scheme) && !"file".equals(scheme)) return null;
        File tmp = null;
        try {
            String name = m.fileName.isEmpty() ? Ui.displayName(c, m.uri) : m.fileName;
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : ".mp3";
            tmp = new File(c.getCacheDir(), "lyr" + ext);
            try (InputStream in = c.getContentResolver().openInputStream(m.uri); OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) return null;
                byte[] b = new byte[64 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    total += n;
                    if (total > 80L * 1024 * 1024) return null;      // very large file: skip
                }
            }
            return TagIO.lyrics(tmp);
        } catch (Throwable t) {
            return null;
        } finally {
            if (tmp != null) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String get(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            con.setRequestProperty("User-Agent", "NKT Media Player 1.0 (Android)");
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream()) { return readAll(in); }
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String read(File f) {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); } catch (Exception e) { return ""; }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
