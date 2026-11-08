package com.nkt.mediaplayer;

import android.content.Context;
import android.media.MediaScannerConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Recycle bin. Deleted files are moved (not copied — instant, even for big videos) into this app's private
 * folder on the same storage: Android/data/com.nkt.mediaplayer/files/.trash. On Android 11+ no other app can see
 * or open them there. They are kept 30 days, then removed for good; uninstalling the app removes them too.
 */
public final class Trash {

    private Trash() { }

    public static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;

    public static final class Entry {
        public String id, name, original, stored;
        public long deletedAt, size;
        public boolean dir;

        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("name", name).put("orig", original).put("stored", stored)
                    .put("at", deletedAt).put("size", size).put("dir", dir);
        }

        static Entry from(JSONObject o) {
            Entry e = new Entry();
            e.id = o.optString("id");
            e.name = o.optString("name");
            e.original = o.optString("orig");
            e.stored = o.optString("stored");
            e.deletedAt = o.optLong("at");
            e.size = o.optLong("size");
            e.dir = o.optBoolean("dir");
            return e;
        }

        public long daysLeft() {
            long left = deletedAt + KEEP_MS - System.currentTimeMillis();
            return Math.max(0, (left + 86_399_999L) / 86_400_000L);
        }

        /** The file as it sits in the bin, for thumbnails and opening. */
        public MediaInfo media() {
            MediaInfo m = MediaInfo.fromFile(new File(stored));
            m.title = MediaInfo.stripExt(name);
            m.fileName = name;
            if (dir) m.other = true;
            return m;
        }
    }

    private static final Object LOCK = new Object();

    private static File index(Context c) { return new File(c.getFilesDir(), "trash.json"); }

    public static List<Entry> list(Context c) {
        synchronized (LOCK) {
            List<Entry> out = new ArrayList<>();
            try {
                File f = index(c);
                if (!f.exists()) return out;
                JSONArray a = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                for (int i = 0; i < a.length(); i++) {
                    Entry e = Entry.from(a.getJSONObject(i));
                    if (new File(e.stored).exists()) out.add(e);
                }
            } catch (Exception ignored) { }
            return out;
        }
    }

    private static void write(Context c, List<Entry> list) {
        synchronized (LOCK) {
            try {
                JSONArray a = new JSONArray();
                for (Entry e : list) a.put(e.json());
                File tmp = new File(c.getFilesDir(), "trash.json.tmp");
                java.nio.file.Files.write(tmp.toPath(), a.toString().getBytes("UTF-8"));
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(index(c));
            } catch (Exception ignored) { }
        }
    }

    /** The bin folder on the same storage as path (phone, SD card or USB), or null. */
    static File binFor(Context c, String path) {
        File[] dirs = c.getExternalFilesDirs(null);
        for (File root : FolderPicker.roots(c)) {
            if (!path.startsWith(root.getAbsolutePath() + "/")) continue;
            for (File d : dirs) {
                if (d != null && d.getAbsolutePath().startsWith(root.getAbsolutePath() + "/")) {
                    File bin = new File(d, ".trash");
                    //noinspection ResultOfMethodCallIgnored
                    bin.mkdirs();
                    try { new File(bin, ".nomedia").createNewFile(); } catch (Exception ignored) { }
                    return bin;
                }
            }
        }
        return null;
    }

    /** Moves one file or folder into the bin. Background thread. Returns its bin entry, or null. */
    public static Entry put(Context c, File f) {
        if (f == null || !f.exists()) return null;
        File bin = binFor(c, f.getAbsolutePath());
        if (bin == null) return null;
        Entry e = new Entry();
        e.id = UUID.randomUUID().toString().substring(0, 12);
        e.name = f.getName();
        e.original = f.getAbsolutePath();
        e.dir = f.isDirectory();
        e.size = e.dir ? FileJobs.sizeOf(f) : f.length();
        e.deletedAt = System.currentTimeMillis();
        String ext = MediaInfo.ext(e.name);
        File to = new File(bin, e.id + (e.dir || ext.isEmpty() ? "" : "." + ext));
        List<String> old = new ArrayList<>();
        collect(f, old, 0);                                    // the paths Android's library knows, before they move
        if (!FileJobs.move(f, to)) return null;          // never lost: on failure the file stays where it was
        e.stored = to.getAbsolutePath();
        synchronized (LOCK) {
            List<Entry> l = list(c);
            l.add(0, e);
            write(c, l);
        }
        forgetFromLibrary(c, old);
        return e;
    }

    public static Entry find(Context c, String id) {
        for (Entry e : list(c)) if (e.id.equals(id)) return e;
        return null;
    }

    /** Back where it was (a number is added if that name is taken again). Returns the restored file or null. */
    public static File restore(Context c, Entry e) {
        File from = new File(e.stored), to = new File(e.original);
        //noinspection ResultOfMethodCallIgnored
        to.getParentFile().mkdirs();
        if (to.exists()) to = FileJobs.unique(to.getParentFile(), e.name);
        if (!FileJobs.move(from, to)) return null;
        remove(c, e.id);
        scanTree(c, to);
        return to;
    }

    public static boolean deleteForever(Context c, Entry e) {
        File f = new File(e.stored);
        boolean ok = !f.exists() || (f.isDirectory() ? FileOps.deleteTree(f) : f.delete());
        if (ok) remove(c, e.id);
        return ok;
    }

    private static void remove(Context c, String id) {
        synchronized (LOCK) {
            List<Entry> l = list(c);
            for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(id)) l.remove(i);
            write(c, l);
        }
    }

    /** Removes what has been in the bin for more than 30 days. Background thread. */
    public static void purgeOld(Context c) {
        for (Entry e : list(c)) if (e.daysLeft() <= 0) deleteForever(c, e);
    }

    public static long totalSize(Context c) {
        long t = 0;
        for (Entry e : list(c)) t += e.size;
        return t;
    }

    /** Tells Android the files are gone from their old place, so other apps' libraries drop them too. */
    private static void forgetFromLibrary(Context c, List<String> paths) {
        if (paths.isEmpty()) return;
        try { MediaScannerConnection.scanFile(c.getApplicationContext(), paths.toArray(new String[0]), null, null); } catch (Throwable ignored) { }
    }

    static void scanTree(Context c, File f) {
        List<String> paths = new ArrayList<>();
        collect(f, paths, 0);
        if (paths.isEmpty()) return;
        try { MediaScannerConnection.scanFile(c.getApplicationContext(), paths.toArray(new String[0]), null, null); } catch (Throwable ignored) { }
    }

    private static void collect(File f, List<String> out, int depth) {
        if (out.size() > 5000 || depth > 12) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) collect(k, out, depth + 1);
        } else out.add(f.getAbsolutePath());
    }
}
