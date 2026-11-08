package com.nkt.mediaplayer;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone's songs, videos and photos. On start the last saved list shows at once (fast start),
 * then MediaStore is read again in the background and the lists are swapped in one go.
 */
public final class Library {
    private Library() {}

    public static volatile List<MediaInfo> songs = new ArrayList<>(), videos = new ArrayList<>(), images = new ArrayList<>();
    public static volatile boolean loaded, refreshing;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final List<Runnable> listeners = new ArrayList<>();

    public static void listen(Runnable r) { synchronized (listeners) { if (!listeners.contains(r)) listeners.add(r); } }
    public static void unlisten(Runnable r) { synchronized (listeners) { listeners.remove(r); } }

    private static void notifyAll0() {
        Ui.post(() -> {
            List<Runnable> copy;
            synchronized (listeners) { copy = new ArrayList<>(listeners); }
            for (Runnable r : copy) r.run();
        });
    }

    /** Show the saved list first, then refresh from the phone in the background. */
    public static void start(Context ctx) {
        Context c = ctx.getApplicationContext();
        IO.execute(() -> {
            if (!loaded) {
                readCache(c);
                loaded = true;
                notifyAll0();
            }
        });
        refresh(c);
    }

    public static void refresh(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (refreshing) return;
        refreshing = true;
        notifyAll0();
        IO.execute(() -> {
            try {
                List<MediaInfo> a = queryAudio(c), v = queryVideo(c), i = queryImages(c);
                boolean thumbs = Store.bool(c, "show_thumbs", false), hidden = Store.bool(c, "show_hidden", false);
                if (!thumbs) { strip(a); strip(v); strip(i); }
                if ((hidden || thumbs) && Perms.allFiles(c)) HiddenScan.scan(c, a, v, i, hidden, thumbs);
                songs = a; videos = v; images = i;
                loaded = true;
                writeCache(c);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                refreshing = false;
                notifyAll0();
            }
        });
    }

    private static void strip(List<MediaInfo> l) {
        for (int k = l.size() - 1; k >= 0; k--) if (l.get(k).path.toLowerCase().contains("/.thumbnails")) l.remove(k);
    }

    public static void remove(String uri) {
        List<MediaInfo> a = new ArrayList<>(songs), v = new ArrayList<>(videos), i = new ArrayList<>(images);
        a.removeIf(m -> m.uri.equals(uri));
        v.removeIf(m -> m.uri.equals(uri));
        i.removeIf(m -> m.uri.equals(uri));
        songs = a; videos = v; images = i;
        notifyAll0();
    }

    public static MediaInfo find(String uri) {
        for (List<MediaInfo> l : new List[]{songs, videos, images}) for (MediaInfo m : l) if (m.uri.equals(uri)) return m;
        return null;
    }

    // ---------------- cache ----------------
    private static File cacheFile(Context c) { return new File(c.getFilesDir(), "library.json"); }

