package com.nkt.mediaplayer;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds media inside hidden folders (names starting with a dot) and folders marked ".nomedia",
 * which Android's media index leaves out. Needs "All files access" on Android 11+.
 */
public final class HiddenScanner {

    private HiddenScanner() { }

    private static final Set<String> AUDIO = new HashSet<>(java.util.Arrays.asList(
            "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "wma", "mka"));
    private static final Set<String> VIDEO = new HashSet<>(java.util.Arrays.asList(
            "mp4", "mkv", "webm", "3gp", "avi", "mov", "ts", "m4v", "flv", "wmv"));
    private static final Set<String> IMAGE = new HashSet<>(java.util.Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp"));

    public static class Result {
        public final List<MediaInfo> audio = new ArrayList<>();
        public final List<MediaInfo> video = new ArrayList<>();
        public final List<MediaInfo> images = new ArrayList<>();
    }

    /** True when the app may read every folder (needed for hidden files and NKT Share). */
    public static boolean hasAllFilesAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Opens Android's "All files access" page for this app (Android 11+). */
    public static Intent allFilesAccessIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + c.getPackageName()));
        if (i.resolveActivity(c.getPackageManager()) == null)
            i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        return i;
    }

    /**
     * Everything in hidden folders (if hidden=true) and/or inside ".thumbnails" folders (if thumbs=true).
     * dropDot / dropNomedia: kinds of hidden folder that stay hidden anyway (Settings → ALL FILES). They are left out
     * here, so no time is spent on them.
     *
     * Quick because: the storage is walked by several threads at once; what was read from a file before is kept
     * (path + size + date) and not read again; the rest is read by several threads; and the early picture (names only)
     * is handed over first through {@code early}, so rows appear while the details are still being read.
     */
    public static Result scan(Context c, boolean hidden, boolean thumbs, boolean dropDot, boolean dropNomedia,
                              java.util.function.Consumer<Result> early) {
        Result r = new Result();
        if (!hidden && !thumbs) return r;
        if (dropDot && dropNomedia) return r;          // everything this walk would find stays hidden: nothing to do

        // 1) walk
        Walk w = new Walk(hidden, thumbs, dropDot, dropNomedia);
        File root = Environment.getExternalStorageDirectory();
        w.submit(() -> w.visit(root, 0, false, false, false, false));
        w.await();

        // 2) what is known already; what is not
        Map<String, Meta> cache = MetaCache.load(c);
        List<Cand> todo = new ArrayList<>();
        List<Cand> all = new ArrayList<>(w.found);
        for (Cand k : all) {
            k.size = k.file.length();
            k.date = k.file.lastModified();
            Meta m = cache.get(k.file.getAbsolutePath());
            if (m != null && m.size == k.size && m.date == k.date) k.meta = m; else if (k.kind != IMG) todo.add(k);
        }

        if (!todo.isEmpty() && early != null) {
            Result first = new Result();
            for (Cand k : all) place(first, k, true);
            sort(first);
            early.accept(first);
        }

        // 3) read the rest, several at a time
        if (!todo.isEmpty()) {
            int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> fs = new ArrayList<>();
                for (Cand k : todo) fs.add(ex.submit(() -> { k.meta = read(k); }));
                for (Future<?> f : fs) try { f.get(); } catch (Exception ignored) { }
            } finally { ex.shutdown(); }
            MetaCache.save(c, all);
        }

