package com.nkt.mediaplayer;

import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads album art, video frames and photo thumbnails off the main thread, with a memory cache. */
public final class Thumbs {
    private Thumbs() {}

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Bitmap NONE = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);

    public static void load(ImageView iv, MediaInfo m, int px, String placeholderIcon) {
        String key = m.uri + "@" + px;
        iv.setTag(key);
        Bitmap b = CACHE.get(key);
        if (b != null && b != NONE) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(b);
            return;
        }
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setImageDrawable(new Icon(iv.getContext(), placeholderIcon, Ui.MUTED));
        if (b == NONE) return;
        Context c = iv.getContext().getApplicationContext();
        POOL.execute(() -> {
            Bitmap r = null;
            try { r = make(c, m, px); } catch (Throwable ignored) {}
            CACHE.put(key, r == null ? NONE : r);
            final Bitmap fr = r;
            if (fr != null) iv.post(() -> {
                if (key.equals(iv.getTag())) {
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setImageBitmap(fr);
                }
            });
        });
    }

    public static void forget(String uri) { CACHE.evictAll(); }

    @SuppressWarnings("deprecation")
    static Bitmap make(Context c, MediaInfo m, int px) throws Exception {
        Uri u = m.u();
        boolean content = "content".equals(u.getScheme());
        if (m.isAudio()) return art(c, m, px);
        if (content && Build.VERSION.SDK_INT >= 29) return c.getContentResolver().loadThumbnail(u, new Size(px, px), null);
        if (m.isVideo()) {
            if (m.isOnline()) return null;
            if (!m.path.isEmpty() && Build.VERSION.SDK_INT < 29) {
                Bitmap b = ThumbnailUtils.createVideoThumbnail(m.path, MediaStore.Images.Thumbnails.MINI_KIND);
                if (b != null) return b;
            }
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                if (content) r.setDataSource(c, u); else r.setDataSource(m.path.isEmpty() ? u.getPath() : m.path);
                Bitmap b = r.getFrameAtTime(1_000_000);
                return b == null ? null : scale(b, px);
            } finally { r.release(); }
        }
        return decode(c, u, px);
    }

    public static Bitmap art(Context c, MediaInfo m, int px) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if ("content".equals(m.u().getScheme())) r.setDataSource(c, m.u());
            else if (m.isOnline()) return null;
            else r.setDataSource(m.path.isEmpty() ? m.u().getPath() : m.path);
            byte[] pic = r.getEmbeddedPicture();
            if (pic != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                o.inSampleSize = sample(o.outWidth, o.outHeight, px);
                o.inJustDecodeBounds = false;
                return BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            }
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        if (m.albumId > 0 && Build.VERSION.SDK_INT < 29) {
            try {
                Uri a = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), m.albumId);
                return decode(c, a, px);
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Decodes a picture with sampling and EXIF rotation applied. */
    public static Bitmap decode(Context c, Uri u, int px) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
        o.inSampleSize = sample(o.outWidth, o.outHeight, px);
        o.inJustDecodeBounds = false;
        Bitmap b;
        try (InputStream in = c.getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o); }
        if (b == null) return null;
        int rot = 0;
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in != null) {
                ExifInterface e = new ExifInterface(in);
                int or = e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            }
        } catch (Exception ignored) {}
        if (rot != 0) {
            Matrix mx = new Matrix();
            mx.postRotate(rot);
            b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
        }
        return b;
    }

    static int sample(int w, int h, int px) {
        int s = 1;
        while (w / (s * 2) >= px && h / (s * 2) >= px) s *= 2;
        return s;
    }

    static Bitmap scale(Bitmap b, int px) {
        float f = (float) px / Math.max(1, Math.min(b.getWidth(), b.getHeight()));
        if (f >= 1) return b;
        return Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * f)), Math.max(1, (int) (b.getHeight() * f)), true);
    }
}
