package com.nkt.mediaplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** The picture shown in the notification, lock screen and music screen when a song has no album art. */
public final class AppLogo {

    private AppLogo() { }

    private static File file(Context c) { return new File(c.getFilesDir(), "app_logo.png"); }

    /** True when the user chose their own picture. */
    public static boolean custom(Context c) { return file(c).exists(); }

    /** The chosen picture, or the NKT logo, as a square bitmap of about `size` pixels. */
    public static Bitmap bitmap(Context c, int size) {
        try {
            if (custom(c)) {
                Bitmap b = BitmapFactory.decodeFile(file(c).getAbsolutePath());
                if (b != null) return b.getWidth() == size ? b : Bitmap.createScaledBitmap(b, size, size, true);
            }
        } catch (Throwable ignored) { }
        try {
            Drawable d = c.getDrawable(R.drawable.art_logo);
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(cv);
            return b;
        } catch (Throwable t) {
            return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        }
    }

    /** Crops the picked image to a square, saves it inside the app. Call off the main thread. */
    public static boolean save(Context c, Uri src) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(src)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= 512) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = c.getContentResolver().openInputStream(src)) { b = BitmapFactory.decodeStream(in, null, o); }
            if (b == null) return false;
            int side = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
            Bitmap out = Bitmap.createScaledBitmap(sq, 512, 512, true);
            try (FileOutputStream f = new FileOutputStream(file(c))) { out.compress(Bitmap.CompressFormat.PNG, 100, f); }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void reset(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
    }
}
