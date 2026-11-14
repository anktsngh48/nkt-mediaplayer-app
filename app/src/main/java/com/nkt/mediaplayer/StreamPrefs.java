package com.nkt.mediaplayer;

import android.content.Context;

import org.json.JSONArray;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.Set;

/** Stream: the saved settings (address, PIN, silence, listener limit, shared library, downloads). */
final class StreamPrefs {

    private StreamPrefs() { }

    static final int DEFAULT_PORT = 8765;
    static final String MODE_ALL = "all", MODE_CHOOSE = "choose";

    // ── Address: http://<wifi address>:<port>/<name> ──

    static String name(Context c) {
        String n = Prefs.sp(c).getString("stream_name", null);
        if (n == null || n.isEmpty()) n = randomName(c);
        return n;
    }

    static String randomName(Context c) {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4; i++) b.append(chars.charAt(r.nextInt(chars.length())));
        Prefs.sp(c).edit().putString("stream_name", b.toString()).apply();
        return b.toString();
    }

    static String newRandomName() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) b.append(chars.charAt(r.nextInt(chars.length())));
        return b.toString();
    }

    static void setName(Context c, String n) { Prefs.sp(c).edit().putString("stream_name", n).apply(); }

    static int port(Context c) { return Prefs.sp(c).getInt("stream_port", DEFAULT_PORT); }
    static void setPort(Context c, int p) { Prefs.sp(c).edit().putInt("stream_port", p).apply(); }

    /** A valid name: 1–40 letters, digits, dash or underscore. */
    static boolean validName(String n) { return n != null && n.matches("[A-Za-z0-9_-]{1,40}"); }

    /** Short or common names are easy to guess. */
    static boolean guessable(String n) { return n == null || n.length() < 6; }

    // ── PIN ──

    static boolean pinOn(Context c) { return Prefs.sp(c).getBoolean("stream_pin_on", false); }

    static String pin(Context c) {
        String p = Prefs.sp(c).getString("stream_pin", null);
        if (p == null) {
            p = String.valueOf(1000 + new SecureRandom().nextInt(9000));
            Prefs.sp(c).edit().putString("stream_pin", p).apply();
        }
        return p;
    }

    static void setPin(Context c, boolean on, String pin) {
        android.content.SharedPreferences.Editor e = Prefs.sp(c).edit().putBoolean("stream_pin_on", on);
        if (pin != null && pin.matches("\\d{4}")) e.putString("stream_pin", pin);
        e.apply();
    }

    // ── Playback ──

    static boolean silence(Context c) { return Prefs.sp(c).getBoolean("stream_silence", false); }
    static void setSilence(Context c, boolean v) { Prefs.sp(c).edit().putBoolean("stream_silence", v).apply(); }

    /** 0 = no limit. */
    static int limit(Context c) { return Prefs.sp(c).getInt("stream_limit", 0); }
    static void setLimit(Context c, int n) { Prefs.sp(c).edit().putInt("stream_limit", n).apply(); }

    // ── Library for listeners ──

    static boolean libraryOn(Context c) { return Prefs.sp(c).getBoolean("stream_library", false); }
    static void setLibraryOn(Context c, boolean v) { Prefs.sp(c).edit().putBoolean("stream_library", v).apply(); }

    static boolean allowDownloads(Context c) { return Prefs.sp(c).getBoolean("stream_downloads", false); }
    static void setAllowDownloads(Context c, boolean v) { Prefs.sp(c).edit().putBoolean("stream_downloads", v).apply(); }

    static String shareMode(Context c) { return MODE_CHOOSE.equals(Prefs.sp(c).getString("stream_share_mode", MODE_ALL)) ? MODE_CHOOSE : MODE_ALL; }
    static void setShareMode(Context c, String m) { Prefs.sp(c).edit().putString("stream_share_mode", m).apply(); }

    static Set<String> picks(Context c) {
        Set<String> out = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString("stream_picks", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    static void savePicks(Context c, Set<String> picks) {
        JSONArray a = new JSONArray();
        for (String s : picks) a.put(s);
        Prefs.sp(c).edit().putString("stream_picks", a.toString()).apply();
    }

    // ── Blocked for good: kept across streams, by the device's address on the network ──

    /** Blocked devices as {address, name}. */
    static java.util.List<String[]> blocked(Context c) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString("stream_blocked", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("ip"), o.optString("name")});
            }
        } catch (Exception ignored) { }
        return out;
    }

    static void addBlocked(Context c, String ip, String name) {
        if (ip == null || ip.isEmpty()) return;
        java.util.List<String[]> list = blocked(c);
        for (String[] e : list) if (e[0].equals(ip)) return;
        list.add(new String[]{ip, name == null ? "" : name});
        saveBlocked(c, list);
    }

    static void removeBlocked(Context c, String ip) {
        java.util.List<String[]> list = blocked(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i)[0].equals(ip)) list.remove(i);
        saveBlocked(c, list);
    }

    private static void saveBlocked(Context c, java.util.List<String[]> list) {
        JSONArray a = new JSONArray();
        try {
            for (String[] e : list) a.put(new org.json.JSONObject().put("ip", e[0]).put("name", e[1]));
        } catch (Exception ignored) { }
        Prefs.sp(c).edit().putString("stream_blocked", a.toString()).apply();
    }
}
