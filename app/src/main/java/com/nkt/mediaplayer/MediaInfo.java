package com.nkt.mediaplayer;

import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/** One song, video or photo. */
public class MediaInfo {
    public static final int AUDIO = 0, VIDEO = 1, IMAGE = 2;

    public long id, albumId, duration, size, date;
    public int kind;
    public String uri = "", title = "", artist = "", album = "", folder = "", path = "", name = "", mime = "", genre = "";
    public int year;
    public boolean hidden;

    public boolean isVideo() { return kind == VIDEO; }
    public boolean isImage() { return kind == IMAGE; }
    public boolean isAudio() { return kind == AUDIO; }
    public boolean isOnline() { return uri.startsWith("http"); }
    public Uri u() { return Uri.parse(uri); }

    public String artistOrUnknown() { return artist == null || artist.isEmpty() || artist.equals("<unknown>") ? "Unknown artist" : artist; }

    public String subtitle() {
        String pre = hidden ? "Hidden · " : "";
        if (kind == AUDIO) return pre + artistOrUnknown() + " · " + Ui.time(duration);
        if (kind == VIDEO) return pre + folder + " · " + Ui.time(duration);
        return pre + folder;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("uri", uri).put("title", title).put("artist", artist).put("album", album)
                    .put("albumId", albumId).put("folder", folder).put("dur", duration).put("kind", kind)
                    .put("hidden", hidden).put("path", path).put("name", name).put("date", date).put("size", size)
                    .put("mime", mime).put("genre", genre).put("year", year);
        } catch (Exception ignored) {}
        return o;
    }

    public static MediaInfo fromJson(JSONObject o) {
        MediaInfo m = new MediaInfo();
        m.id = o.optLong("id");
        m.uri = o.optString("uri");
        m.title = o.optString("title");
        m.artist = o.optString("artist");
        m.album = o.optString("album");
        m.albumId = o.optLong("albumId");
        m.folder = o.optString("folder");
        m.duration = o.optLong("dur");
        m.kind = o.has("kind") ? o.optInt("kind") : (o.optBoolean("image") ? IMAGE : o.optBoolean("video") ? VIDEO : AUDIO);
        m.hidden = o.optBoolean("hidden");
        m.path = o.optString("path");
        m.name = o.optString("name");
        m.date = o.optLong("date");
        m.size = o.optLong("size");
        m.mime = o.optString("mime");
        m.genre = o.optString("genre");
        m.year = o.optInt("year");
        return m;
    }

    /** Build an item for a plain file (folders browser, hidden scan, shared files). */
    public static MediaInfo fromFile(File f) {
        MediaInfo m = new MediaInfo();
        m.uri = Uri.fromFile(f).toString();
        m.path = f.getAbsolutePath();
        m.name = f.getName();
        int dot = m.name.lastIndexOf('.');
        m.title = dot > 0 ? m.name.substring(0, dot) : m.name;
        File p = f.getParentFile();
        m.folder = p != null ? p.getName() : "";
        m.size = f.length();
        m.date = f.lastModified();
        m.kind = kindOf(m.name);
        m.mime = mimeOf(m.name);
        return m;
    }

    public static final String[] AUDIO_EXT = {"mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "wma", "mka", "mid", "midi"};
    public static final String[] VIDEO_EXT = {"mp4", "mkv", "webm", "3gp", "avi", "mov", "ts", "m4v", "flv", "wmv", "mpg", "mpeg"};
    public static final String[] IMAGE_EXT = {"jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp"};
    public static final String[] TEXT_EXT = {"txt", "md", "json", "xml", "csv", "log", "html", "htm", "css", "js", "java", "kt", "py",
            "c", "cpp", "h", "ini", "conf", "cfg", "yml", "yaml", "srt", "lrc", "sh", "properties", "gradle", "ts", "tsx", "sql", "vtt"};

    public static String ext(String name) {
        int d = name.lastIndexOf('.');
        return d < 0 ? "" : name.substring(d + 1).toLowerCase(Locale.US);
    }

    static boolean in(String e, String[] list) {
        for (String s : list) if (s.equals(e)) return true;
        return false;
    }

    /** -1 = not media. */
    public static int kindOf(String name) {
        String e = ext(name);
        if (in(e, AUDIO_EXT)) return AUDIO;
        if (in(e, VIDEO_EXT) && !e.equals("ts")) return VIDEO;
        if (e.equals("ts")) return VIDEO;
        if (in(e, IMAGE_EXT)) return IMAGE;
        return -1;
    }

    public static boolean isText(String name) { return in(ext(name), TEXT_EXT) && !ext(name).equals("ts"); }

    public static String mimeOf(String name) {
        String e = ext(name);
        String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        if (m != null) return m;
        if (e.equals("apk")) return "application/vnd.android.package-archive";
        if (in(e, AUDIO_EXT)) return "audio/*";
        if (in(e, VIDEO_EXT)) return "video/*";
        if (in(e, IMAGE_EXT)) return "image/*";
        if (isText(name)) return "text/plain";
        return "*/*";
    }
}
