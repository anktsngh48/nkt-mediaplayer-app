package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** Dialogs used from many screens. */
public final class Actions {
    private Actions() {}

    public static final int REQ_DELETE = 41;
    private static List<MediaInfo> pendingDelete;
    private static Runnable pendingDone;

    public static void menu(Activity a, MediaInfo m, String inPlaylist, Runnable changed) {
        List<String> opts = new ArrayList<>();
        opts.add("Play");
        opts.add(Store.isFav(a, m) ? "Remove from favourites" : "♥ Favourite");
        opts.add("Add to playlist");
        opts.add("Share");
        if (inPlaylist != null) opts.add("Remove from playlist");
        opts.add("Details");
        opts.add("Delete");
        new AlertDialog.Builder(a).setTitle(m.title).setItems(opts.toArray(new String[0]), (d, w) -> {
            String o = opts.get(w);
            switch (o) {
                case "Play":
                    List<MediaInfo> one = Collections.singletonList(m);
                    if (m.isAudio()) Media.playSongs(a, one, 0, true);
                    else if (m.isVideo()) Media.playVideos(a, one, 0);
                    else Media.view(a, one, 0);
                    break;
                case "Add to playlist": addToPlaylist(a, Collections.singletonList(m)); break;
                case "Share": Media.share(a, Collections.singletonList(m)); break;
                case "Remove from playlist":
                    Store.removeFrom(a, inPlaylist, m.uri);
                    if (changed != null) changed.run();
                    break;
                case "Details": details(a, m, changed); break;
                case "Delete": confirmDelete(a, Collections.singletonList(m), changed); break;
                default:
                    boolean now = Store.toggleFav(a, m);
                    Ui.msg(a, now ? "Added to " + Store.favName(m) : "Removed from " + Store.favName(m));
                    if (changed != null) changed.run();
            }
        }).show();
    }

    public static void addToPlaylist(Activity a, List<MediaInfo> items) {
        List<String> names = Store.playlistNames(a);
        List<String> opts = new ArrayList<>();
        opts.add("＋ New playlist…");
        opts.addAll(names);
        new AlertDialog.Builder(a).setTitle("Add to playlist").setItems(opts.toArray(new String[0]), (d, w) -> {
            if (w == 0) {
                newPlaylist(a, name -> {
                    Store.addTo(a, name, items);
                    Ui.msg(a, "Added to " + name);
                });
            } else {
                Store.addTo(a, opts.get(w), items);
                Ui.msg(a, "Added to " + opts.get(w));
            }
        }).show();
    }

    public interface NameCallback { void done(String name); }

