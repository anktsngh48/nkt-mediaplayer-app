package com.nkt.mediaplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * "Save sound as audio": copies a video's sound track out as its own file, without re-encoding
 * (same quality, fast). AAC → .m4a, MP3 → .mp3, Opus → .ogg. Saved in Music/NKT Media Player.
 */
public final class AudioExtract {

    private AudioExtract() { }

    public static void save(Activity a, MediaInfo m) {
        if (m == null) return;
        Ui.toast(a, R.string.saving_sound);
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String result;
            try {
                result = a.getString(R.string.sound_saved, extract(a, m));
            } catch (Throwable t) {
                result = a.getString(R.string.sound_failed);
            }
            final String msg = result;
            ui.post(() -> Ui.toast(a, msg));
        }).start();
    }

    @SuppressLint("WrongConstant")
    private static String extract(Activity a, MediaInfo m) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        File tmp = null;
        try {
            ex.setDataSource(a, m.uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new Exception("no sound");
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            ex.selectTrack(track);

            String ext, outMime;
            if (MediaFormat.MIMETYPE_AUDIO_MPEG.equals(mime)) { ext = "mp3"; outMime = "audio/mpeg"; }
            else if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)) { ext = "m4a"; outMime = "audio/mp4"; }
            else if (MediaFormat.MIMETYPE_AUDIO_OPUS.equals(mime) && Build.VERSION.SDK_INT >= 29) { ext = "ogg"; outMime = "audio/ogg"; }
            else throw new Exception("unsupported " + mime);

            String base = clean(m.title);
            tmp = new File(a.getCacheDir(), "sound." + ext);
            int max = fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 1 << 20;
            ByteBuffer buf = ByteBuffer.allocate(Math.max(max, 256 * 1024));

            if ("mp3".equals(ext)) {
                // MP3 frames back to back make a normal .mp3 file
                try (OutputStream out = new FileOutputStream(tmp)) {
                    byte[] bytes = new byte[buf.capacity()];
                    while (true) {
                        buf.clear();
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) break;
                        buf.get(bytes, 0, n);
                        out.write(bytes, 0, n);
                        ex.advance();
                    }
                }
            } else {
                int container = "ogg".equals(ext) ? MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
                MediaMuxer mux = new MediaMuxer(tmp.getAbsolutePath(), container);
                int outTrack = mux.addTrack(fmt);
                mux.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (true) {
                    buf.clear();
                    int n = ex.readSampleData(buf, 0);
                    if (n < 0) break;
                    info.set(0, n, ex.getSampleTime(), (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    mux.writeSampleData(outTrack, buf, info);
                    ex.advance();
                }
                mux.stop();
                mux.release();
            }
            return publish(a, tmp, base + "." + ext, outMime);
        } finally {
            ex.release();
            if (tmp != null) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    /** Puts the file into Music/NKT Media Player so every music app finds it. Returns the folder shown to the user. */
    @SuppressWarnings("deprecation")
    private static String publish(Activity a, File src, String name, String mime) throws Exception {
        String folder = Environment.DIRECTORY_MUSIC + "/NKT Media Player";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Audio.Media.MIME_TYPE, mime);
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, folder);
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            Uri uri = a.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("insert failed");
            try (InputStream in = new FileInputStream(src); OutputStream out = a.getContentResolver().openOutputStream(uri)) {
                copy(in, out);
            }
            v.clear();
            v.put(MediaStore.Audio.Media.IS_PENDING, 0);
            a.getContentResolver().update(uri, v, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "NKT Media Player");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File out = new File(dir, name);
            for (int i = 2; out.exists(); i++) out = new File(dir, name.replaceFirst("(\\.[^.]+)$", " (" + i + ")$1"));
            try (InputStream in = new FileInputStream(src); OutputStream o = new FileOutputStream(out)) { copy(in, o); }
            MediaScannerConnection.scanFile(a, new String[]{out.getAbsolutePath()}, null, null);
        }
        return folder;
    }

    private static String clean(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t.isEmpty() ? "Video sound" : t;
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        if (out == null) throw new Exception("no output");
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }
}
