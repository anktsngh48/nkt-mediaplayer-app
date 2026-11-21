package com.nkt.mediaplayer;

import android.app.Activity;

/** "Set as wallpaper" for photos: the photo is placed and zoomed first, then home screen, lock screen, or both. */
public final class Wallpapers {

    private Wallpapers() { }

    public static void set(Activity a, MediaInfo m) {
        if (m == null || !m.image) return;
        WallpaperActivity.open(a, m);
    }
}
