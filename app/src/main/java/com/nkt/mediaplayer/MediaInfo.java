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
        } catch (Exception ignored) { }
        return o;
    }

    public static MediaInfo fromJson(JSONObject o) {
        MediaInfo m = new MediaInfo(o.optLong("id", -1), Uri.parse(o.optString("uri")), o.optString("title"),
                o.optString("artist"), o.optString("album"), o.optLong("albumId", -1),
                o.optString("folder"), o.optLong("dur"), o.optBoolean("video"));
        m.hidden = o.optBoolean("hidden");
        m.path = o.optString("path");
        return m;
    }
}
