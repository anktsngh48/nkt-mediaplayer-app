package com.nkt.mediaplayer;

import android.content.Context;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The last library seen, saved to files/library.json, so the app shows real rows the moment it opens.
 * The fresh library is read in the background and replaces it (see MainActivity.load).
 */
final class LibraryCache {

    private LibraryCache() { }

    static final class Snapshot {
        final List<MediaInfo> audio = new ArrayList<>(), video = new ArrayList<>(), images = new ArrayList<>();
        long generation = -1;
        String settings = "";
        boolean empty() { return audio.isEmpty() && video.isEmpty() && images.isEmpty(); }
    }

    private static File file(Context c) { return new File(c.getFilesDir(), "library.json"); }

    /** Runs on a background thread. Returns null when there is no saved copy. */
    static Snapshot load(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            JSONObject o = new JSONObject(new String(data, 0, off, StandardCharsets.UTF_8));
            Snapshot s = new Snapshot();
            read(o.optJSONArray("audio"), s.audio);
            read(o.optJSONArray("video"), s.video);
            read(o.optJSONArray("images"), s.images);
            s.generation = o.optLong("gen", -1);
            s.settings = o.optString("settings");
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void read(JSONArray a, List<MediaInfo> into) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) into.add(MediaInfo.fromJson(o));
        }
    }

    /** Runs on a background thread. */
    static void save(Context c, List<MediaInfo> audio, List<MediaInfo> video, List<MediaInfo> images, long gen, String settings) {
        try {
            JSONObject o = new JSONObject();
            o.put("audio", write(audio));
            o.put("video", write(video));
            o.put("images", write(images));
            o.put("gen", gen);
            o.put("settings", settings);
            File tmp = new File(c.getFilesDir(), "library.json.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) { out.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file(c));
        } catch (Throwable ignored) { }
    }

    private static JSONArray write(List<MediaInfo> l) {
        JSONArray a = new JSONArray();
        for (MediaInfo m : l) a.put(m.toJson());
        return a;
    }

    /** Android's own change counter for the media index (Android 11+), or -1. */
    static long generation(Context c) {
        if (Build.VERSION.SDK_INT < 30) return -1;
        try { return MediaStore.getGeneration(c, MediaStore.VOLUME_EXTERNAL_PRIMARY); } catch (Throwable t) { return -1; }
    }
}
