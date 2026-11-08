package com.nkt.mediaplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Files: Properties (with Open with, Rename, Share, Delete), deleting and renaming. Shared by every screen. */
public final class FileOps {

    private FileOps() { }

    public static final int REQ_DELETE = 4301, REQ_DELETE_PERM = 4302;

    public interface Deleted {
        /** Called at once: take these out of sight. */
        void done(Set<String> goneKeys);
        /** UNDO or a failed move: they are back. */
        default void restored() { }
    }

    /** Files can go into the app's own Recycle bin (needs "All files access" on Android 11+). */
    public static boolean canUseBin(Activity a) {
        if (Build.VERSION.SDK_INT >= 30) return HiddenScanner.hasAllFilesAccess(a);
        return a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * One per screen. Delete = move to the Recycle bin: instant, with UNDO. Without "All files access" on
     * Android 11+, files go to Android's own trash instead (Android asks first). The screen forwards
     * onActivityResult and onRequestPermissionsResult here.
     */
    public static final class Deleter {
        private final Activity a;
        private List<MediaInfo> pending;
        private Deleted callback;

        public Deleter(Activity a) { this.a = a; }

        public void delete(List<MediaInfo> items, Deleted done) {
            if (items == null || items.isEmpty()) return;
            pending = new ArrayList<>(items);
            callback = done;
            if (canUseBin(a)) { toBin(pending, done); return; }
            if (Build.VERSION.SDK_INT <= 29) {
                a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_DELETE_PERM);
                return;
            }
            // Android 11+ without All files access: Android's trash (kept 30 days, shown in the Recycle bin too)
            List<Uri> content = new ArrayList<>();
            for (MediaInfo m : items) if ("content".equals(m.uri.getScheme())) content.add(m.uri);
            if (content.isEmpty()) { Ui.toast(a, R.string.delete_failed); return; }
            try {
                PendingIntent pi = MediaStore.createTrashRequest(a.getContentResolver(), content, true);
                a.startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception e) { Ui.toast(a, R.string.delete_failed); }
        }

        /** Returns true when the result was ours. */
        public boolean onActivityResult(int requestCode, int resultCode) {
            if (requestCode != REQ_DELETE || pending == null) return false;
            if (resultCode == Activity.RESULT_OK) {
                Set<String> gone = new HashSet<>();
                for (MediaInfo m : pending) { gone.add(m.key()); Thumbs.forget(m); }
                Deleted cb = callback;
                int n = pending.size();
                pending = null;
                if (cb != null) cb.done(gone);                     // out of sight straight away
                Ui.toast(a, a.getResources().getQuantityString(R.plurals.moved_to_bin, n, n));
            } else pending = null;
            return true;
        }

        public boolean onRequestPermissionsResult(int requestCode, int[] results) {
            if (requestCode != REQ_DELETE_PERM || pending == null) return false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) toBin(pending, callback);
            else Ui.toast(a, R.string.delete_failed);
            return true;
        }

