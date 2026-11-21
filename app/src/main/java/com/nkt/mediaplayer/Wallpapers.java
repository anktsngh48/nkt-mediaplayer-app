package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.WallpaperManager;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;

/** "Set as wallpaper" for photos: home screen, lock screen, or both. */
public final class Wallpapers {

    private Wallpapers() { }

    /** Opens the adjust screen: move, zoom and turn the photo, then choose home screen, lock screen or both. */
    public static void set(Activity a, MediaInfo m) {
        if (m == null || !m.image) return;
        WallpaperActivity.open(a, m);
    }
}
