package com.nkt.mediaplayer;

import android.content.ContentUris;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Album art, video and photo thumbnails, loaded in the background and cached. */
public final class Thumbs {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** An eighth of the app's memory, so scrolling back up never reloads. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) Math.max(16L * 1024 * 1024, Math.min(Runtime.getRuntime().maxMemory() / 8, 160L * 1024 * 1024))) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private static final Bitmap NONE = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);

    private Thumbs() { }

    public static int placeholder(MediaInfo m) {
        if (m.image) return R.drawable.ic_image;
        if (m.video) return R.drawable.ic_movie;
        if (m.other) {
            int k = m.kind();
            return k == MediaInfo.TEXT ? R.drawable.ic_doc : k == MediaInfo.PDF ? R.drawable.ic_pdf : R.drawable.ic_file;
        }
        return R.drawable.ic_music;
    }

    public static void load(ImageView v, MediaInfo m) { load(v, m, 256); }

    public static void load(ImageView v, MediaInfo m, int size) {
        String key = m.uri.toString() + "#" + size;
        v.setTag(R.id.thumb_key, key);
        Bitmap cached = CACHE.get(key);
        if (cached != null && cached != NONE) { showBitmap(v, cached); return; }
        showPlaceholder(v, placeholder(m));
        if (cached == NONE) return;
        if (m.other || m.remote) return;                                                                   // documents, other devices
        if (m.id < 0 && !"file".equals(m.uri.getScheme()) && !"content".equals(m.uri.getScheme())) return;  // online link
        Context app = v.getContext().getApplicationContext();
        POOL.execute(() -> {
            if (!key.equals(v.getTag(R.id.thumb_key))) return;           // scrolled past before its turn
            File disk = diskFile(app, m, size);
            Bitmap b = null;
            boolean known = false;
            if (disk != null && disk.exists()) {
                known = true;
                if (disk.length() > 0) b = BitmapFactory.decodeFile(disk.getAbsolutePath());
            }
            if (!known) {
                b = fetch(app, m, size);
                saveDisk(disk, b);
            }
            final Bitmap got = b;
            CACHE.put(key, got == null ? NONE : got);
            if (got != null) MAIN.post(() -> { if (key.equals(v.getTag(R.id.thumb_key))) showBitmap(v, got); });
        });
    }

    /** Large art for the music screen. Calls back on the main thread (null = no art). */
    public static void big(Context c, MediaInfo m, Callback cb) {
        Context app = c.getApplicationContext();
        POOL.execute(() -> {
            Bitmap b = fetch(app, m, 720);
            MAIN.post(() -> cb.done(b));
        });
    }

    public interface Callback { void done(Bitmap b); }

    // ── Disk copy in the app's cache: the second start shows every thumbnail at once ──

    private static File diskDir;

    private static File diskFile(Context c, MediaInfo m, int size) {
        try {
            if (diskDir == null) {
                diskDir = new File(c.getCacheDir(), "thumbs");
                //noinspection ResultOfMethodCallIgnored
                diskDir.mkdirs();
            }
            return new File(diskDir, Lyrics.idOf(m.uri + "#" + size + "#" + m.dateMs + "#" + m.size) + ".jpg");
        } catch (Throwable t) { return null; }
    }

    private static void saveDisk(File f, Bitmap b) {
        if (f == null) return;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            if (b != null) b.compress(Bitmap.CompressFormat.JPEG, 85, out);   // empty file = "no picture"
        } catch (Throwable ignored) { }
    }

    public static void forget(MediaInfo m) {
        CACHE.remove(m.uri.toString() + "#256");
        CACHE.remove(m.uri.toString() + "#720");
        CACHE.remove(m.uri.toString() + "#120");
        CACHE.remove(m.uri.toString() + "#320");
    }

    public static void showPlaceholder(ImageView v, int res) {
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(v.getContext().getColor(R.color.accent)));
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    }

    public static void showBitmap(ImageView v, Bitmap b) {
        v.setImageTintList(null);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setImageBitmap(b);
    }

    @SuppressWarnings("deprecation")
    static Bitmap fetch(Context c, MediaInfo m, int size) {
        try {
            if ("file".equals(m.uri.getScheme()) || m.hidden) return fromFile(m, size);
            if (Build.VERSION.SDK_INT >= 29) {
                return c.getContentResolver().loadThumbnail(m.uri, new Size(size, size), null);
            }
            if (m.video) {
                return MediaStore.Video.Thumbnails.getThumbnail(c.getContentResolver(), m.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null);
            }
            if (m.image) {
                Bitmap b = MediaStore.Images.Thumbnails.getThumbnail(c.getContentResolver(), m.id,
                        MediaStore.Images.Thumbnails.MINI_KIND, null);
                return rotate(b, m.orientation);
            }
            if (m.albumId <= 0) return embeddedArt(c, m, size);
            Uri art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), m.albumId);
            try (InputStream in = c.getContentResolver().openInputStream(art)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = size > 300 ? 1 : 2;
                Bitmap b = BitmapFactory.decodeStream(in, null, o);
                return b != null ? b : embeddedArt(c, m, size);
            } catch (Exception e) {
                return embeddedArt(c, m, size);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap embeddedArt(Context c, MediaInfo m, int size) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, m.uri);
            byte[] pic = r.getEmbeddedPicture();
            return pic == null ? null : decodeSampled(pic, size);
        } catch (Exception e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    @SuppressWarnings("deprecation")
    private static Bitmap fromFile(MediaInfo m, int size) {
        String path = m.path.isEmpty() ? m.uri.getPath() : m.path;
        if (path == null) return null;
        try {
            if (m.image) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                o.inSampleSize = sample(o.outWidth, o.outHeight, size);
                o.inJustDecodeBounds = false;
                return BitmapFactory.decodeFile(path, o);
            }
            if (m.video) {
                if (Build.VERSION.SDK_INT >= 29) return ThumbnailUtils.createVideoThumbnail(new File(path), new Size(size, size), null);
                return ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND);
            }
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(path);
                byte[] pic = r.getEmbeddedPicture();
                return pic == null ? null : decodeSampled(pic, size);
            } finally {
                try { r.release(); } catch (Exception ignored) { }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    static Bitmap decodeSampled(byte[] data, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inSampleSize = sample(o.outWidth, o.outHeight, size);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    static int sample(int w, int h, int target) {
        int s = 1;
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2;
        return s;
    }

    static Bitmap rotate(Bitmap b, int degrees) {
        if (b == null || degrees == 0) return b;
        Matrix mx = new Matrix();
        mx.postRotate(degrees);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
    }
}
