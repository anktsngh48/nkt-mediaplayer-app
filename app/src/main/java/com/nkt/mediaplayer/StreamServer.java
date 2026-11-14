package com.nkt.mediaplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stream: the small web server on the phone. It serves the listener page, the live state (a long poll), the song or video
 * with seeking, the album art, the lyrics, and (when allowed) the shared library and downloads.
 * Only what is in the queue, or the shared library when the host allows it, can be reached; nothing else on the phone.
 */
final class StreamServer {

    private final Context c;
    private final int port;
    private final String base;
    private ServerSocket ss;
    private volatile boolean stopped = false;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final java.util.Set<Socket> open = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final String sessionKey;
    private byte[] page;

    // PIN guesses: 5 wrong tries lock that device for a minute
    private final Map<String, long[]> tries = new ConcurrentHashMap<>();

    StreamServer(Context c, int port, String name) {
        this.c = c.getApplicationContext();
        this.port = port;
        this.base = "/" + name;
        byte[] k = new byte[12];
        new SecureRandom().nextBytes(k);
        StringBuilder b = new StringBuilder();
        for (byte x : k) b.append(String.format(Locale.US, "%02x", x));
        sessionKey = b.toString();
    }

    void start() throws IOException {
        ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        Thread t = new Thread(() -> {
            while (!stopped) {
                try {
                    final Socket s = ss.accept();
                    pool.execute(() -> handle(s));
                } catch (Throwable e) {
                    if (stopped) break;
                }
            }
        }, "nkt-stream-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        stopped = true;
        try { if (ss != null) ss.close(); } catch (Throwable ignored) { }
        for (Socket k : open) { try { k.close(); } catch (Throwable ignored) { } }     // a song being sent stops with the stream
        open.clear();
        pool.shutdownNow();
    }

    // ── Requests ─────────────────────────────────────────────────────────────

    private static final class Req {
        String method = "GET", path = "/", ip = "";
        final Map<String, String> q = new HashMap<>();
        final Map<String, String> h = new HashMap<>();
        String body = "";
        String q(String k) { String v = q.get(k); return v == null ? "" : v; }
    }

    private void handle(Socket s) {
        open.add(s);
        try {
            s.setSoTimeout(25_000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            Req r = parse(in, s);
            if (r != null) route(r, out);
            try { out.flush(); } catch (IOException ignored) { }
        } catch (Throwable ignored) {
        } finally {
            open.remove(s);
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) throw new IOException("line too long");
        }
        if (ch == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private Req parse(InputStream in, Socket s) throws IOException {
        String first = line(in);
        if (first == null || first.isEmpty()) return null;
        String[] parts = first.split(" ");
        if (parts.length < 2) return null;
        Req r = new Req();
        r.method = parts[0].toUpperCase(Locale.ROOT);
        r.ip = s.getInetAddress() == null ? "" : s.getInetAddress().getHostAddress();
        String target = parts[1];
        int qi = target.indexOf('?');
        String rawPath = qi < 0 ? target : target.substring(0, qi);
        try { r.path = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { r.path = rawPath; }
        if (qi >= 0) {
            for (String kv : target.substring(qi + 1).split("&")) {
                if (kv.isEmpty()) continue;
                int e = kv.indexOf('=');
                try {
                    String k = URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), "UTF-8");
                    String v = e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                    r.q.put(k, v);
                } catch (Exception ignored) { }
            }
        }
        int count = 0;
        String l;
        while ((l = line(in)) != null && !l.isEmpty() && count++ < 60) {
            int i = l.indexOf(':');
            if (i > 0) r.h.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
        }
        int len = 0;
        try { len = Integer.parseInt(r.h.getOrDefault("content-length", "0")); } catch (Exception ignored) { }
        if (len > 0 && len <= 65536) {
            byte[] b = new byte[len];
            int off = 0, n;
            while (off < len && (n = in.read(b, off, len - off)) > 0) off += n;
            r.body = new String(b, 0, off, StandardCharsets.UTF_8);
        }
        return r;
    }

    // ── Routes ───────────────────────────────────────────────────────────────

    private void route(Req r, OutputStream out) throws IOException {
        if (!"GET".equals(r.method) && !"POST".equals(r.method) && !"HEAD".equals(r.method)) { text(out, 405, "text/plain", "No"); return; }
        if (r.path.equals(base)) { redirect(out, base + "/"); return; }
        if (!r.path.startsWith(base) && r.path.length() >= base.length() && r.path.regionMatches(true, 0, base, 0, base.length())
                && (r.path.length() == base.length() || r.path.charAt(base.length()) == '/')) {
            redirect(out, base + r.path.substring(base.length()) + (r.path.length() == base.length() ? "/" : ""));     // "nkt" typed for "NKT"
            return;
        }
        if (!r.path.startsWith(base + "/")) { text(out, 404, "text/plain", "Not found"); return; }
        if (StreamHub.isBlocked(r.ip)) { text(out, 403, "text/plain", "Blocked by the host"); return; }
        String rel = r.path.substring(base.length() + 1);

        if (rel.isEmpty() || rel.equals("index.html")) { servePage(out); return; }
        if (rel.equals("api/time")) { text(out, 200, "application/json", "{\"t\":" + System.currentTimeMillis() + "}"); return; }
        if (rel.equals("api/info")) {
            text(out, 200, "application/json", "{\"pin\":" + StreamPrefs.pinOn(c) + ",\"host\":" + JSONObject.quote(StreamHub.deviceName()) + "}");
            return;
        }
        if (rel.equals("api/pin")) { pin(r, out); return; }
        if (rel.equals("font/orbitron_700.ttf") || rel.equals("font/orbitron_900.ttf")) {
            try (InputStream in = c.getAssets().open("fonts/" + rel.substring(5))) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                byte[] d = b.toByteArray();
                head(out, 200, "font/ttf", d.length, "Cache-Control: public, max-age=86400\r\n");
                if (!"HEAD".equals(r.method)) out.write(d);
            } catch (IOException e) { text(out, 404, "text/plain", "Not found"); }
            return;
        }

        if (StreamPrefs.pinOn(c) && !sessionKey.equals(cookie(r, "nkt"))) { text(out, 401, "application/json", "{\"err\":\"pin\"}"); return; }
        String cid = r.q("c");
        if (StreamHub.kickedOrBanned(cid, r.ip)) { text(out, 403, "application/json", "{\"err\":\"kicked\"}"); return; }

        if (rel.equals("api/state")) { state(r, out, cid); return; }
        if (rel.equals("api/hello")) { hello(r, out); return; }
        if (rel.equals("api/viz")) {
            long after = -1;
            try { after = Long.parseLong(r.q("after")); } catch (Exception ignored) { }
            text(out, 200, "application/json", StreamHub.vizJson(after));
            return;
        }
        if (rel.equals("api/lyrics")) { text(out, 200, "application/json", StreamHub.lyricsFor(r.q("id"))); return; }
        if (rel.equals("api/library")) {
            if (!StreamPrefs.libraryOn(c) || StreamHub.draining) { text(out, 403, "application/json", "{\"err\":\"off\"}"); return; }
            text(out, 200, "application/json", StreamHub.library());
            return;
        }
        if (rel.startsWith("art/")) {
            byte[] b = StreamHub.artJpeg(rel.substring(4));
            if (b == null) { text(out, 404, "text/plain", "No picture"); return; }
            head(out, 200, "image/jpeg", b.length, "Cache-Control: private, max-age=60\r\n");
            if (!"HEAD".equals(r.method)) out.write(b);
            return;
        }
        if (rel.startsWith("ph/")) {
            byte[] b = StreamHub.photoJpeg(rel.substring(3));
            if (b == null) { text(out, 404, "text/plain", "No photo"); return; }
            head(out, 200, "image/jpeg", b.length, "Cache-Control: private, max-age=300\r\n");
            if (!"HEAD".equals(r.method)) out.write(b);
            return;
        }
        if (rel.startsWith("m/")) { media(r, out, rel.substring(2), false); return; }
        if (rel.startsWith("dl/")) { media(r, out, rel.substring(3), true); return; }
        text(out, 404, "text/plain", "Not found");
    }

    private void servePage(OutputStream out) throws IOException {
        if (page == null) {
            try (InputStream in = c.getAssets().open("stream/index.html")) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                page = b.toByteArray();
            }
        }
        head(out, 200, "text/html; charset=utf-8", page.length, "Cache-Control: no-store\r\n");
        out.write(page);
    }