        for (Cand k : all) place(r, k, false);
        sort(r);
        return r;
    }

    private static void sort(Result r) {
        Collections.sort(r.audio, (a, b) -> a.title.compareToIgnoreCase(b.title));
        Collections.sort(r.video, (a, b) -> Long.compare(b.dateMs, a.dateMs));
        Collections.sort(r.images, (a, b) -> Long.compare(b.dateMs, a.dateMs));
    }

    public static boolean isThumbnailPath(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).contains("/.thumbnails");
    }

    private static final int AUD = 0, VID = 1, IMG = 2;

    /** A media file found by the walk. */
    private static final class Cand {
        File file; String folder; int kind; long size, date; Meta meta;
    }

    /** What is read from a media file. */
    static final class Meta {
        long size, date, dur;
        String title = "", artist = "", album = "";
    }

    /** The walk through the storage: several threads, each folder is looked at once. */
    private static final class Walk {
        final boolean wantHidden, wantThumbs, dropDot, dropNomedia;
        final ExecutorService ex;
        final AtomicInteger pending = new AtomicInteger();
        final Object doneLock = new Object();
        final Queue<Cand> found = new ConcurrentLinkedQueue<>();

        Walk(boolean h, boolean t, boolean dd, boolean dn) {
            wantHidden = h; wantThumbs = t; dropDot = dd; dropNomedia = dn;
            ex = Executors.newFixedThreadPool(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() + 1)));
        }

        void submit(Runnable r) {
            pending.incrementAndGet();
            ex.execute(() -> {
                try { r.run(); } catch (Throwable ignored) { }
                finally {
                    if (pending.decrementAndGet() == 0) synchronized (doneLock) { doneLock.notifyAll(); }
                }
            });
        }

        void await() {
            synchronized (doneLock) {
                while (pending.get() > 0) try { doneLock.wait(); } catch (InterruptedException e) { break; }
            }
            ex.shutdown();
        }

        void visit(File dir, int depth, boolean inHidden, boolean inThumbs, boolean inDot, boolean inNomedia) {
            if (depth > 12) return;
            String name = dir.getName();
            if (depth == 1 && name.equals("Android")) return;            // app data: not accessible anyway
            boolean thumbsHere = inThumbs || name.equalsIgnoreCase(".thumbnails");
            if (thumbsHere && !wantThumbs) return;                       // .thumbnails has its own switch
            boolean dotHere = inDot || (depth > 0 && name.startsWith("."));
            if (dotHere && dropDot) return;                              // all of it stays hidden: no need to look
            File[] files = dir.listFiles();
            if (files == null) return;
            boolean nomediaHere = inNomedia;
            if (!nomediaHere) for (File f : files) if (f.getName().equals(".nomedia")) { nomediaHere = true; break; }
            if (nomediaHere && dropNomedia) return;
            boolean hiddenHere = inHidden || dotHere || nomediaHere;
            boolean collect = thumbsHere ? wantThumbs : (hiddenHere && wantHidden);
            for (File f : files) {
                if (f.isDirectory()) {
                    final boolean h = hiddenHere, t = thumbsHere, d = dotHere, n = nomediaHere;
                    if (depth < 3) submit(() -> visit(f, depth + 1, h, t, d, n));     // the top folders go to other threads
                    else visit(f, depth + 1, h, t, d, n);
                } else if (collect) {
                    candidate(f, name);
                }
            }
        }

        void candidate(File f, String folder) {
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            if (dot <= 0) return;                                        // (also leaves out files such as ".nomedia")
            String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
            int kind = AUDIO.contains(ext) ? AUD : VIDEO.contains(ext) ? VID : IMAGE.contains(ext) ? IMG : -1;
            if (kind < 0) return;
            Cand k = new Cand();
            k.file = f; k.folder = folder; k.kind = kind;
            found.add(k);
        }
    }

    /** Reads duration and tags of one file. */
    private static Meta read(Cand k) {
        Meta m = new Meta();
        m.size = k.size; m.date = k.date;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(k.file.getAbsolutePath());
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            m.dur = d == null ? 0 : Long.parseLong(d);
            if (k.kind == AUD) {
                String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                if (t != null) m.title = t;
                String ar = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                if (ar != null) m.artist = ar;
                String al = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                if (al != null) m.album = al;
            }
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
        return m;
    }

    /** Puts a found file in the list it belongs to. provisional = details not read yet (shown by file name). */
    private static void place(Result r, Cand k, boolean provisional) {
        File f = k.file;
        String n = f.getName();
        String base = n.substring(0, n.lastIndexOf('.'));
        long dur = 0;
        String artist = "", album = "";
        Meta m = k.meta;
        if (m != null) {
            dur = m.dur;
            if (k.kind == AUD) {
                if (!m.title.isEmpty()) base = m.title;
                artist = m.artist; album = m.album;
            }
        }
        if (k.kind == AUD && dur > 0 && dur < 10000) return;      // skip tiny clips, like the main library does
        MediaInfo mi = new MediaInfo(-1, Uri.fromFile(f), base, artist, album, -1, k.folder, dur, k.kind == VID);
        mi.hidden = true;
        mi.image = k.kind == IMG;
        mi.dateMs = k.date;
        mi.size = k.size;
        mi.path = f.getAbsolutePath();
        mi.fileName = n;
        if (k.kind == AUD) r.audio.add(mi); else if (k.kind == VID) r.video.add(mi); else r.images.add(mi);
    }

    /** What was read from files before, kept in files/hidden_meta.tsv (path, size, date, duration, title, artist, album). */
    private static final class MetaCache {
        private static final Object LOCK = new Object();

        static Map<String, Meta> load(Context c) {
            Map<String, Meta> map = new HashMap<>();
            synchronized (LOCK) {
                File f = new File(c.getFilesDir(), "hidden_meta.tsv");
                if (!f.exists()) return map;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] p = line.split("\t", -1);
                        if (p.length < 7) continue;
                        Meta m = new Meta();
                        m.size = Long.parseLong(p[1]); m.date = Long.parseLong(p[2]); m.dur = Long.parseLong(p[3]);
                        m.title = p[4]; m.artist = p[5]; m.album = p[6];
                        map.put(p[0], m);
                    }
                } catch (Throwable ignored) { }
            }
            return map;
        }

        /** Saves what is found now (files that are gone are dropped). */
        static void save(Context c, List<Cand> now) {
            synchronized (LOCK) {
                File tmp = new File(c.getFilesDir(), "hidden_meta.tsv.tmp");
                try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    for (Cand k : now) {
                        if (k.meta == null) continue;
                        Meta m = k.meta;
                        sb.setLength(0);
                        sb.append(clean(k.file.getAbsolutePath())).append('\t').append(m.size).append('\t').append(m.date)
                                .append('\t').append(m.dur).append('\t').append(clean(m.title)).append('\t')
                                .append(clean(m.artist)).append('\t').append(clean(m.album)).append('\n');
                        w.write(sb.toString());
                    }
                } catch (Throwable t) { return; }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(new File(c.getFilesDir(), "hidden_meta.tsv"));
            }
        }

        private static String clean(String s) { return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }
    }
}
