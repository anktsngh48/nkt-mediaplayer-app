package com.nkt.mediaplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;


/** The picture shown in the notification, lock screen and music screen when a song has no album art. */
public final class AppLogo {

    private AppLogo() { }

    /** The NKT logo as a square bitmap of about `size` pixels. */
    public static Bitmap bitmap(Context c, int size) {
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
}