    private void state(Req r, OutputStream out, String cid) throws IOException {
        StreamHub.Listener l = StreamHub.touch(cid, r.ip, r.h.get("user-agent"));
        if (l == null) {
            if (StreamHub.kickedOrBanned(cid, r.ip)) text(out, 403, "application/json", "{\"err\":\"kicked\"}");
            else text(out, 503, "application/json", StreamHub.draining ? "{\"err\":\"over\"}" : "{\"err\":\"full\"}");
            return;
        }
        int have = -1;
        try { have = Integer.parseInt(r.q("v")); } catch (Exception ignored) { }
        if (have >= 0) StreamHub.waitPast(have, 20_000);
        StreamHub.touch(cid, r.ip, r.h.get("user-agent"));
        text(out, 200, "application/json", StreamHub.stateJson());
    }

    private void hello(Req r, OutputStream out) throws IOException {
        try {
            JSONObject o = new JSONObject(r.body);
            String cid = o.optString("c", r.q("c"));
            StreamHub.Listener l = StreamHub.touch(cid, r.ip, r.h.get("user-agent"));
            if (l == null) {
                if (StreamHub.kickedOrBanned(cid, r.ip)) text(out, 403, "application/json", "{\"err\":\"kicked\"}");
                else text(out, 503, "application/json", "{\"err\":\"full\"}");
                return;
            }
            l.mode = "own".equals(o.optString("mode")) ? "own" : "live";
            l.own = clip(o.optString("own"), 60);
            l.view = "viz".equals(o.optString("view")) ? "viz" : o.optString("view").length() > 8 ? "" : o.optString("view");
            l.sync = o.optInt("sync", 0);
            text(out, 200, "application/json", "{\"ok\":1}");
        } catch (Exception e) {
            text(out, 400, "application/json", "{\"err\":\"bad\"}");
        }
    }

