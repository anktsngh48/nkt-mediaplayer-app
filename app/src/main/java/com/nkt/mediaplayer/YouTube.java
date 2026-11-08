package com.nkt.mediaplayer;

import android.net.Uri;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognises YouTube links (watch, youtu.be, shorts, live, embed, music). */
public final class YouTube {

    private YouTube() { }

    private static final Pattern ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/|embed/|v/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    public static boolean isYouTube(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(java.util.Locale.ROOT);
        return u.contains("youtube.com/") || u.contains("youtu.be/");
    }

    /** The 11-character video id, or null. */
    public static String videoId(String url) {
        if (url == null) return null;
        Matcher m = ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static String playlistId(String url) {
        try {
            String l = Uri.parse(url).getQueryParameter("list");
            return l == null || l.isEmpty() ? null : l;
        } catch (Exception e) { return null; }
    }

    /** Start time in seconds from t= / start= (e.g. 90, 1m30s), or 0. */
    public static int startSeconds(String url) {
        try {
            Uri u = Uri.parse(url);
            String t = u.getQueryParameter("t");
            if (t == null) t = u.getQueryParameter("start");
            if (t == null) return 0;
            t = t.toLowerCase(java.util.Locale.ROOT);
            if (t.matches("\\d+s?")) return Integer.parseInt(t.replace("s", ""));
            int total = 0;
            Matcher m = Pattern.compile("(\\d+)([hms])").matcher(t);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                total += m.group(2).equals("h") ? n * 3600 : m.group(2).equals("m") ? n * 60 : n;
            }
            return total;
        } catch (Exception e) { return 0; }
    }
}