        private void toBin(List<MediaInfo> items, Deleted cb) {
            pending = null;
            Set<String> gone = new HashSet<>();
            for (MediaInfo m : items) { gone.add(m.key()); Thumbs.forget(m); }
            if (cb != null) cb.done(gone);                         // out of sight at once; the move follows
            final Context app = a.getApplicationContext();
            new Thread(() -> {
                List<String> ids = new ArrayList<>();
                List<MediaInfo> noBin = new ArrayList<>();          // can't go to the bin: deleted for good after a warning
                for (MediaInfo m : items) {
                    File f = FileJobs.localFile(m);
                    if (f == null && m.path != null && !m.path.isEmpty()) f = new File(m.path);
                    Trash.Entry e = f == null ? null : Trash.put(app, f);
                    if (e != null) ids.add(e.id); else noBin.add(m);
                }
                final int n = ids.size();
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!noBin.isEmpty()) warnPermanent(noBin, cb);
                    if (n == 0) return;
                    String msg = app.getResources().getQuantityString(R.plurals.moved_to_bin, n, n);
                    if (a.isFinishing()) { Ui.toastApp(app, msg); return; }
                    Ui.toast(a, msg, R.string.undo_caps, () -> new Thread(() -> {
                        for (String id : ids) {
                            Trash.Entry e = Trash.find(app, id);
                            if (e != null) Trash.restore(app, e);
                        }
                        new Handler(Looper.getMainLooper()).post(() -> {
                            Ui.toastApp(app, app.getResources().getQuantityString(R.plurals.restored_n, n, n));
                            if (cb != null) cb.restored();
                        });
                    }).start());
                });
            }).start();
        }

        /** These can't go to the Recycle bin: say so, then delete them for good (or keep them). */
        private void warnPermanent(List<MediaInfo> items, Deleted cb) {
            final Context app = a.getApplicationContext();
            int n = items.size();
            if (a.isFinishing()) { if (cb != null) cb.restored(); return; }
            String name = items.get(0).fileName.isEmpty() ? items.get(0).title : items.get(0).fileName;
            Dialogs.builder(a)
                    .setTitle(R.string.bin_cant_title)
                    .setMessage(a.getResources().getQuantityString(R.plurals.bin_cant_msg, n, n, name))
                    .setPositiveButton(R.string.delete_permanently, (d, w) -> new Thread(() -> {
                        int ok = 0;
                        List<String> scan = new ArrayList<>();
                        for (MediaInfo m : items) if (deleteForGood(app, m, scan)) ok++;
                        if (!scan.isEmpty()) {
                            try { MediaScannerConnection.scanFile(app, scan.toArray(new String[0]), null, null); } catch (Exception ignored) { }
                        }
                        final int done = ok;
                        new Handler(Looper.getMainLooper()).post(() -> {
                            Ui.toastApp(app, done > 0 ? app.getResources().getQuantityString(R.plurals.deleted_for_good, done, done)
                                    : app.getString(R.string.delete_failed));
                            if (done < items.size() && cb != null) cb.restored();
                        });
                    }).start())
                    .setNegativeButton(R.string.keep_caps, (d, w) -> { if (cb != null) cb.restored(); })
                    .setOnCancelListener(d -> { if (cb != null) cb.restored(); })
                    .show();
        }
    }

    /** Gone for good: the file itself, or the library entry when there's no file to reach. */
    static boolean deleteForGood(Context c, MediaInfo m, List<String> scan) {
        File f = FileJobs.localFile(m);
        if (f == null && m.path != null && !m.path.isEmpty()) f = new File(m.path);
        if (f != null && f.exists()) {
            boolean ok = f.isDirectory() ? deleteTree(f) : f.delete();
            if (ok) { scan.add(f.getAbsolutePath()); return true; }
        }
        try {
            return m.uri != null && "content".equals(m.uri.getScheme()) && c.getContentResolver().delete(m.uri, null, null) > 0;
        } catch (Exception e) { return false; }
    }

    static boolean deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) { if (k.isDirectory()) deleteTree(k); else //noinspection ResultOfMethodCallIgnored
            k.delete(); }
        return f.delete();
    }

    // ── Open with ────────────────────────────────────────────────────────────

    public static String mime(Activity a, MediaInfo m) {
        String type = null;
        if ("content".equals(m.uri.getScheme())) {
            try { type = a.getContentResolver().getType(m.uri); } catch (Exception ignored) { }
        }
        if (type == null) {
            String e = MediaInfo.ext(m.fileName.isEmpty() ? m.uri.getLastPathSegment() : m.fileName);
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        }
        if (type == null) type = m.image ? "image/*" : m.video ? "video/*" : m.other ? "*/*" : "audio/*";
        return type;
    }

    /** A uri other apps can read: content:// as is, files through our FileProvider. */
    public static Uri shareable(Activity a, MediaInfo m) {
        if ("file".equals(m.uri.getScheme()))
            return FileProvider.getUriForFile(a, a.getPackageName() + ".files", new File(m.uri.getPath()));
        return m.uri;
    }

    public static void openWith(Activity a, MediaInfo m) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(shareable(a, m), mime(a, m))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, a.getString(R.string.open_with_caps)));
        } catch (Exception e) {
            Ui.toast(a, R.string.no_app_to_open);
        }
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    public static void rename(Activity a, MediaInfo m, Runnable done) {
        String name = m.fileName.isEmpty() ? m.title : m.fileName;
        File asFile = FileJobs.localFile(m);
        boolean folder = asFile != null && asFile.isDirectory();
        if (folder && Build.VERSION.SDK_INT >= 30 && !HiddenScanner.hasAllFilesAccess(a)) {
            Ui.toast(a, R.string.rename_needs_access);       // folders can only be renamed with All files access
            try { a.startActivity(HiddenScanner.allFilesAccessIntent(a)); } catch (Exception ignored) { }
            return;
        }
        String ext = folder ? "" : MediaInfo.ext(name);    // a folder's name has no extension to keep
        String base = ext.isEmpty() ? name : name.substring(0, name.length() - ext.length() - 1);
        EditText input = new EditText(a);
        input.setText(base);
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        FrameLayout box = new FrameLayout(a);
        int pad = Ui.dp(a, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        Dialogs.builder(a)
                .setTitle(R.string.rename)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String nb = input.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
                    if (nb.isEmpty() || nb.equals(base)) return;
                    String nn = ext.isEmpty() ? nb : nb + "." + ext;
                    new Thread(() -> {
                        boolean ok = doRename(a, m, nn);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            Ui.toast(a, ok ? R.string.renamed : R.string.rename_failed);
                            if (ok) { Thumbs.forget(m); if (done != null) done.run(); }
                        });
                    }).start();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static boolean doRename(Activity a, MediaInfo m, String newName) {
        String path = m.path;
        if (path.isEmpty() && "file".equals(m.uri.getScheme())) path = m.uri.getPath();
        if (path != null && !path.isEmpty() && ("file".equals(m.uri.getScheme()) || HiddenScanner.hasAllFilesAccess(a))) {
            File f = new File(path), to = new File(f.getParentFile(), newName);
            if (to.exists()) return false;
            // a folder: every file inside moves too, so Android's library must re-read each old and new path
            List<String> inside = new ArrayList<>();
            if (f.isDirectory()) listInside(f, "", inside);
            if (f.renameTo(to)) {
                List<String> scan = new ArrayList<>();
                scan.add(f.getAbsolutePath());
                scan.add(to.getAbsolutePath());
                for (String rel : inside) {
                    scan.add(new File(f, rel).getAbsolutePath());
                    scan.add(new File(to, rel).getAbsolutePath());
                }
                try {
                    MediaScannerConnection.scanFile(a.getApplicationContext(), scan.toArray(new String[0]), null, null);
                } catch (Exception ignored) { }
                return true;
            }
        }
        if ("content".equals(m.uri.getScheme())) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, newName);
                return a.getContentResolver().update(m.uri, v, null, null) > 0;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static void listInside(File dir, String rel, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null || out.size() > 20000) return;
        for (File k : kids) {
            String r = rel.isEmpty() ? k.getName() : rel + "/" + k.getName();
            if (k.isDirectory()) listInside(k, r, out); else out.add(r);
        }
    }

    // ── Properties ───────────────────────────────────────────────────────────

    /** Properties of any file, with OPEN WITH · RENAME · SHARE · DELETE. changed runs after a rename or delete. */
    @SuppressWarnings("deprecation")
    public static void properties(Activity a, MediaInfo m, Deleter deleter, Deleted onDeleted, Runnable onRenamed) {
        if (m == null) return;
        String name = m.fileName, path = m.path;
        long size = m.size, date = m.dateMs;
        if ("content".equals(m.uri.getScheme())) {
            try (Cursor c = a.getContentResolver().query(m.uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int i;
                    if ((i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)) >= 0 && name.isEmpty()) name = str(c.getString(i));
                    if ((i = c.getColumnIndex(OpenableColumns.SIZE)) >= 0 && size <= 0) size = c.getLong(i);
                    if ((i = c.getColumnIndex(MediaStore.MediaColumns.DATA)) >= 0 && path.isEmpty()) path = str(c.getString(i));
                    if ((i = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)) >= 0) {
                        long mod = c.getLong(i) * 1000L;
                        if (mod > 0) date = mod;
                    }
                }
            } catch (Exception ignored) { }
        } else if ("file".equals(m.uri.getScheme()) && m.uri.getPath() != null) {
            File f = new File(m.uri.getPath());
            if (name.isEmpty()) name = f.getName();
            if (path.isEmpty()) path = f.getAbsolutePath();
            if (size <= 0) size = f.length();
            date = f.lastModified();
        }
        List<String[]> rows = new ArrayList<>();
        if (!m.other && !m.image) add(rows, a.getString(R.string.d_title), m.title);
        if (!m.artist.isEmpty()) add(rows, a.getString(R.string.d_artist), m.artist);
        if (!m.album.isEmpty()) add(rows, a.getString(R.string.d_album), m.album);
        if (!name.isEmpty()) add(rows, a.getString(R.string.d_file), name);
        if (!path.isEmpty()) {
            File parent = new File(path).getParentFile();
            add(rows, a.getString(R.string.d_location), parent == null ? path : FolderPicker.pretty(a, FolderPicker.roots(a), parent));
        } else if (m.uri.getScheme() != null && !"content".equals(m.uri.getScheme())) add(rows, a.getString(R.string.d_location), Remote.visible(m.uri));
        if (size > 0) add(rows, a.getString(R.string.d_size), Formatter.formatFileSize(a, size));
        if (date > 0) add(rows, a.getString(R.string.d_modified), DateFormat.getMediumDateFormat(a).format(date) + ", " + DateFormat.getTimeFormat(a).format(date));
        if (m.durationMs > 0) add(rows, a.getString(R.string.d_length), Ui.time(m.durationMs));
        String res = resolution(a, m, path);
        if (res != null) add(rows, a.getString(R.string.d_resolution), res);
        add(rows, a.getString(R.string.d_type), mime(a, m));
        if (m.hidden) add(rows, a.getString(R.string.d_hidden), a.getString(R.string.yes));

        int dp = Ui.dp(a, 1);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20 * dp, 8 * dp, 20 * dp, 4 * dp);
        for (String[] r : rows) {
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, 7 * dp, 0, 7 * dp);
            TextView k = new TextView(a);
            k.setText(r[0]);
            k.setTextColor(a.getColor(R.color.muted));
            k.setTextSize(12);
            TextView v = new TextView(a);
            v.setText(r[1]);
            v.setTextColor(a.getColor(R.color.text));
            v.setTextSize(13);
            v.setTextIsSelectable(true);
            line.addView(k, new LinearLayout.LayoutParams(104 * dp, ViewGroup.LayoutParams.WRAP_CONTENT));
            line.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            box.addView(line);
            View div = new View(a);
            div.setBackgroundColor(a.getColor(R.color.border));
            box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp));
        }
        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, 14 * dp, 0, 0);
        box.addView(buttons);
        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);

        AlertDialog dlg = Dialogs.builder(a)
                .setTitle(R.string.properties)
                .setView(scroll)
                .setNegativeButton(R.string.close_caps, null)
                .create();
        boolean local = !m.remote && ("file".equals(m.uri.getScheme()) || "content".equals(m.uri.getScheme()));
        if (local) button(a, buttons, R.drawable.ic_open, R.string.open_with_caps, false, () -> { dlg.dismiss(); openWith(a, m); });
        if (local) button(a, buttons, R.drawable.ic_edit, R.string.rename_caps, false, () -> { dlg.dismiss(); rename(a, m, onRenamed); });
        if (!m.remote) button(a, buttons, R.drawable.ic_share, R.string.share_caps, false, () -> { dlg.dismiss(); Share.send(a, m); });
        if (local && deleter != null) button(a, buttons, R.drawable.ic_delete, R.string.delete_caps, true, () -> {
            dlg.dismiss();
            List<MediaInfo> one = new ArrayList<>();
            one.add(m);
            deleter.delete(one, onDeleted);
        });
        dlg.show();
    }

    private static void button(Activity a, LinearLayout row, int icon, int label, boolean danger, Runnable r) {
        TextView b = new TextView(a);
        b.setText(label);
        b.setTypeface(Ui.orbitron(a));
        b.setTextSize(8.5f);
        b.setLetterSpacing(0.08f);
        b.setGravity(Gravity.CENTER);
        int color = danger ? 0xFFFF4F6A : a.getColor(R.color.accent);
        b.setTextColor(color);
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(0, icon, 0, 0);
        b.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
        b.setCompoundDrawablePadding(Ui.dp(a, 4));
        b.setBackgroundResource(danger ? R.drawable.bg_danger_btn : R.drawable.bg_tile_btn);
        b.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 8));
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 64), 1);
        lp.setMarginEnd(Ui.dp(a, 6));
        row.addView(b, lp);
    }

    private static String resolution(Activity a, MediaInfo m, String path) {
        try {
            if (m.image) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                if (path != null && !path.isEmpty() && new File(path).canRead()) BitmapFactory.decodeFile(path, o);
                else try (java.io.InputStream in = a.getContentResolver().openInputStream(m.uri)) { BitmapFactory.decodeStream(in, null, o); }
                return o.outWidth > 0 ? o.outWidth + " × " + o.outHeight : null;
            }
            if (m.video && !m.remote) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(a, m.uri);
                    String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                    if (w == null || h == null) return null;
                    boolean turned = "90".equals(rot) || "270".equals(rot);
                    return turned ? h + " × " + w : w + " × " + h;
                } finally { try { r.release(); } catch (Exception ignored) { } }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void add(List<String[]> rows, String k, String v) { rows.add(new String[]{k, v}); }

    private static String str(String s) { return s == null ? "" : s; }
}