    private static String clip(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }

    private void pin(Req r, OutputStream out) throws IOException {
        long now = System.currentTimeMillis();
        long[] t = tries.computeIfAbsent(r.ip, k -> new long[]{0, 0});
        if (t[1] > now) { text(out, 429, "application/json", "{\"err\":\"wait\"}"); return; }
        String given = "";
        for (String kv : r.body.split("&")) if (kv.startsWith("pin=")) given = kv.substring(4);
        if (StreamPrefs.pinOn(c) && !StreamPrefs.pin(c).equals(given)) {
            if (++t[0] >= 5) { t[0] = 0; t[1] = now + 60_000; }
            text(out, 403, "application/json", "{\"err\":\"wrong\"}");
            return;
        }
        t[0] = 0;
        head(out, 200, "application/json", 8, "Set-Cookie: nkt=" + sessionKey + "; Path=" + base + "; HttpOnly; SameSite=Lax\r\n");
        out.write("{\"ok\":1}".getBytes(StandardCharsets.UTF_8));
    }

    private static String cookie(Req r, String name) {
        String h = r.h.get("cookie");
        if (h == null) return "";
        for (String part : h.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        }
        return "";
    }

    // ── Songs and videos ─────────────────────────────────────────────────────

    private void media(Req r, OutputStream out, String id, boolean download) throws IOException {
        MediaInfo m = StreamHub.resolve(id);
        if (m == null) { text(out, 404, "text/plain", "Not available"); return; }
        if (download && (!StreamPrefs.allowDownloads(c) || StreamHub.draining)) { text(out, 403, "text/plain", "Downloads are off"); return; }
        Src src = Src.of(c, m);
        if (src == null) { text(out, 404, "text/plain", "Not available"); return; }
        final boolean viaLib = StreamHub.viaLibraryOnly(id);
        long len = src.length;
        long from = 0, to = len - 1;
        boolean partial = false;
        String range = r.h.get("range");
        if (range != null && range.startsWith("bytes=") && len > 0) {
            String spec = range.substring(6).split(",")[0].trim();
            int dash = spec.indexOf('-');
            try {
                if (dash == 0) { from = Math.max(0, len - Long.parseLong(spec.substring(1))); }
                else {
                    from = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1) to = Math.min(len - 1, Long.parseLong(spec.substring(dash + 1)));
                }
                partial = true;
            } catch (Exception e) { from = 0; to = len - 1; partial = false; }
            if (from >= len) {
                head(out, 416, "text/plain", 0, "Content-Range: bytes */" + len + "\r\n");
                return;
            }
        }
        StringBuilder extra = new StringBuilder("Accept-Ranges: bytes\r\n");
        if (download) {
            String fn = src.name.isEmpty() ? "file" : src.name;
            String enc;
            try { enc = URLEncoder.encode(fn, "UTF-8").replace("+", "%20"); } catch (Exception e) { enc = "file"; }
            extra.append("Content-Disposition: attachment; filename=\"").append(fn.replaceAll("[\"\\r\\n]", "_")).append("\"; filename*=UTF-8''").append(enc).append("\r\n");
        }
        long send = len > 0 ? to - from + 1 : -1;
        if (partial) extra.append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(len).append("\r\n");
        head(out, partial ? 206 : 200, src.mime, send, extra.toString());
        if ("HEAD".equals(r.method)) return;
        try (InputStream in = src.open(from)) {
            byte[] buf = new byte[64 * 1024];
            long left = send;
            while (left != 0) {
                if (viaLib && !StreamPrefs.libraryOn(c)) break;                 // the host switched the library off: stop sending now
                if (download && !StreamPrefs.allowDownloads(c)) break;
                int n = in.read(buf, 0, left < 0 ? buf.length : (int) Math.min(buf.length, left));
                if (n <= 0) break;
                out.write(buf, 0, n);
                if (left > 0) left -= n;
            }
        } catch (IOException ignored) { }       // the listener moved on or went away
    }

    /** A file on this phone, opened at any point. */
    static final class Src {
        long length = -1;
        String mime = "application/octet-stream", name = "";
        private File file;
        private Uri uri;
        private ContentResolver cr;

        static Src of(Context c, MediaInfo m) {
            try {
                Src s = new Src();
                String sc = m.uri.getScheme();
                if ("file".equals(sc) && m.uri.getPath() != null) {
                    s.file = new File(m.uri.getPath());
                    if (!s.file.isFile()) return null;
                    s.length = s.file.length();
                    s.name = s.file.getName();
                } else if ("content".equals(sc)) {
                    s.uri = m.uri;
                    s.cr = c.getContentResolver();
                    try (AssetFileDescriptor afd = s.cr.openAssetFileDescriptor(m.uri, "r")) {
                        if (afd == null) return null;
                        s.length = afd.getLength();
                    }
                    try (android.database.Cursor cur = s.cr.query(m.uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                        if (cur != null && cur.moveToFirst()) {
                            if (!cur.isNull(0)) s.name = cur.getString(0);
                            if (s.length < 0 && !cur.isNull(1)) s.length = cur.getLong(1);
                        }
                    } catch (Exception ignored) { }
                    if (s.name.isEmpty()) s.name = m.fileName;
                } else return null;
                s.mime = mimeFor(s.name, m.video, s.cr, m.uri);
                return s;
            } catch (Throwable t) {
                return null;
            }
        }

        InputStream open(long start) throws IOException {
            if (file != null) {
                FileInputStream in = new FileInputStream(file);
                if (start > 0) in.getChannel().position(start);
                return in;
            }
            AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r");
            if (afd == null) throw new IOException("closed");
            FileInputStream in = afd.createInputStream();
            long pos = afd.getStartOffset() + start;
            if (pos > 0) in.getChannel().position(pos);
            return in;
        }

        private static String mimeFor(String name, boolean video, ContentResolver cr, Uri uri) {
            String e = MediaInfo.ext(name);
            switch (e) {
                case "mp3": return "audio/mpeg";
                case "m4a": return "audio/mp4";
                case "aac": return "audio/aac";
                case "flac": return "audio/flac";
                case "ogg": case "oga": case "opus": return "audio/ogg";
                case "wav": return "audio/wav";
                case "mka": return "audio/x-matroska";
                case "mp4": case "m4v": return "video/mp4";
                case "mkv": return "video/x-matroska";
                case "webm": return "video/webm";
                case "3gp": return "video/3gpp";
                case "jpg": case "jpeg": return "image/jpeg";
                case "png": return "image/png";
                case "webp": return "image/webp";
                case "gif": return "image/gif";
                case "heic": case "heif": return "image/heic";
                case "bmp": return "image/bmp";
                case "mov": return "video/quicktime";
                default: break;
            }
            try {
                if (cr != null) { String t = cr.getType(uri); if (t != null && !t.isEmpty()) return t; }
            } catch (Exception ignored) { }
            return video ? "video/mp4" : "audio/mpeg";
        }
    }

    // ── Replies ──────────────────────────────────────────────────────────────

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            default: return "OK";
        }
    }

    private static void head(OutputStream out, int code, String type, long len, String extra) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        b.append("Content-Type: ").append(type).append("\r\n");
        if (len >= 0) b.append("Content-Length: ").append(len).append("\r\n");
        b.append("Connection: close\r\n");
        if (extra != null) b.append(extra);
        b.append("\r\n");
        out.write(b.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void text(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        head(out, code, type.startsWith("text/") || type.startsWith("application/json") ? type + "; charset=utf-8" : type, b.length, "Cache-Control: no-store\r\n");
        out.write(b);
    }

    private static void redirect(OutputStream out, String to) throws IOException {
        head(out, 302, "text/plain", 0, "Location: " + to + "\r\n");
    }
}
