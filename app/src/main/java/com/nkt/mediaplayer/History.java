package com.nkt.mediaplayer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Files in the order they were opened (newest first). On by default; can be switched off in Settings. */
public final class History {

    private History() { }

    private static final int MAX = 300;

    public static class Entry {
        public final MediaInfo media;
        public final long when;
        Entry(MediaInfo m, long t) { media = m; when = t; }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("history", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) { return Prefs.sp(c).getBoolean("history_on", true); }
    public static void setEnabled(Context c, boolean on) { Prefs.sp(c).edit().putBoolean("history_on", on).apply(); }

    public static synchronized void record(Context c, MediaInfo m) {
        if (m == null || !enabled(c)) return;
        try {
            JSONArray old = new JSONArray(sp(c).getString("list", "[]"));
            JSONArray now = new JSONArray();
            JSONObject e = new JSONObject();
            e.put("m", m.toJson());
            e.put("t", System.currentTimeMillis());
            now.put(e);
            for (int i = 0; i < old.length() && now.length() < MAX; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                // the same file opened again right away counts once
                if (i == 0 && m.key().equals(o.optJSONObject("m") == null ? "" : o.optJSONObject("m").optString("uri"))) continue;
                now.put(o);
            }
            sp(c).edit().putString("list", now.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static List<Entry> all(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || o.optJSONObject("m") == null) continue;
                out.add(new Entry(MediaInfo.fromJson(o.optJSONObject("m")), o.optLong("t")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static int count(Context c) {
        try { return new JSONArray(sp(c).getString("list", "[]")).length(); } catch (Exception e) { return 0; }
    }

    public static void clear(Context c) { sp(c).edit().remove("list").apply(); }
}
