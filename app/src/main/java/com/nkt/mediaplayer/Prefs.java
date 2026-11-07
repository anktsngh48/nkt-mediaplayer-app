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

    public static java.util.Map<String, Integer> playCounts(Context c) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("plays", Context.MODE_PRIVATE);
        for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) out.put(e.getKey(), asInt(e.getValue()));
        return out;
    }

    private static int asInt(Object o) { return o instanceof Integer ? (Integer) o : 0; }

    // ── Equalizer ──
    public static JSONObject eq(Context c) {
        try { return new JSONObject(sp(c).getString("eq", "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    public static void setEq(Context c, JSONObject o) { sp(c).edit().putString("eq", o.toString()).apply(); }

    // ── Favourites: one playlist each for songs, videos and photos ──
    public static final String FAV_SONGS = "♥ Favourite songs";
    public static final String FAV_VIDEOS = "♥ Favourite videos";
    public static final String FAV_PHOTOS = "♥ Favourite photos";
    private static final String OLD_FAVOURITES = "♥ Favourites";

    public static String favouritesFor(MediaInfo m) {
        return m.image ? FAV_PHOTOS : m.video ? FAV_VIDEOS : FAV_SONGS;
    }

    public static boolean isFavouriteList(String name) {
        return FAV_SONGS.equals(name) || FAV_VIDEOS.equals(name) || FAV_PHOTOS.equals(name);
    }

    public static boolean isFavourite(Context c, MediaInfo m) {
        if (m == null) return false;
        for (MediaInfo x : Playlists.items(c, favouritesFor(m))) if (x.key().equals(m.key())) return true;
        return false;
    }

    /** Adds or removes; returns true if it is a favourite now. */
    public static boolean toggleFavourite(Context c, MediaInfo m) {
        String list = favouritesFor(m);
        if (isFavourite(c, m)) { Playlists.removeUri(c, list, m.key()); return false; }
        if (!Playlists.exists(c, list)) Playlists.create(c, list);
        Playlists.add(c, list, m);
        return true;
    }

    /** Version 2 had one "♥ Favourites" list; split it by type once. */
    public static void migrateFavourites(Context c) {
        if (sp(c).getBoolean("fav_split", false)) return;
        if (Playlists.exists(c, OLD_FAVOURITES)) {
            for (MediaInfo m : Playlists.items(c, OLD_FAVOURITES)) {
                String list = favouritesFor(m);
                if (!Playlists.exists(c, list)) Playlists.create(c, list);
                Playlists.add(c, list, m);
            }
            Playlists.delete(c, OLD_FAVOURITES);
        }
        sp(c).edit().putBoolean("fav_split", true).apply();
    }

    // ── Views & sorting ──
    public static boolean videosGrid(Context c) { return sp(c).getBoolean("videos_grid", false); }
    public static void setVideosGrid(Context c, boolean v) { sp(c).edit().putBoolean("videos_grid", v).apply(); }

    /** Sort choice per tab ("songs", "videos", "gallery", "folders"). */
    public static String sort(Context c, String tab, String def) { return sp(c).getString("sort_" + tab, def); }
    public static void setSort(Context c, String tab, String v) { sp(c).edit().putString("sort_" + tab, v).apply(); }

    // ── .thumbnails folders (separate from "show hidden") ──
    public static boolean showThumbnails(Context c) { return sp(c).getBoolean("show_thumbs", false); }
    public static void setShowThumbnails(Context c, boolean v) { sp(c).edit().putBoolean("show_thumbs", v).apply(); }

    // ── Video rotation: 0 auto, 1 portrait, 2 landscape ──
    public static int videoOrientation(Context c) { return sp(c).getInt("video_orient", 0); }
    public static void setVideoOrientation(Context c, int v) { sp(c).edit().putInt("video_orient", v).apply(); }

    // ── helpers ──
    public static List<String> keys(JSONObject o) {
        List<String> l = new ArrayList<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) l.add(it.next());
        return l;
    }
}