    private static void readCache(Context c) {
        try (FileInputStream in = new FileInputStream(cacheFile(c))) {
            byte[] b = new byte[(int) cacheFile(c).length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONArray all = new JSONArray(new String(b, StandardCharsets.UTF_8));
            List<MediaInfo> a = new ArrayList<>(), v = new ArrayList<>(), i = new ArrayList<>();
            for (int k = 0; k < all.length(); k++) {
                MediaInfo m = MediaInfo.fromJson(all.getJSONObject(k));
                (m.isAudio() ? a : m.isVideo() ? v : i).add(m);
            }
            if (songs.isEmpty() && videos.isEmpty() && images.isEmpty()) { songs = a; videos = v; images = i; }
        } catch (Exception ignored) {}
    }

    private static void writeCache(Context c) {
        try {
            JSONArray all = new JSONArray();
            for (MediaInfo m : songs) all.put(m.toJson());
            for (MediaInfo m : videos) all.put(m.toJson());
            for (MediaInfo m : images) all.put(m.toJson());
            File tmp = new File(c.getFilesDir(), "library.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(all.toString().getBytes(StandardCharsets.UTF_8)); }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(cacheFile(c));
        } catch (Exception ignored) {}
    }

    // ---------------- MediaStore ----------------
    @SuppressWarnings("deprecation")
    private static List<MediaInfo> queryAudio(Context c) {
        List<MediaInfo> out = new ArrayList<>();
        if (!Perms.media(c)) return out;
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        List<String> cols = new ArrayList<>();
        String[] basic = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.YEAR};
        for (String s : basic) cols.add(s);
        if (Build.VERSION.SDK_INT >= 30) cols.add(MediaStore.Audio.Media.GENRE);
        try (Cursor q = c.getContentResolver().query(base, cols.toArray(new String[0]),
                MediaStore.Audio.Media.DURATION + ">=10000", null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE")) {
            if (q == null) return out;
            while (q.moveToNext()) {
                MediaInfo m = new MediaInfo();
                m.kind = MediaInfo.AUDIO;
                m.id = q.getLong(0);
                m.title = nz(q.getString(1));
                m.artist = nz(q.getString(2));
                m.album = nz(q.getString(3));
                m.albumId = q.getLong(4);
                m.duration = q.getLong(5);
                m.size = q.getLong(6);
                m.date = q.getLong(7) * 1000L;
                m.path = nz(q.getString(8));
                m.name = nz(q.getString(9));
                m.mime = nz(q.getString(10));
                m.year = q.getInt(11);
                if (Build.VERSION.SDK_INT >= 30) m.genre = nz(q.getString(12));
                m.folder = parentName(m.path);
                m.uri = ContentUris.withAppendedId(base, m.id).toString();
                out.add(m);
            }
        } catch (Exception e) { e.printStackTrace(); }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static List<MediaInfo> queryVideo(Context c) {
        List<MediaInfo> out = new ArrayList<>();
        if (!Perms.media(c)) return out;
        Uri base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DATA, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME};
        try (Cursor q = c.getContentResolver().query(base, cols, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (q == null) return out;
            while (q.moveToNext()) {
                MediaInfo m = new MediaInfo();
                m.kind = MediaInfo.VIDEO;
                m.id = q.getLong(0);
                m.name = nz(q.getString(6));
                m.title = nz(q.getString(1));
                if (m.title.isEmpty()) m.title = m.name;
                m.duration = q.getLong(2);
                m.size = q.getLong(3);
                m.date = q.getLong(4) * 1000L;
                m.path = nz(q.getString(5));
                m.mime = nz(q.getString(7));
                m.folder = nz(q.getString(8));
                if (m.folder.isEmpty()) m.folder = parentName(m.path);
                m.uri = ContentUris.withAppendedId(base, m.id).toString();
                out.add(m);
            }
        } catch (Exception e) { e.printStackTrace(); }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static List<MediaInfo> queryImages(Context c) {
        List<MediaInfo> out = new ArrayList<>();
        if (!Perms.media(c)) return out;
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.BUCKET_DISPLAY_NAME};
        try (Cursor q = c.getContentResolver().query(base, cols, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (q == null) return out;
            while (q.moveToNext()) {
                MediaInfo m = new MediaInfo();
                m.kind = MediaInfo.IMAGE;
                m.id = q.getLong(0);
                long taken = q.getLong(1);
                m.date = taken > 0 ? taken : q.getLong(2) * 1000L;
                m.size = q.getLong(3);
                m.path = nz(q.getString(4));
                m.name = nz(q.getString(5));
                m.title = m.name;
                m.mime = nz(q.getString(6));
                m.folder = nz(q.getString(7));
                if (m.folder.isEmpty()) m.folder = parentName(m.path);
                m.uri = ContentUris.withAppendedId(base, m.id).toString();
                out.add(m);
            }
        } catch (Exception e) { e.printStackTrace(); }
        return out;
    }

    static String nz(String s) { return s == null ? "" : s; }

    static String parentName(String path) {
        if (path == null || path.isEmpty()) return "";
        File p = new File(path).getParentFile();
        return p == null ? "" : p.getName();
    }

    /** Walks storage for files Android hides (dot folders, .nomedia, .thumbnails). */
    static final class HiddenScan {
        static void scan(Context c, List<MediaInfo> a, List<MediaInfo> v, List<MediaInfo> i, boolean hidden, boolean thumbs) {
            Set<String> known = new HashSet<>();
            for (List<MediaInfo> l : new List[]{a, v, i}) for (MediaInfo m : l) known.add(m.path);
            File root = Environment.getExternalStorageDirectory();
            walk(root, 0, false, false, hidden, thumbs, known, a, v, i, root);
        }

        private static void walk(File dir, int depth, boolean inHidden, boolean inThumbs, boolean hidden, boolean thumbs,
                                 Set<String> known, List<MediaInfo> a, List<MediaInfo> v, List<MediaInfo> i, File root) {
            if (depth > 12) return;
            File[] list = dir.listFiles();
            if (list == null) return;
            boolean nomedia = new File(dir, ".nomedia").exists();
            for (File f : list) {
                if (f.isDirectory()) {
                    if (depth == 0 && f.getName().equals("Android")) continue;
                    boolean isThumbs = f.getName().equalsIgnoreCase(".thumbnails");
                    boolean h = inHidden || (!isThumbs && (f.getName().startsWith(".") || nomedia));
                    walk(f, depth + 1, h, inThumbs || isThumbs, hidden, thumbs, known, a, v, i, root);
                } else {
                    boolean fileHidden = inHidden || nomedia || f.getName().startsWith(".");
                    boolean take = inThumbs ? thumbs : (fileHidden && hidden);
                    if (!take || known.contains(f.getAbsolutePath())) continue;
                    int k = MediaInfo.kindOf(f.getName());
                    if (k < 0) continue;
                    MediaInfo m = MediaInfo.fromFile(f);
                    m.hidden = true;
                    if (k != MediaInfo.IMAGE) {
                        try {
                            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                            r.setDataSource(f.getAbsolutePath());
                            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                            m.duration = d == null ? 0 : Long.parseLong(d);
                            String t = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
                            if (t != null && !t.isEmpty()) m.title = t;
                            m.artist = nz(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST));
                            m.album = nz(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM));
                            r.release();
                        } catch (Exception ignored) {}
                        if (k == MediaInfo.AUDIO && m.duration < 10000) continue;
                    }
                    (k == MediaInfo.AUDIO ? a : k == MediaInfo.VIDEO ? v : i).add(m);
                }
            }
        }
    }
}
