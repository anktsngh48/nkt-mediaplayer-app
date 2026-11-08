package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.WallpaperManager;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;

/** "Set as wallpaper" for photos: home screen, lock screen, or both. */
public final class Wallpapers {

    private Wallpapers() { }

    public static void set(Activity a, MediaInfo m) {
        if (m == null || !m.image) return;
        String[] where = {a.getString(R.string.wallpaper_home), a.getString(R.string.wallpaper_lock), a.getString(R.string.wallpaper_both)};
        Dialogs.builder(a)
                .setTitle(R.string.set_wallpaper)
                .setItems(where, (d, w) -> {
                    final int which = w == 0 ? WallpaperManager.FLAG_SYSTEM : w == 1 ? WallpaperManager.FLAG_LOCK
                            : WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
                    new Thread(() -> {
                        boolean ok;
                        try (InputStream in = "file".equals(m.uri.getScheme()) ? new java.io.FileInputStream(m.uri.getPath())
                                : a.getContentResolver().openInputStream(m.uri)) {
                            WallpaperManager.getInstance(a).setStream(in, null, true, which);
                            ok = true;
                        } catch (Throwable t) { ok = false; }
                        final boolean done = ok;
                        new Handler(Looper.getMainLooper()).post(() ->
                                Ui.toastApp(a, a.getString(done ? R.string.wallpaper_set : R.string.wallpaper_failed)));
                    }).start();
                })
                .show();
    }
}
