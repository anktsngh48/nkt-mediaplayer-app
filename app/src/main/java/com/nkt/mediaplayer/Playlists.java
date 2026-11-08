package com.nkt.mediaplayer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Saved playlists, kept on the phone. */
public final class Playlists {

    private Playlists() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("playlists", Context.MODE_PRIVATE);
    }

    private static JSONObject all(Context c) {
        try { return new JSONObject(sp(c).getString("all", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static void save(Context c, JSONObject o) {
        sp(c).edit().putString("all", o.toString()).apply();
    }

    public static List<String> names(Context c) {
        JSONObject o = all(c);
        List<String> out = new ArrayList<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) out.add(it.next());
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public static boolean create(Context c, String name) {
        JSONObject o = all(c);
        if (name == null || name.trim().isEmpty() || o.has(name)) return false;
        try { o.put(name.trim(), new JSONArray()); } catch (Exception e) { return false; }
        save(c, o);
        return true;
    }

    public static void delete(Context c, String name) {
        JSONObject o = all(c);
        o.remove(name);
        save(c, o);
    }

    public static int count(Context c, String name) {
        JSONArray a = all(c).optJSONArray(name);
        return a == null ? 0 : a.length();
    }

    public static List<MediaInfo> items(Context c, String name) {
        List<MediaInfo> out = new ArrayList<>();
        JSONArray a = all(c).optJSONArray(name);
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(MediaInfo.fromJson(o));
        }
        return out;
    }

    public static void add(Context c, String name, MediaInfo m) {
        JSONObject o = all(c);
        JSONArray a = o.optJSONArray(name);
        if (a == null) a = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && m.uri.toString().equals(x.optString("uri"))) return;   // already in it
        }
        a.put(m.toJson());
        try { o.put(name, a); } catch (Exception ignored) { }
        save(c, o);
    }

    public static void remove(Context c, String name, int index) {
        JSONObject o = all(c);
        JSONArray a = o.optJSONArray(name);
        if (a == null || index < 0 || index >= a.length()) return;
        a.remove(index);
        try { o.put(name, a); } catch (Exception ignored) { }
        save(c, o);
    }

    public static void removeUri(Context c, String name, String uri) {
        List<MediaInfo> items = items(c, name);
        for (int i = items.size() - 1; i >= 0; i--)
            if (items.get(i).uri.toString().equals(uri)) remove(c, name, i);
    }

    public static boolean exists(Context c, String name) { return all(c).has(name); }
}
