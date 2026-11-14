package com.nkt.mediaplayer;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stream: which songs and videos listeners may browse and play on their own. Either everything in Audio and Videos,
 * or only the files and folders ticked on the "Shared with listeners" screen.
 */
final class StreamShare {

    private StreamShare() { }

    private static final int MAX_FILES = 20000;
    private static List<MediaInfo> cache;
    private static long cacheAt = 0;
    private static String cacheKey = "";

    static void invalidate() { synchronized (StreamShare.class) { cache = null; } }

    /** The shared songs and videos. Runs on a background thread. */
    static List<MediaInfo> items(Context c) {
        String key = StreamPrefs.shareMode(c) + "|" + StreamPrefs.picks(c) + "|" + Prefs.showHidden(c);
        synchronized (StreamShare.class) {
            if (cache != null && key.equals(cacheKey) && System.currentTimeMillis() - cacheAt < 60_000) return cache;
        }
        List<MediaInfo> out = new ArrayList<>();
        boolean hidden = Prefs.showHidden(c);
        if (StreamPrefs.MODE_ALL.equals(StreamPrefs.shareMode(c))) {
            LibraryCache.Snapshot s = LibraryCache.load(c);
            List<MediaInfo> a, v;
            if (s != null) { a = s.audio; v = s.video; }
            else { a = MediaLibrary.audio(c); v = MediaLibrary.video(c); }
            for (MediaInfo m : a) if (hidden || !m.hidden) out.add(m);
            for (MediaInfo m : v) if (hidden || !m.hidden) out.add(m);
        } else {
            Set<String> seen = new HashSet<>();
            for (String p : StreamPrefs.picks(c)) walk(new File(p), hidden, out, seen, 0);
            Collections.sort(out, (x, y) -> x.title.compareToIgnoreCase(y.title));
        }
        synchronized (StreamShare.class) { cache = out; cacheAt = System.currentTimeMillis(); cacheKey = key; }
        return out;
    }

    /** How many songs and videos "Everything in Audio and Videos" covers. Runs on a background thread. */
    static int countAll(Context c) {
        boolean hidden = Prefs.showHidden(c);
        LibraryCache.Snapshot s = LibraryCache.load(c);
        List<MediaInfo> a, v;
        if (s != null) { a = s.audio; v = s.video; }
        else { a = MediaLibrary.audio(c); v = MediaLibrary.video(c); }
        int n = 0;
        for (MediaInfo m : a) if (hidden || !m.hidden) n++;
        for (MediaInfo m : v) if (hidden || !m.hidden) n++;
        return n;
    }

    private static void walk(File f, boolean hidden, List<MediaInfo> out, Set<String> seen, int depth) {
        if (out.size() >= MAX_FILES || depth > 12 || !f.exists()) return;
        String name = f.getName();
        if (depth > 0 && name.startsWith(".") && !hidden) return;
        if (f.isDirectory()) {
            if (isHiddenSystem(f)) return;
            File[] kids = f.listFiles();
            if (kids == null) return;
            for (File k : kids) walk(k, hidden, out, seen, depth + 1);
        } else {
            int k = MediaInfo.kindOf(name);
            if (k != MediaInfo.AUDIO && k != MediaInfo.VIDEO) return;
            String path = f.getAbsolutePath();
            if (!seen.add(path)) return;
            out.add(MediaInfo.fromFile(f));
        }
    }

    /** The phone's own Android folder (apps' private files, including the Recycle bin) is never offered. */
    @SuppressWarnings("deprecation")
    static boolean isHiddenSystem(File f) {
        File root = Environment.getExternalStorageDirectory();
        return f.getParentFile() != null && f.getParentFile().equals(root) && f.getName().equals("Android");
    }

    // ── Ticking in the browser ──

    /** 0 not shared, 1 some of it, 2 shared. */
    static int state(Set<String> picks, String path) {
        if (covering(picks, path) != null) return 2;
        String pre = path.endsWith("/") ? path : path + "/";
        for (String p : picks) if (p.startsWith(pre)) return 1;
        return 0;
    }

    private static String covering(Set<String> picks, String path) {
        for (String p : picks) if (path.equals(p) || path.startsWith(p.endsWith("/") ? p : p + "/")) return p;
        return null;
    }

    /** Ticks or unticks a file or folder; unticking inside a ticked folder keeps the rest of it shared. */
    static void toggle(Context c, File f) {
        Set<String> p = StreamPrefs.picks(c);
        String path = f.getAbsolutePath();
        String anc = covering(p, path);
        if (anc == null) {
            String pre = path + "/";
            java.util.Iterator<String> it = p.iterator();
            while (it.hasNext()) if (it.next().startsWith(pre)) it.remove();
            p.add(path);
        } else if (anc.equals(path)) {
            p.remove(path);
        } else {
            p.remove(anc);
            explode(p, new File(anc), path);
        }
        StreamPrefs.savePicks(c, p);
        invalidate();
    }

    private static void explode(Set<String> p, File dir, String target) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String kp = k.getAbsolutePath();
            if (kp.equals(target)) continue;
            if (target.startsWith(kp + "/")) explode(p, k, target); else p.add(kp);
        }
    }
}
