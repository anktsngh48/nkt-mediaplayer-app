package com.nkt.mediaplayer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Small saved settings: view choices, play counts, equalizer, NKT Share. */
public final class Prefs {

    private Prefs() { }

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean showHidden(Context c) { return sp(c).getBoolean("show_hidden", false); }
    public static void setShowHidden(Context c, boolean v) { sp(c).edit().putBoolean("show_hidden", v).apply(); }

    public static boolean squares(Context c) { return sp(c).getBoolean("squares", false); }
    public static void setSquares(Context c, boolean v) { sp(c).edit().putBoolean("squares", v).apply(); }

    public static int galleryCols(Context c) { return sp(c).getInt("gallery_cols", 3); }
    public static void setGalleryCols(Context c, int v) { sp(c).edit().putInt("gallery_cols", v).apply(); }

    // ── Play counts (for "Most played") ──
    public static void countPlay(Context c, String mediaId) {
        if (mediaId == null) return;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("plays", Context.MODE_PRIVATE);
        p.edit().putInt(mediaId, p.getInt(mediaId, 0) + 1).apply();
    }

    /** Media ids, most played first. */
    public static List<String> mostPlayed(Context c, int limit) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("plays", Context.MODE_PRIVATE);
        List<java.util.Map.Entry<String, ?>> all = new ArrayList<>(p.getAll().entrySet());
        Collections.sort(all, (a, b) -> Integer.compare(asInt(b.getValue()), asInt(a.getValue())));
        List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<String, ?> e : all) {
            if (out.size() >= limit) break;
            if (asInt(e.getValue()) > 0) out.add(e.getKey());
        }
        return out;
    }

    private static int asInt(Object o) { return o instanceof Integer ? (Integer) o : 0; }

    // ── Equalizer ──
    public static JSONObject eq(Context c) {
        try { return new JSONObject(sp(c).getString("eq", "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    public static void setEq(Context c, JSONObject o) { sp(c).edit().putString("eq", o.toString()).apply(); }

    // ── Favourites (a playlist with a fixed name) ──
    public static final String FAVOURITES = "♥ Favourites";

    public static boolean isFavourite(Context c, String uri) {
        for (MediaInfo m : Playlists.items(c, FAVOURITES)) if (m.uri.toString().equals(uri)) return true;
        return false;
    }

    // ── helpers ──
    public static List<String> keys(JSONObject o) {
        List<String> l = new ArrayList<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) l.add(it.next());
        return l;
    }
}
