package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Copy · Cut · Paste for files and folders: inside this phone (phone storage, SD card, USB) and to or from
 * other devices (phones running NKT Share, any FTP server). Runs in the background with progress in the notification.
 */
public final class FileJobs {

    private FileJobs() { }

    // ── The clipboard ────────────────────────────────────────────────────────

    public static final class Clip {
        public final List<MediaInfo> items;
        public final boolean cut;
        public final Remote.Device from;       // null = this phone

        Clip(List<MediaInfo> items, boolean cut, Remote.Device from) {
            this.items = new ArrayList<>(items);
            this.cut = cut;
            this.from = from;
        }
    }

    private static Clip clip;

    public static Clip clip() { return clip; }

    public static boolean hasClip() { return clip != null && !clip.items.isEmpty(); }

    public static void clear() { clip = null; }

    /** Copy (cut = false) or Cut (cut = true). from = the device they are on, null for this phone. */
    public static void put(Context c, List<MediaInfo> items, boolean cut, Remote.Device from) {
        if (items == null || items.isEmpty()) return;
        clip = new Clip(items, cut, from);
        Ui.toastApp(c, c.getResources().getQuantityString(cut ? R.plurals.clip_cut : R.plurals.clip_copied, items.size(), items.size()));
    }

    /** "PASTE 3 ITEMS" */
    public static String pasteLabel(Context c) {
        int n = hasClip() ? clip.items.size() : 0;
        return c.getResources().getQuantityString(clip != null && clip.cut ? R.plurals.paste_move_n : R.plurals.paste_n, n, n);
    }

    public interface Done { void done(int ok, int failed); }

    // ── Paste here (a folder on this phone) ──────────────────────────────────

    public static void pasteLocal(Activity a, File dir, Done done) {
        final Clip job = clip;
        if (job == null || dir == null) return;
        final Context app = a.getApplicationContext();
        final int id = nextId();
        final Progress pr = new Progress(app, id, job.items.size());
        new Thread(() -> {
            int ok = 0, bad = 0;
            List<String> scan = new ArrayList<>();
            FTPClient src = null;
            try {
                if (job.from != null) src = Remote.connect(job.from);
                for (MediaInfo m : job.items) {
                    pr.next(name(m));
                    boolean done1;
                    try {
                        done1 = job.from != null ? remoteToLocal(src, job.from, m, dir, job.cut, scan, pr)
                                : localToLocal(app, m, dir, job.cut, scan, pr);
                    } catch (Exception e) { done1 = false; }
                    if (done1) ok++; else bad++;
                }
            } catch (Exception e) {
                bad = job.items.size() - ok;
            } finally { Remote.quietly(src); }
            if (!scan.isEmpty()) {
                try { MediaScannerConnection.scanFile(app, scan.toArray(new String[0]), null, null); } catch (Throwable ignored) { }
            }
            if (job.cut && ok > 0 && clip == job) clip = null;          // moved: nothing left to paste
            finish(app, pr, ok, bad, FolderPicker.pretty(app, FolderPicker.roots(app), dir), done);
        }).start();
    }

    private static boolean localToLocal(Context c, MediaInfo m, File dir, boolean cut, List<String> scan, Progress pr) throws IOException {
        File src = localFile(m);
        if (src != null && src.exists()) {
            if (cut && dir.equals(src.getParentFile())) return true;                     // already here
            if (src.isDirectory() && (dir.getAbsolutePath() + "/").startsWith(src.getAbsolutePath() + "/")) return false; // into itself
            File to = unique(dir, src.getName());
            if (cut && src.renameTo(to)) {                                               // same storage: instant
                scan.add(src.getAbsolutePath());
                collect(to, scan);
                return true;
            }
            copyTree(src, to, pr);
            collect(to, scan);
            if (cut) {
                List<String> old = new ArrayList<>();
                collect(src, old);
                if (src.isDirectory() ? FileOps.deleteTree(src) : src.delete()) scan.addAll(old);
            }
            return true;
        }
        // only a library address (content://): copy its bytes
        if (m.uri == null || !"content".equals(m.uri.getScheme())) return false;
        String name = m.fileName.isEmpty() ? m.title : m.fileName;
        File to = unique(dir, name);
        try (InputStream in = c.getContentResolver().openInputStream(m.uri); OutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new IOException("open");
            pump(in, out, pr);
        }
        scan.add(to.getAbsolutePath());
        if (cut) try { c.getContentResolver().delete(m.uri, null, null); } catch (Exception ignored) { }
        return true;
    }

