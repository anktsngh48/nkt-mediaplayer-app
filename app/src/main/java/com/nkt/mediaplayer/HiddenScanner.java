package com.nkt.mediaplayer;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds media inside hidden folders (names starting with a dot) and folders marked ".nomedia",
 * which Android's media index leaves out. Needs "All files access" on Android 11+.
 */
public final class HiddenScanner {

    private HiddenScanner() { }

    private static final Set<String> AUDIO = new HashSet<>(java.util.Arrays.asList(
            "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "wma", "mka"));
    private static final Set<String> VIDEO = new HashSet<>(java.util.Arrays.asList(
            "mp4", "mkv", "webm", "3gp", "avi", "mov", "ts", "m4v", "flv", "wmv"));
    private static final Set<String> IMAGE = new HashSet<>(java.util.Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp"));

    public static class Result {
        public final List<MediaInfo> audio = new ArrayList<>();
        public final List<MediaInfo> video = new ArrayList<>();
        public final List<MediaInfo> images = new ArrayList<>();
    }

    /** True when the app may read every folder (needed for hidden files and NKT Share). */
    public static boolean hasAllFilesAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Opens Android's "All files access" page for this app (Android 11+). */
    public static Intent allFilesAccessIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + c.getPackageName()));
        if (i.resolveActivity(c.getPackageManager()) == null)
            i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        return i;
    }

    public static Result scan() {
        Result r = new Result();
        File root = Environment.getExternalStorageDirectory();
        walk(root, false, 0, r);
        Collections.sort(r.audio, (a, b) -> a.title.compareToIgnoreCase(b.title));
        Collections.sort(r.video, (a, b) -> Long.compare(b.dateMs, a.dateMs));
        Collections.sort(r.images, (a, b) -> Long.compare(b.dateMs, a.dateMs));
        return r;
    }

    private static void walk(File dir, boolean insideHidden, int depth, Result r) {
        if (depth > 12) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        String name = dir.getName();
        if (depth == 1 && name.equals("Android")) return;            // app data: not accessible anyway
        boolean hiddenHere = insideHidden || (depth > 0 && name.startsWith("."));
        if (!hiddenHere) {
            for (File f : files) if (f.getName().equals(".nomedia")) { hiddenHere = true; break; }
        }
        for (File f : files) {
            if (f.isDirectory()) {
                walk(f, hiddenHere, depth + 1, r);
            } else if (hiddenHere) {
                add(f, name, r);
            }
        }
    }

    private static void add(File f, String folder, Result r) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot <= 0) return;
        String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
        boolean a = AUDIO.contains(ext), v = VIDEO.contains(ext), im = IMAGE.contains(ext);
        if (!a && !v && !im) return;
        String base = n.substring(0, dot);
        long dur = 0;
        String artist = "", album = "";
        if (a || v) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(f.getAbsolutePath());
                String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                dur = d == null ? 0 : Long.parseLong(d);
                if (a) {
                    String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    if (t != null && !t.isEmpty()) base = t;
                    String ar = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    if (ar != null) artist = ar;
                    String al = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    if (al != null) album = al;
                }
            } catch (Exception ignored) {
            } finally {
                try { mmr.release(); } catch (Exception ignored) { }
            }
            if (a && dur > 0 && dur < 10000) return;      // skip tiny clips, like the main library does
        }
        MediaInfo m = new MediaInfo(-1, Uri.fromFile(f), base, artist, album, -1, folder, dur, v);
        m.hidden = true;
        m.image = im;
        m.dateMs = f.lastModified();
        m.size = f.length();
        m.path = f.getAbsolutePath();
        m.fileName = n;
        if (a) r.audio.add(m); else if (v) r.video.add(m); else r.images.add(m);
    }
}
