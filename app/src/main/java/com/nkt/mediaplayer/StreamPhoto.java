package com.nkt.mediaplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/** Stream: a photo the host is looking at, cut down to a size that is quick to send, the right way up. */
final class StreamPhoto {

    private StreamPhoto() { }

    /** A JPEG no bigger than {@code max} pixels on its long side, or null. Not on the main thread. */
    static byte[] jpeg(Context c, MediaInfo m, int max) {
        try {
            Bitmap b = decode(c, m, max);
            if (b == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 86, out);
            b.recycle();
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap decode(Context c, MediaInfo m, int max) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source src = "file".equals(m.uri.getScheme()) && m.uri.getPath() != null
                    ? ImageDecoder.createSource(new File(m.uri.getPath()))
                    : ImageDecoder.createSource(c.getContentResolver(), m.uri);
            return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float f = Math.min(1f, (float) max / Math.max(1, Math.max(w, h)));
                decoder.setTargetSize(Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = open(c, m)) { BitmapFactory.decodeStream(in, null, o); }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = open(c, m)) { b = BitmapFactory.decodeStream(in, null, o); }
        if (b == null) return null;
        int deg = m.orientation;
        if (deg == 0) {
            try (InputStream in = open(c, m)) {
                android.media.ExifInterface ex = new android.media.ExifInterface(in);
                int e = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                deg = e == 6 ? 90 : e == 3 ? 180 : e == 8 ? 270 : 0;
            } catch (Throwable ignored) { }
        }
        if (deg != 0) {
            Matrix mx = new Matrix();
            mx.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    private static InputStream open(Context c, MediaInfo m) throws Exception {
        if ("file".equals(m.uri.getScheme()) && m.uri.getPath() != null) return new java.io.FileInputStream(m.uri.getPath());
        return c.getContentResolver().openInputStream(m.uri);
    }
}