    private static boolean remoteToLocal(FTPClient ftp, Remote.Device d, MediaInfo m, File dir, boolean cut, List<String> scan, Progress pr) throws IOException {
        String path = m.uri.getPath();
        if (path == null) return false;
        File to = unique(dir, name(m));
        if (m.other && isRemoteDir(ftp, path)) {
            downloadTree(ftp, path, to, scan, pr);
        } else {
            try (OutputStream out = new FileOutputStream(to)) {
                InputStream in = ftp.retrieveFileStream(path);
                if (in == null) throw new IOException("get");
                try { pump(in, out, pr); } finally { in.close(); }
                if (!ftp.completePendingCommand()) throw new IOException("get");
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                to.delete();
                throw e;
            }
            scan.add(to.getAbsolutePath());
        }
        if (cut) deleteRemote(ftp, path);
        return true;
    }

    private static void downloadTree(FTPClient ftp, String path, File to, List<String> scan, Progress pr) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        to.mkdirs();
        FTPFile[] kids = ftp.listFiles(path);
        if (kids == null) return;
        for (FTPFile k : kids) {
            String n = k.getName();
            if (n == null || ".".equals(n) || "..".equals(n)) continue;
            String p = Remote.child(path, n);
            if (k.isDirectory()) downloadTree(ftp, p, new File(to, n), scan, pr);
            else {
                File f = new File(to, n);
                try (OutputStream out = new FileOutputStream(f)) {
                    InputStream in = ftp.retrieveFileStream(p);
                    if (in == null) throw new IOException("get");
                    try { pump(in, out, pr); } finally { in.close(); }
                    if (!ftp.completePendingCommand()) throw new IOException("get");
                }
                scan.add(f.getAbsolutePath());
            }
        }
    }

    // ── Paste on another device ──────────────────────────────────────────────

    public static void pasteRemote(Activity a, Remote.Device to, String dir, Done done) {
        final Clip job = clip;
        if (job == null || to == null) return;
        final Context app = a.getApplicationContext();
        final Progress pr = new Progress(app, nextId(), job.items.size());
        new Thread(() -> {
            int ok = 0, bad = 0;
            FTPClient dst = null, src = null;
            try {
                dst = Remote.connect(to);
                boolean sameDevice = job.from != null && job.from.key().equals(to.key());
                if (job.from != null) src = Remote.connect(job.from);
                for (MediaInfo m : job.items) {
                    pr.next(name(m));
                    boolean done1;
                    try {
                        if (job.from == null) done1 = localToRemote(app, m, dst, dir, job.cut, pr);
                        else done1 = remoteToRemote(src, dst, m, dir, job.cut, sameDevice, pr);
                    } catch (Exception e) { done1 = false; }
                    if (done1) ok++; else bad++;
                }
            } catch (Exception e) {
                bad = job.items.size() - ok;
            } finally { Remote.quietly(dst); Remote.quietly(src); }
            if (job.cut && ok > 0 && clip == job) clip = null;
            finish(app, pr, ok, bad, to.label() + dir, done);
        }).start();
    }

    private static boolean localToRemote(Context c, MediaInfo m, FTPClient ftp, String dir, boolean cut, Progress pr) throws IOException {
        File src = localFile(m);
        String name = src != null ? src.getName() : name(m);
        String target = uniqueRemote(ftp, dir, name);
        if (src != null && src.isDirectory()) {
            uploadTree(ftp, src, target, pr);
        } else {
            try (InputStream in = src != null ? new FileInputStream(src) : c.getContentResolver().openInputStream(m.uri)) {
                if (in == null) throw new IOException("open");
                if (!ftp.storeFile(target, new CountingInput(in, pr))) throw new IOException("put");
            }
        }
        if (cut) {
            if (src != null) { if (src.isDirectory()) FileOps.deleteTree(src); else //noinspection ResultOfMethodCallIgnored
                src.delete(); }
            else try { c.getContentResolver().delete(m.uri, null, null); } catch (Exception ignored) { }
        }
        return true;
    }

    private static void uploadTree(FTPClient ftp, File dir, String target, Progress pr) throws IOException {
        ftp.makeDirectory(target);
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String p = Remote.child(target, k.getName());
            if (k.isDirectory()) uploadTree(ftp, k, p, pr);
            else try (InputStream in = new FileInputStream(k)) {
                if (!ftp.storeFile(p, new CountingInput(in, pr))) throw new IOException("put");
            }
        }
    }

    private static boolean remoteToRemote(FTPClient src, FTPClient dst, MediaInfo m, String dir, boolean cut, boolean same, Progress pr) throws IOException {
        String path = m.uri.getPath();
        if (path == null) return false;
        String target = uniqueRemote(dst, dir, name(m));
        if (same && cut) {
            if (Remote.parent(path).equals(dir)) return true;
            return dst.rename(path, target);
        }
        if (m.other && isRemoteDir(src, path)) return false;             // folders between devices: copy to the phone first
        InputStream in = src.retrieveFileStream(path);
        if (in == null) throw new IOException("get");
        try { if (!dst.storeFile(target, new CountingInput(in, pr))) throw new IOException("put"); }
        finally { in.close(); }
        if (!src.completePendingCommand()) throw new IOException("get");
        if (cut) deleteRemote(src, path);
        return true;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static String name(MediaInfo m) {
        if (!m.fileName.isEmpty()) return m.fileName;
        if (m.uri != null && m.uri.getLastPathSegment() != null && !"content".equals(m.uri.getScheme())) return m.uri.getLastPathSegment();
        return m.title;
    }

    /** The real file behind an item, when there is one we can read. */
    static File localFile(MediaInfo m) {
        if (m.uri != null && "file".equals(m.uri.getScheme()) && m.uri.getPath() != null) return new File(m.uri.getPath());
        if (m.path != null && !m.path.isEmpty()) {
            File f = new File(m.path);
            if (f.canRead()) return f;
        }
        return null;
    }

    static boolean isRemoteDir(FTPClient ftp, String path) {
        try {
            FTPFile f = ftp.mlistFile(path);
            if (f != null) return f.isDirectory();
        } catch (Exception ignored) { }
        try {
            String here = ftp.printWorkingDirectory();
            boolean dir = ftp.changeWorkingDirectory(path);
            if (dir && here != null) ftp.changeWorkingDirectory(here);
            return dir;
        } catch (Exception e) { return false; }
    }

    static void deleteRemote(FTPClient ftp, String path) throws IOException {
        if (isRemoteDir(ftp, path)) {
            FTPFile[] kids = ftp.listFiles(path);
            if (kids != null) for (FTPFile k : kids) {
                String n = k.getName();
                if (n == null || ".".equals(n) || "..".equals(n)) continue;
                deleteRemote(ftp, Remote.child(path, n));
            }
            ftp.removeDirectory(path);
        } else ftp.deleteFile(path);
    }

    static String uniqueRemote(FTPClient ftp, String dir, String name) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        try {
            FTPFile[] l = ftp.listFiles(dir);
            if (l != null) for (FTPFile f : l) if (f.getName() != null) taken.add(f.getName());
        } catch (Exception ignored) { }
        if (!taken.contains(name)) return Remote.child(dir, name);
        String base = MediaInfo.stripExt(name), ext = MediaInfo.ext(name);
        for (int i = 1; i < 1000; i++) {
            String n = base + " (" + i + ")" + (ext.isEmpty() ? "" : "." + ext);
            if (!taken.contains(n)) return Remote.child(dir, n);
        }
        return Remote.child(dir, name);
    }

    public static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = MediaInfo.stripExt(name), ext = MediaInfo.ext(name);
        for (int i = 1; i < 10000; i++) {
            f = new File(dir, base + " (" + i + ")" + (ext.isEmpty() ? "" : "." + ext));
            if (!f.exists()) return f;
        }
        return f;
    }

    public static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (!f.isDirectory()) return f.length();
        long t = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) t += sizeOf(k);
        return t;
    }

    /** Moves a file or folder; when a plain rename isn't possible (another storage area), copies then removes. */
    static boolean move(File src, File to) {
        if (src.renameTo(to)) return true;
        try {
            copyTree(src, to, null);
        } catch (IOException e) {
            if (to.isDirectory()) FileOps.deleteTree(to); else //noinspection ResultOfMethodCallIgnored
                to.delete();
            return false;                                  // the original is untouched
        }
        if (src.isDirectory()) FileOps.deleteTree(src); else //noinspection ResultOfMethodCallIgnored
            src.delete();
        return true;
    }

    static void copyTree(File src, File to, Progress pr) throws IOException {
        if (src.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            to.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(to, k.getName()), pr);
            return;
        }
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(to)) {
            pump(in, out, pr);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();
            throw e;
        }
        //noinspection ResultOfMethodCallIgnored
        to.setLastModified(src.lastModified());
    }

    private static void collect(File f, List<String> out) {
        if (out.size() > 5000) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) collect(k, out);
        } else out.add(f.getAbsolutePath());
    }

    private static void pump(InputStream in, OutputStream out, Progress pr) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (pr != null) pr.bytes(n);
        }
    }

    /** Counts bytes going up to another device. */
    private static final class CountingInput extends java.io.FilterInputStream {
        private final Progress pr;
        CountingInput(InputStream in, Progress pr) { super(in); this.pr = pr; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) pr.bytes(n);
            return n;
        }
    }

    // ── Progress in the notification ─────────────────────────────────────────

    private static int ids = 5600;

    private static synchronized int nextId() { return ids++; }

    static final String CHANNEL = "copies";

    static final class Progress {
        final Context app;
        final int id, total;
        int index = 0;
        long moved = 0, lastShown = 0;
        String current = "";

        Progress(Context app, int id, int total) {
            this.app = app; this.id = id; this.total = total;
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null)
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, app.getString(R.string.copies_channel), NotificationManager.IMPORTANCE_LOW));
        }

        void next(String name) { index++; current = name; show(); }

        void bytes(int n) {
            moved += n;
            long now = System.currentTimeMillis();
            if (now - lastShown > 700) show();
        }

        @SuppressWarnings("deprecation")
        void show() {
            lastShown = System.currentTimeMillis();
            post(app, id, total == 1 ? app.getString(R.string.copying_one) : app.getString(R.string.copying_n, index, total),
                    current + " · " + android.text.format.Formatter.formatShortFileSize(app, moved), index - 1, total, true);
        }
    }

    @SuppressWarnings("deprecation")
    static void post(Context app, int id, String title, String text, int done, int total, boolean ongoing) {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(app, CHANNEL) : new Notification.Builder(app);
            b.setSmallIcon(R.drawable.ic_stat_nkt).setContentTitle(title).setOngoing(ongoing).setOnlyAlertOnce(true);
            if (text != null) b.setContentText(text);
            if (ongoing) b.setProgress(Math.max(1, total), done, false);
            nm.notify(id, b.build());
        } catch (Exception ignored) { }
    }

    private static void finish(Context app, Progress pr, int ok, int bad, String where, Done done) {
        String text = bad == 0 ? app.getString(R.string.pasted_to, where)
                : app.getString(R.string.pasted_some, ok, ok + bad, where);
        post(app, pr.id, text, null, 1, 1, false);
        new Handler(Looper.getMainLooper()).post(() -> {
            Ui.toastApp(app, text);
            if (done != null) done.done(ok, bad);
        });
    }
}