    public static void newPlaylist(Activity a, NameCallback cb) {
        EditText e = new EditText(a);
        e.setHint("Playlist name");
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
        box.addView(e);
        new AlertDialog.Builder(a).setTitle("New playlist").setView(box).setPositiveButton("Create", (d, w) -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty()) return;
            if (!Store.createPlaylist(a, n)) { Ui.msg(a, "A playlist with that name already exists"); return; }
            cb.done(n);
        }).setNegativeButton("Cancel", null).show();
    }

    /** Properties dialog with Delete, Share and Close. */
    public static void details(Activity a, MediaInfo m, Runnable changed) {
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
        TextView h = Ui.label(a, "Details", 13, Ui.ACCENT, 0.2f);
        h.setPadding(0, 0, 0, Ui.dp(a, 12));
        box.addView(h);
        String loc = m.path.isEmpty() ? m.uri : new File(m.path).getParent();
        String[][] rows = {{"Title", m.title}, {"Artist", m.isAudio() ? m.artistOrUnknown() : null}, {"Album", m.isAudio() ? m.album : null},
                {"File name", m.name}, {"Location", loc}, {"Size", m.size > 0 ? Ui.size(m.size) : null},
                {"Date", m.date > 0 ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(m.date)) : null},
                {"Length", m.duration > 0 ? Ui.time(m.duration) : null}, {"Type", m.mime}, {"Hidden", m.hidden ? "Yes" : "No"}};
        for (String[] r : rows) {
            if (r[1] == null || r[1].isEmpty()) continue;
            LinearLayout l = Ui.row(a);
            l.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
            TextView k = Ui.text(a, r[0], 13, Ui.MUTED);
            l.addView(k, Ui.lp(Ui.dp(a, 84), Ui.WRAP));
            TextView v = Ui.text(a, r[1], 13, Ui.TEXT);
            v.setTextIsSelectable(true);
            l.addView(v, Ui.lpw(0, Ui.WRAP, 1));
            box.addView(l);
        }
        LinearLayout btns = Ui.row(a);
        btns.setPadding(0, Ui.dp(a, 18), 0, 0);
        TextView del = Ui.pill(a, "DELETE", false, Ui.DANGER), share = Ui.pill(a, "SHARE", false, Ui.ACCENT), close = Ui.pill(a, "CLOSE", true, Ui.ACCENT);
        for (TextView t : new TextView[]{del, share, close}) {
            t.setPadding(Ui.dp(a, 6), 0, Ui.dp(a, 6), 0);
            btns.addView(t, Ui.lpm(a, 0, Ui.dp(a, 46), 3, 0, 3, 0));
            ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1;
        }
        box.addView(btns);
        Dialog d = Ui.sheet(a, box);
        del.setOnClickListener(v -> { d.dismiss(); confirmDelete(a, Collections.singletonList(m), changed); });
        share.setOnClickListener(v -> Media.share(a, Collections.singletonList(m)));
        close.setOnClickListener(v -> d.dismiss());
        d.show();
    }

    public static void confirmDelete(Activity a, List<MediaInfo> items, Runnable done) {
        if (items.isEmpty()) return;
        boolean allMediaStore = true;
        for (MediaInfo m : items) if (!m.uri.startsWith("content://media")) allMediaStore = false;
        if (Build.VERSION.SDK_INT >= 30 && allMediaStore) {
            // Android shows its own confirmation.
            try {
                List<Uri> us = new ArrayList<>();
                for (MediaInfo m : items) us.add(m.u());
                PendingIntent pi = MediaStore.createDeleteRequest(a.getContentResolver(), us);
                pendingDelete = items;
                pendingDone = done;
                a.startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception e) {
                Ui.msg(a, "Couldn't delete");
            }
            return;
        }
        String what = items.size() == 1 ? "“" + items.get(0).title + "”" : items.size() + " files";
        new AlertDialog.Builder(a).setTitle("Delete " + what + "?")
                .setMessage("The file is removed from your phone and from every playlist. This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> deleteNow(a, items, done))
                .setNegativeButton("Cancel", null).show();
    }

    private static void deleteNow(Activity a, List<MediaInfo> items, Runnable done) {
        int n = 0;
        ContentResolver r = a.getContentResolver();
        for (MediaInfo m : items) {
            boolean ok = false;
            try {
                if (!m.path.isEmpty() && new File(m.path).delete()) {
                    ok = true;
                    MediaScannerConnection.scanFile(a, new String[]{m.path}, null, null);
                }
                if (!ok && m.uri.startsWith("content://")) ok = r.delete(m.u(), null, null) > 0;
            } catch (SecurityException se) {
                if (Build.VERSION.SDK_INT >= 29 && se instanceof RecoverableSecurityException) {
                    try {
                        pendingDelete = Collections.singletonList(m);
                        pendingDone = done;
                        a.startIntentSenderForResult(((RecoverableSecurityException) se).getUserAction().getActionIntent().getIntentSender(),
                                REQ_DELETE, null, 0, 0, 0);
                    } catch (Exception ignored) {}
                    return;
                }
            } catch (Exception ignored) {}
            if (ok) { n++; Library.remove(m.uri); Store.removeEverywhere(a, m.uri); }
        }
        Ui.msg(a, n == 0 ? "Couldn't delete" : n == 1 ? "Deleted" : "Deleted " + n + " files");
        if (done != null) done.run();
    }

    /** Activities forward onActivityResult here. */
    public static void onResult(Activity a, int req, int res) {
        if (req != REQ_DELETE || pendingDelete == null) return;
        if (res == Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= 30) {
                for (MediaInfo m : pendingDelete) { Library.remove(m.uri); Store.removeEverywhere(a, m.uri); }
                Ui.msg(a, pendingDelete.size() == 1 ? "Deleted" : "Deleted " + pendingDelete.size() + " files");
                if (pendingDone != null) pendingDone.run();
            } else {
                deleteNow(a, pendingDelete, pendingDone);
            }
        }
        pendingDelete = null;
        pendingDone = null;
    }

    public static boolean deleteFile(Activity a, File f) {
        boolean ok = f.isDirectory() ? deleteTree(f) : f.delete();
        if (ok) MediaScannerConnection.scanFile(a, new String[]{f.getAbsolutePath()}, null, null);
        return ok;
    }

    private static boolean deleteTree(File d) {
        File[] l = d.listFiles();
        if (l != null) for (File f : l) { if (f.isDirectory()) deleteTree(f); else //noinspection ResultOfMethodCallIgnored
            f.delete(); }
        return d.delete();
    }
}
