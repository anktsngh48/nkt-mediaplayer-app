package com.nkt.mediaplayer;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Album art downloaded from the internet for songs that have none. Kept inside the app (the song file is
 * only changed if the user asks for that), one small JPEG per song.
 */
public final class ArtStore {

    private ArtStore() { }

    /** Goes up whenever art is saved, so open screens know to look again. */
    public static final AtomicInteger version = new AtomicInteger();

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File file(Context c, MediaInfo m) { return new File(dir(c), Lyrics.idOf(m.key()) + ".jpg"); }

    public static boolean has(Context c, MediaInfo m) { return file(c, m).length() > 0; }

    /** The saved picture, scaled down to about `size` pixels, or null. */
    public static Bitmap load(Context c, MediaInfo m, int size) {
        File f = file(c, m);
        if (f.length() == 0) return null;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            o.inSampleSize = Thumbs.sample(o.outWidth, o.outHeight, size);
            o.inJustDecodeBounds = false;
            return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Saves JPEG/PNG bytes (re-encoded as a JPEG of at most 1000 px). */
    public static boolean save(Context c, MediaInfo m, byte[] data) {
        try {
            Bitmap b = Thumbs.decodeSampled(data, 1000);
            if (b == null) return false;
            try (FileOutputStream out = new FileOutputStream(file(c, m))) { b.compress(Bitmap.CompressFormat.JPEG, 90, out); }
            Prefs.sp(c).edit().remove("art_miss_" + Lyrics.idOf(m.key())).apply();
            Thumbs.refresh(c, m);
            version.incrementAndGet();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void remove(Context c, MediaInfo m) {
        //noinspection ResultOfMethodCallIgnored
        file(c, m).delete();
        Thumbs.refresh(c, m);
        version.incrementAndGet();
    }

    /** A song the internet had nothing for is not asked about again for a week. */
    public static boolean recentlyMissed(Context c, MediaInfo m) {
        long t = Prefs.sp(c).getLong("art_miss_" + Lyrics.idOf(m.key()), 0);
        return t > 0 && System.currentTimeMillis() - t < 7L * 24 * 3600 * 1000;
    }

    public static void markMiss(Context c, MediaInfo m) {
        Prefs.sp(c).edit().putLong("art_miss_" + Lyrics.idOf(m.key()), System.currentTimeMillis()).apply();
    }
}
