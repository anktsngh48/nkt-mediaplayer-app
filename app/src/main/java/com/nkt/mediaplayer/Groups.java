package com.nkt.mediaplayer;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Music squares: groups of similar songs, built from each song's own details
 * (artist, genre, album, year, folder) plus what's new and what you play most.
 */
public final class Groups {

    private Groups() { }

    public static class Group {
        public final String kind;      // e.g. "SAME ARTIST"
        public final String name;      // e.g. "Arijit Singh mix"
        public final List<MediaInfo> songs;
        Group(String kind, String name, List<MediaInfo> songs) { this.kind = kind; this.name = name; this.songs = songs; }
        public long totalMs() { long t = 0; for (MediaInfo m : songs) t += m.durationMs; return t; }
    }

    public static class Section {
        public final String title;
        public final List<Group> groups;
        Section(String t, List<Group> g) { title = t; groups = g; }
    }

    private static final int MIN = 3, MAX_PER_SECTION = 12;

    public static List<Section> build(Context c, List<MediaInfo> audio) {
        List<Section> out = new ArrayList<>();
        if (audio.isEmpty()) return out;

        // For you
        List<Group> forYou = new ArrayList<>();
        List<MediaInfo> recent = new ArrayList<>(audio);
        Collections.sort(recent, (a, b) -> Long.compare(b.dateMs, a.dateMs));
        if (recent.size() > 50) recent = new ArrayList<>(recent.subList(0, 50));
        forYou.add(new Group("FOR YOU", "Recently added", recent));
        Map<String, MediaInfo> byId = new HashMap<>();
        for (MediaInfo m : audio) byId.put(m.key(), m);
        List<MediaInfo> most = new ArrayList<>();
        for (String id : Prefs.mostPlayed(c, 50)) { MediaInfo m = byId.get(id); if (m != null) most.add(m); }
        if (!most.isEmpty()) forYou.add(new Group("FOR YOU", "Most played", most));
        out.add(new Section("FOR YOU", forYou));

        add(out, "SAME ARTIST", audio, m -> mainArtist(m.artist), n -> n + " mix");
        add(out, "SAME GENRE", audio, m -> m.genre, n -> n);
        add(out, "SAME ALBUM", audio, m -> m.album, n -> n);
        add(out, "BY DECADE", audio, m -> m.year >= 1900 ? (m.year / 10 * 10) + "s" : "", n -> n);
        add(out, "BY FOLDER", audio, m -> m.folder, n -> n);
        return out;
    }

    interface Key { String of(MediaInfo m); }
    interface Name { String of(String key); }

    private static void add(List<Section> out, String title, List<MediaInfo> audio, Key key, Name name) {
        Map<String, List<MediaInfo>> map = new LinkedHashMap<>();
        Map<String, String> display = new HashMap<>();
        for (MediaInfo m : audio) {
            String k = key.of(m);
            if (k == null || k.trim().isEmpty() || k.equalsIgnoreCase("unknown") || k.equals("<unknown>")) continue;
            String norm = k.trim().toLowerCase(Locale.ROOT);
            List<MediaInfo> l = map.get(norm);
            if (l == null) { l = new ArrayList<>(); map.put(norm, l); display.put(norm, k.trim()); }
            l.add(m);
        }
        List<Map.Entry<String, List<MediaInfo>>> entries = new ArrayList<>(map.entrySet());
        Collections.sort(entries, (a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<MediaInfo>> e : entries) {
            if (e.getValue().size() < MIN || groups.size() >= MAX_PER_SECTION) continue;
            groups.add(new Group(title, name.of(display.get(e.getKey())), e.getValue()));
        }
        if (!groups.isEmpty()) out.add(new Section(title, groups));
    }

    /** "Pritam, Arijit Singh & Amitabh" → "Pritam", so collaborations join the main artist's square. */
    private static String mainArtist(String a) {
        if (a == null) return "";
        String s = a;
        for (String sep : new String[]{",", " & ", " feat", " ft.", " x ", ";", "/"}) {
            int i = s.toLowerCase(Locale.ROOT).indexOf(sep);
            if (i > 0) s = s.substring(0, i);
        }
        return s.trim();
    }
}
