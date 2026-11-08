package com.nkt.mediaplayer;

import android.content.Context;

import java.io.File;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Sort choices for every page. Every page starts with A–Z, Z–A, Newest first, Oldest first. */
public final class Sorts {
    private Sorts() {}

    public static final String AZ = "A–Z", ZA = "Z–A", NEW = "Newest first", OLD = "Oldest first";

    public static String[] options(int page) {
        switch (page) {
            case 0: return new String[]{AZ, ZA, NEW, OLD, "Artist", "Album", "Longest", "Shortest", "Biggest", "Most played"};
            case 1: return new String[]{AZ, ZA, NEW, OLD, "Longest", "Shortest", "Biggest", "Smallest"};
            case 2: return new String[]{NEW, OLD, AZ, ZA, "Biggest", "Smallest"};
            case 3: return new String[]{AZ, ZA, NEW, OLD, "Biggest", "Smallest"};
            case 4: return new String[]{AZ, ZA, NEW, OLD, "Most items", "Fewest items"};
            default: return new String[]{NEW, OLD, AZ, ZA};
        }
    }

    public static String current(Context c, int page) {
        String s = Store.str(c, "sort_" + page, options(page)[0]);
        for (String o : options(page)) if (o.equals(s)) return s;
        return options(page)[0];
    }

    public static String chipLabel(String s) {
        switch (s) {
            case NEW: return "NEWEST";
            case OLD: return "OLDEST";
            default: return s.toUpperCase();
        }
    }

    static int cmp(String a, String b) { return a.compareToIgnoreCase(b); }

    static String key(MediaInfo m) { return m.title.isEmpty() ? m.name : m.title; }

    public static void media(Context c, List<MediaInfo> l, String s) {
        Comparator<MediaInfo> byTitle = (a, b) -> cmp(key(a), key(b));
        Comparator<MediaInfo> k;
        switch (s) {
            case ZA: k = byTitle.reversed(); break;
            case NEW: k = (a, b) -> Long.compare(b.date, a.date); break;
            case OLD: k = (a, b) -> Long.compare(a.date, b.date); break;
            case "Artist": k = (a, b) -> {
                boolean ua = a.artistOrUnknown().equals("Unknown artist"), ub = b.artistOrUnknown().equals("Unknown artist");
                if (ua != ub) return ua ? 1 : -1;
                int r = cmp(a.artist, b.artist);
                return r != 0 ? r : byTitle.compare(a, b);
            }; break;
            case "Album": k = (a, b) -> {
                boolean ua = a.album.isEmpty(), ub = b.album.isEmpty();
                if (ua != ub) return ua ? 1 : -1;
                int r = cmp(a.album, b.album);
                return r != 0 ? r : byTitle.compare(a, b);
            }; break;
            case "Longest": k = (a, b) -> Long.compare(b.duration, a.duration); break;
            case "Shortest": k = (a, b) -> Long.compare(a.duration, b.duration); break;
            case "Biggest": k = (a, b) -> Long.compare(b.size, a.size); break;
            case "Smallest": k = (a, b) -> Long.compare(a.size, b.size); break;
            case "Most played": k = (a, b) -> Integer.compare(Store.plays(c, b.uri), Store.plays(c, a.uri)); break;
            default: k = byTitle;
        }
        Collections.sort(l, k);
    }

    public static void files(List<File> l, String s) {
        Comparator<File> k;
        switch (s) {
            case ZA: k = (a, b) -> cmp(b.getName(), a.getName()); break;
            case NEW: k = (a, b) -> Long.compare(b.lastModified(), a.lastModified()); break;
            case OLD: k = (a, b) -> Long.compare(a.lastModified(), b.lastModified()); break;
            case "Biggest": k = (a, b) -> Long.compare(b.length(), a.length()); break;
            case "Smallest": k = (a, b) -> Long.compare(a.length(), b.length()); break;
            default: k = (a, b) -> cmp(a.getName(), b.getName());
        }
        Comparator<File> foldersFirst = (a, b) -> Boolean.compare(!a.isDirectory(), !b.isDirectory());
        Collections.sort(l, foldersFirst.thenComparing(k));
    }
}
