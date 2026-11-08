package com.nkt.mediaplayer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Everything the app remembers: settings, playlists, favourites, history, play counts. */
public final class Store {
    private Store() {}

    public static final String FAV_SONGS = "♥ Favourite songs", FAV_VIDEOS = "♥ Favourite videos", FAV_PHOTOS = "♥ Favourite photos";

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean bool(Context c, String k, boolean def) { return prefs(c).getBoolean(k, def); }
    public static void put(Context c, String k, boolean v) { prefs(c).edit().putBoolean(k, v).apply(); }
    public static int num(Context c, String k, int def) { return prefs(c).getInt(k, def); }
    public static void put(Context c, String k, int v) { prefs(c).edit().putInt(k, v).apply(); }
    public static String str(Context c, String k, String def) { return prefs(c).getString(k, def); }
    public static void put(Context c, String k, String v) { prefs(c).edit().putString(k, v).apply(); }

    // ---------- play counts ----------
    public static void countPlay(Context c, String uri) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("plays", Context.MODE_PRIVATE);
        p.edit().putInt(uri, p.getInt(uri, 0) + 1).apply();
    }

    public static int plays(Context c, String uri) {
        return c.getApplicationContext().getSharedPreferences("plays", Context.MODE_PRIVATE).getInt(uri, 0);
    }

    // ---------- playlists ----------
    private static JSONObject all(Context c) {
        try {
            return new JSONObject(c.getApplicationContext().getSharedPreferences("playlists", Context.MODE_PRIVATE).getString("all", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void save(Context c, JSONObject o) {
        c.getApplicationContext().getSharedPreferences("playlists", Context.MODE_PRIVATE).edit().putString("all", o.toString()).apply();
    }

    public static List<String> playlistNames(Context c) {
        List<String> favs = new ArrayList<>(), rest = new ArrayList<>();
        Iterator<String> it = all(c).keys();
        while (it.hasNext()) {
            String n = it.next();
            if (n.startsWith("♥")) favs.add(n); else rest.add(n);
        }
        Collections.sort(favs);
        Collections.sort(rest, String.CASE_INSENSITIVE_ORDER);
        favs.addAll(rest);
        return favs;
    }

    public static List<MediaInfo> playlist(Context c, String name) {
        List<MediaInfo> out = new ArrayList<>();
        JSONArray a = all(c).optJSONArray(name);
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(MediaInfo.fromJson(a.optJSONObject(i)));
        return out;
    }

    public static boolean createPlaylist(Context c, String name) {
        JSONObject o = all(c);
        if (o.has(name)) return false;
        try { o.put(name, new JSONArray()); } catch (Exception ignored) {}
        save(c, o);
        stamp(c, name);
        return true;
    }

    private static void stamp(Context c, String name) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("playlists", Context.MODE_PRIVATE);
        if (!p.contains("t_" + name)) p.edit().putLong("t_" + name, System.currentTimeMillis()).apply();
    }

    public static long created(Context c, String name) {
        return c.getApplicationContext().getSharedPreferences("playlists", Context.MODE_PRIVATE).getLong("t_" + name, 0);
    }

    public static void deletePlaylist(Context c, String name) {
        JSONObject o = all(c);
        o.remove(name);
        save(c, o);
    }

    public static void addTo(Context c, String name, List<MediaInfo> items) {
        JSONObject o = all(c);
        JSONArray a = o.optJSONArray(name);
        if (a == null) { a = new JSONArray(); stamp(c, name); }
        for (MediaInfo m : items) {
            boolean has = false;
            for (int i = 0; i < a.length(); i++) if (m.uri.equals(a.optJSONObject(i).optString("uri"))) { has = true; break; }
            if (!has) a.put(m.toJson());
        }
        try { o.put(name, a); } catch (Exception ignored) {}
        save(c, o);
    }

    public static void removeFrom(Context c, String name, String uri) {
        JSONObject o = all(c);
        JSONArray a = o.optJSONArray(name), b = new JSONArray();
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) if (!uri.equals(a.optJSONObject(i).optString("uri"))) b.put(a.optJSONObject(i));
        try { o.put(name, b); } catch (Exception ignored) {}
        save(c, o);
    }

    public static void removeEverywhere(Context c, String uri) {
        for (String n : playlistNames(c)) removeFrom(c, n, uri);
    }

    public static String favName(MediaInfo m) { return m.isVideo() ? FAV_VIDEOS : m.isImage() ? FAV_PHOTOS : FAV_SONGS; }

    public static boolean isFav(Context c, MediaInfo m) {
        for (MediaInfo x : playlist(c, favName(m))) if (x.uri.equals(m.uri)) return true;
        return false;
    }

    /** Returns true when now a favourite. */
    public static boolean toggleFav(Context c, MediaInfo m) {
        if (isFav(c, m)) { removeFrom(c, favName(m), m.uri); return false; }
        List<MediaInfo> l = new ArrayList<>();
        l.add(m);
        addTo(c, favName(m), l);
        return true;
    }

    // ---------- history ----------
    public static void addHistory(Context c, MediaInfo m) {
        if (m == null || !bool(c, "history_on", true)) return;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("history", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(p.getString("list", "[]"));
            if (a.length() > 0 && m.uri.equals(a.optJSONObject(0).optJSONObject("m").optString("uri"))) return;
            JSONArray b = new JSONArray();
            b.put(new JSONObject().put("m", m.toJson()).put("t", System.currentTimeMillis()));
            for (int i = 0; i < a.length() && b.length() < 300; i++) b.put(a.optJSONObject(i));
            p.edit().putString("list", b.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static List<Object[]> history(Context c) {
        List<Object[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(c.getApplicationContext().getSharedPreferences("history", Context.MODE_PRIVATE).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                out.add(new Object[]{MediaInfo.fromJson(o.optJSONObject("m")), o.optLong("t")});
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void clearHistory(Context c) {
        c.getApplicationContext().getSharedPreferences("history", Context.MODE_PRIVATE).edit().clear().apply();
    }

    // ---------- saved FTP devices ----------
    public static JSONArray devices(Context c) {
        try { return new JSONArray(str(c, "devices", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    public static void saveDevices(Context c, JSONArray a) { put(c, "devices", a.toString()); }
}
