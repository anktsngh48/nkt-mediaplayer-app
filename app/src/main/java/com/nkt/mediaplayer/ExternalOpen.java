package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;

/**
 * A song, video or PDF opened from another app ("Open with" / "Share"): it opens straight away, and Back goes to
 * the file explorer, in the folder the file is in with the file marked, instead of straight back to the other app.
 */
public final class ExternalOpen {

    private ExternalOpen() { }

    /** Put on intents the app sends to its own screens for a shared file. */
    public static final String EXTRA = "nkt_external";
    /** Sent to the library: the path of the file to show in the explorer. */
    public static final String REVEAL = "nkt_reveal";

    public static boolean isExternal(Intent i) {
        return i != null && (i.getBooleanExtra(EXTRA, false) || (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null));
    }

    /** Back: show the explorer at the file's folder. Returns true when it took over. */
    public static boolean back(Activity a, Uri uri) {
        if (!isExternal(a.getIntent())) return false;
        String path = pathOf(a, uri);
        a.startActivity(new Intent(a, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(REVEAL, path == null ? "" : path));
        a.finish();
        return true;
    }

    /** The place of the file on the phone, when it can be worked out (null for files that only live in another app). */
    public static String pathOf(Context c, Uri u) {
        if (u == null) return null;
        try {
            if ("file".equals(u.getScheme())) return u.getPath();
            if (!"content".equals(u.getScheme())) return null;
            if (DocumentsContract.isDocumentUri(c, u) && "com.android.externalstorage.documents".equals(u.getAuthority())) {
                String id = DocumentsContract.getDocumentId(u);                     // "primary:Music/song.mp3"
                int i = id.indexOf(':');
                if (i > 0) {
                    String vol = id.substring(0, i), rel = id.substring(i + 1);
                    File root = "primary".equals(vol) ? Environment.getExternalStorageDirectory() : new File("/storage/" + vol);
                    File f = new File(root, rel);
                    if (f.exists()) return f.getAbsolutePath();
                }
            }
            try (Cursor q = c.getContentResolver().query(u, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
                if (q != null && q.moveToFirst()) {
                    String p = q.getString(0);
                    if (p != null && new File(p).exists()) return p;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
