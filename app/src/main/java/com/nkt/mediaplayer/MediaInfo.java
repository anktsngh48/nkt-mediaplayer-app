package com.nkt.mediaplayer;

import android.net.Uri;

import org.json.JSONObject;

/** One song, video, photo or online link. */
public class MediaInfo {
    public final long id;            // MediaStore id, or -1 for links and hidden files
    public final Uri uri;
    public final String title;
    public final String artist;
    public final String album;
    public final long albumId;
    public final String folder;
    public final long durationMs;
    public final boolean video;

    // Extra details (filled in where known)
    public boolean image;            // a photo (gallery only)
    public boolean hidden;           // found in a hidden / .nomedia folder
    public long dateMs;              // taken or added
    public int year;
    public String genre = "";
    public long size;
    public int orientation;          // photos: rotation in degrees
    public String path = "";         // file path when known
    public String fileName = "";
    public boolean other;            // any other file (Folders page, other devices): text, PDF, apps…
    public boolean remote;           // on another device (Other devices)

    public MediaInfo(long id, Uri uri, String title, String artist, String album,
                     long albumId, String folder, long durationMs, boolean video) {
        this.id = id;
        this.uri = uri;
        this.title = title == null || title.isEmpty() ? "Unknown" : title;
        this.artist = artist == null || artist.equals("<unknown>") ? "" : artist;
        this.album = album == null ? "" : album;
        this.albumId = albumId;
        this.folder = folder == null || folder.isEmpty() ? "Other" : folder;
        this.durationMs = durationMs;
        this.video = video;
    }

    /** An online link or a file opened from another app. */
    public static MediaInfo link(Uri uri, String title, boolean video) {
        return new MediaInfo(-1, uri, title, "", "", -1, "Links", 0, video);
    }

    public String key() { return uri.toString(); }

    public String subtitle() {
        String d = durationMs > 0 ? Ui.time(durationMs) : "";
        String pre = hidden ? "Hidden · " : "";
        if (other) return pre + folder;
        if (image) return pre + folder;
        if (video) return pre + folder + (d.isEmpty() ? "" : " · " + d);
        if (artist.isEmpty()) return pre + d;
        return pre + artist + (d.isEmpty() ? "" : " · " + d);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("uri", uri.toString());
            o.put("title", title);
            o.put("artist", artist);
            o.put("album", album);
            o.put("albumId", albumId);
            o.put("folder", folder);
            o.put("dur", durationMs);
            o.put("video", video);
            o.put("hidden", hidden);
            o.put("path", path);
            o.put("image", image);
            o.put("name", fileName);
            o.put("date", dateMs);
            o.put("size", size);
            if (year != 0) o.put("year", year);
            if (!genre.isEmpty()) o.put("genre", genre);
            if (orientation != 0) o.put("orient", orientation);
            if (other) o.put("other", true);
        } catch (Exception ignored) { }
        return o;
    }

    public static MediaInfo fromJson(JSONObject o) {
        MediaInfo m = new MediaInfo(o.optLong("id", -1), Uri.parse(o.optString("uri")), o.optString("title"),
                o.optString("artist"), o.optString("album"), o.optLong("albumId", -1),
                o.optString("folder"), o.optLong("dur"), o.optBoolean("video"));
        m.hidden = o.optBoolean("hidden");
        m.path = o.optString("path");
        m.image = o.optBoolean("image");
        m.fileName = o.optString("name");
        m.dateMs = o.optLong("date");
        m.size = o.optLong("size");
        m.year = o.optInt("year");
        m.genre = o.optString("genre");
        m.orientation = o.optInt("orient");
        m.other = o.optBoolean("other");
        return m;
    }

    // ── Files found by browsing (Folders page) ──

    public static final int AUDIO = 0, VIDEO = 1, IMAGE = 2, TEXT = 3, PDF = 4, OTHER = 5;

    private static final java.util.Set<String> AUDIO_EXT = new java.util.HashSet<>(java.util.Arrays.asList(
            "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "wma", "mka", "mid", "midi", "3ga"));
    private static final java.util.Set<String> VIDEO_EXT = new java.util.HashSet<>(java.util.Arrays.asList(
            "mp4", "mkv", "webm", "3gp", "avi", "mov", "ts", "m4v", "flv", "wmv", "mpg", "mpeg"));
    private static final java.util.Set<String> IMAGE_EXT = new java.util.HashSet<>(java.util.Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp"));
    private static final java.util.Set<String> TEXT_EXT = new java.util.HashSet<>(java.util.Arrays.asList(
            "txt", "md", "log", "csv", "json", "xml", "html", "htm", "srt", "lrc", "ini", "cfg", "conf", "yml", "yaml",
            "properties", "java", "kt", "py", "js", "css", "sh", "nfo", "vtt", "tsv", "sql"));

    public static String ext(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** AUDIO, VIDEO, IMAGE, TEXT, PDF or OTHER, from the file name. */
    public static int kindOf(String name) {
        String e = ext(name);
        if (AUDIO_EXT.contains(e)) return AUDIO;
        if (VIDEO_EXT.contains(e)) return VIDEO;
        if (IMAGE_EXT.contains(e)) return IMAGE;
        if (TEXT_EXT.contains(e)) return TEXT;
        if ("pdf".equals(e)) return PDF;
        return OTHER;
    }

    public int kind() {
        if (image) return IMAGE;
        if (video) return VIDEO;
        if (other) { int k = kindOf(fileName.isEmpty() ? title : fileName); return k <= IMAGE ? OTHER : k; }
        return AUDIO;
    }

    /** A file on this phone, typed by its name. */
    public static MediaInfo fromFile(java.io.File f) {
        String name = f.getName();
        int k = kindOf(name);
        String title = k <= IMAGE ? stripExt(name) : name;
        java.io.File parent = f.getParentFile();
        MediaInfo m = new MediaInfo(-1, Uri.fromFile(f), title, "", "", -1,
                parent == null ? "" : parent.getName(), 0, k == VIDEO);
        m.image = k == IMAGE;
        m.other = k > IMAGE;
        m.path = f.getAbsolutePath();
        m.fileName = name;
        m.size = f.length();
        m.dateMs = f.lastModified();
        m.hidden = name.startsWith(".");
        return m;
    }

    /** A file on another device (Other devices); uri is ftp://host:port/path. */
    public static MediaInfo remoteFile(Uri uri, String name, long size, long date) {
        int k = kindOf(name);
        MediaInfo m = new MediaInfo(-1, uri, k <= IMAGE ? stripExt(name) : name, "", "", -1, "", 0, k == VIDEO);
        m.image = k == IMAGE;
        m.other = k > IMAGE;
        m.remote = true;
        m.fileName = name;
        m.size = size;
        m.dateMs = date;
        return m;
    }

    static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 && n.length() - dot <= 6 ? n.substring(0, dot) : n;
    }
}
