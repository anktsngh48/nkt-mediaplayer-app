package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.Cursor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.List;

/** Shared pop-ups: add to playlist, new playlist, details. */
public final class Dialogs {

    private Dialogs() { }

    public interface NameCallback { void done(String name); }

    public static AlertDialog.Builder builder(Activity a) {
        return new AlertDialog.Builder(a, R.style.Theme_NKT_Dialog);
    }

    public static void newPlaylist(Activity a, NameCallback cb) {
        EditText input = new EditText(a);
        input.setHint(R.string.playlist_name);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setSingleLine(true);
        FrameLayout box = new FrameLayout(a);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        builder(a)
                .setTitle(R.string.new_playlist)
                .setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (!Playlists.create(a, name)) {
                        Ui.toast(a, R.string.playlist_exists);
                        return;
                    }
                    cb.done(name);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void addToPlaylist(Activity a, MediaInfo m) {
        if (m == null) return;
        List<MediaInfo> one = new java.util.ArrayList<>();
        one.add(m);
        addToPlaylist(a, one, null);
    }

    /** Adds one or many items; done (may be null) runs after adding. */
    public static void addToPlaylist(Activity a, List<MediaInfo> items, Runnable done) {
        if (items == null || items.isEmpty()) return;
        List<String> names = Playlists.names(a);
        String[] choices = new String[names.size() + 1];
        choices[0] = a.getString(R.string.new_playlist_dots);
        for (int i = 0; i < names.size(); i++) choices[i + 1] = names.get(i);
        builder(a)
                .setTitle(items.size() == 1 ? a.getString(R.string.add_to_playlist)
                        : a.getString(R.string.add_n_to_playlist, items.size()))
                .setItems(choices, (d, which) -> {
                    if (which == 0) {
                        newPlaylist(a, name -> {
                            for (MediaInfo m : items) Playlists.add(a, name, m);
                            Ui.toast(a, a.getString(R.string.added_to, name));
                            if (done != null) done.run();
                        });
                    } else {
                        String name = names.get(which - 1);
                        for (MediaInfo m : items) Playlists.add(a, name, m);
                        Ui.toast(a, a.getString(R.string.added_to, name));
                        if (done != null) done.run();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** File name, where it is, size, date, length, type. */
    @SuppressWarnings("deprecation")
    public static void details(Activity a, MediaInfo m) {
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
                    if ((i = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)) >= 0 && date <= 0) date = c.getLong(i) * 1000L;
                }
            } catch (Exception ignored) { }
        } else if ("file".equals(m.uri.getScheme()) && m.uri.getPath() != null) {
            java.io.File f = new java.io.File(m.uri.getPath());
            if (name.isEmpty()) name = f.getName();
            if (path.isEmpty()) path = f.getAbsolutePath();
            if (size <= 0) size = f.length();
            if (date <= 0) date = f.lastModified();
        }
        String type = null;
        try { type = a.getContentResolver().getType(m.uri); } catch (Exception ignored) { }

        StringBuilder b = new StringBuilder();
        line(b, a.getString(R.string.d_title), m.title);
        if (!m.artist.isEmpty()) line(b, a.getString(R.string.d_artist), m.artist);
        if (!m.album.isEmpty()) line(b, a.getString(R.string.d_album), m.album);
        if (!name.isEmpty()) line(b, a.getString(R.string.d_file), name);
        if (!path.isEmpty()) line(b, a.getString(R.string.d_location), path);
        else if (m.uri.getScheme() != null && m.uri.getScheme().startsWith("http")) line(b, a.getString(R.string.d_location), m.uri.toString());
        if (size > 0) line(b, a.getString(R.string.d_size), Formatter.formatFileSize(a, size));
        if (date > 0) line(b, a.getString(R.string.d_date), DateFormat.getMediumDateFormat(a).format(date) + " " + DateFormat.getTimeFormat(a).format(date));
        if (m.durationMs > 0) line(b, a.getString(R.string.d_length), Ui.time(m.durationMs));
        if (type != null) line(b, a.getString(R.string.d_type), type);
        if (m.hidden) line(b, a.getString(R.string.d_hidden), a.getString(R.string.yes));
        builder(a).setTitle(R.string.details).setMessage(b.toString().trim()).setPositiveButton(android.R.string.ok, null).show();
    }

    private static String str(String s) { return s == null ? "" : s; }

    private static void line(StringBuilder b, String k, String v) { b.append(k).append('\n').append(v).append("\n\n"); }
}
