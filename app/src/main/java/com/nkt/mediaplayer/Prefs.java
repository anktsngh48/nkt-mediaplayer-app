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

    /** Library (Audio, Videos, Gallery) includes media in hidden folders unless Settings → ALL FILES hides both kinds. */
    public static boolean showHidden(Context c) { return !(hideDot(c) && hideNomedia(c)); }
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
    /** Library includes .thumbnails pictures only when Settings → ALL FILES doesn't hide them. */
    public static boolean showThumbnails(Context c) { return !hideThumbDirs(c); }
    public static void setShowThumbnails(Context c, boolean v) { sp(c).edit().putBoolean("show_thumbs", v).apply(); }

    // ── Video rotation: 0 auto, 1 portrait, 2 landscape ──
    public static int videoOrientation(Context c) { return sp(c).getInt("video_orient", 0); }
    public static void setVideoOrientation(Context c, int v) { sp(c).edit().putInt("video_orient", v).apply(); }


    // ── Start page (chosen with ⌂ in the ☰ menu) ──
    public static String startPage(Context c) { return sp(c).getString("start_page", "songs"); }
    public static void setStartPage(Context c, String v) { sp(c).edit().putString("start_page", v).apply(); }

    // ── View per page: list, grid, squares (songs), folders (videos) ──
    public static String view(Context c, String page, String def) {
        SharedPreferences p = sp(c);
        if (!p.contains("view_" + page)) {
            // older builds kept two switches; read them once
            if ("songs".equals(page) && p.getBoolean("squares", false)) return "squares";
            if ("videos".equals(page) && p.getBoolean("videos_grid", false)) return "grid";
        }
        return p.getString("view_" + page, def);
    }
    public static void setView(Context c, String page, String v) { sp(c).edit().putString("view_" + page, v).apply(); }

    /** Tiles per row in a grid (pinch to change). */
    public static int cols(Context c, String page, int def) { return sp(c).getInt(page + "_cols", def); }
    public static void setCols(Context c, String page, int v) { sp(c).edit().putInt(page + "_cols", v).apply(); }

    // ── Folders page: hide only what is switched on here ──
    public static boolean hideDot(Context c) { return sp(c).getBoolean("hide_dot", true); }
    public static boolean hideNomedia(Context c) { return sp(c).getBoolean("hide_nomedia", true); }
    // .thumbnails (the phone's little preview copies) are hidden unless switched on: thousands of them would flood the Gallery
    public static boolean hideThumbDirs(Context c) { return sp(c).getBoolean("hide_thumbdirs", true); }
    public static void setBool(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }
    public static boolean foldersFirst(Context c, String page) { return sp(c).getBoolean("folders_first_" + page, true); }
    public static void setFoldersFirst(Context c, String page, boolean v) { sp(c).edit().putBoolean("folders_first_" + page, v).apply(); }

    // ── When a video ends: 0 play next, 1 go back, 2 stop on the last frame ──
    public static int videoEnd(Context c) {
        SharedPreferences p = sp(c);
        if (!p.contains("video_end")) return p.getBoolean("video_autonext", true) ? 0 : 2;
        String v = p.getString("video_end", "next");
        return "back".equals(v) ? 1 : "stop".equals(v) ? 2 : 0;
    }
    public static void setVideoEnd(Context c, int v) {
        sp(c).edit().putString("video_end", v == 1 ? "back" : v == 2 ? "stop" : "next").apply();
    }

    // ── Karaoke: strength 0 light, 1 normal, 2 strong; key in semitones per song ──
    public static boolean karaoke(Context c) { return sp(c).getBoolean("karaoke", false); }
    public static void setKaraoke(Context c, boolean v) { sp(c).edit().putBoolean("karaoke", v).apply(); KaraokeProcessor.enabled = v; }
    public static int karaokeStrength(Context c) {
        String v = sp(c).getString("karaoke_strength", "normal");
        return "light".equals(v) ? 0 : "strong".equals(v) ? 2 : 1;
    }
    public static void setKaraokeStrength(Context c, int v) {
        sp(c).edit().putString("karaoke_strength", v == 0 ? "light" : v == 2 ? "strong" : "normal").apply();
        KaraokeProcessor.strength = v;
    }
    public static boolean karaokeLyrics(Context c) { return sp(c).getBoolean("karaoke_lyrics", true); }
    public static int key(Context c, String mediaId) { return mediaId == null ? 0 : sp(c).getInt("key_" + Lyrics.idOf(mediaId), 0); }
    public static void setKey(Context c, String mediaId, int semis) {
        if (mediaId == null) return;
        if (semis == 0) sp(c).edit().remove("key_" + Lyrics.idOf(mediaId)).apply();
        else sp(c).edit().putInt("key_" + Lyrics.idOf(mediaId), semis).apply();
    }
    /** Pitch multiplier for a key change in semitones. */
    public static float pitchFor(int semis) { return (float) Math.pow(2, semis / 12.0); }

    // ── Text editor & PDF reader ──
    public static int editorSize(Context c) { return sp(c).getInt("editor_size", 14); }
    public static void setEditorSize(Context c, int v) { sp(c).edit().putInt("editor_size", v).apply(); }
    public static boolean editorWrap(Context c) { return sp(c).getBoolean("editor_wrap", true); }
    public static void setEditorWrap(Context c, boolean v) { sp(c).edit().putBoolean("editor_wrap", v).apply(); }
    public static int pdfPage(Context c, String key) { return sp(c).getInt("pdf_page_" + Lyrics.idOf(key), 0); }
    public static void setPdfPage(Context c, String key, int page) { sp(c).edit().putInt("pdf_page_" + Lyrics.idOf(key), page).apply(); }
    public static boolean pdfNight(Context c) { return sp(c).getBoolean("pdf_night", false); }
    public static void setPdfNight(Context c, boolean v) { sp(c).edit().putBoolean("pdf_night", v).apply(); }

    // ── helpers ──
    public static List<String> keys(JSONObject o) {
        List<String> l = new ArrayList<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) l.add(it.next());
        return l;
    }
}
