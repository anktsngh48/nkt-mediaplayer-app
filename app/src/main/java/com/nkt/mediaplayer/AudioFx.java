package com.nkt.mediaplayer;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Equalizer, bass boost, surround and loudness on the player's audio session.
 * Settings are saved in Prefs ("eq") and put back every time the player starts.
 *
 * Saved JSON: {on, preset (-1 = custom), bands:[mB…], bass 0-1000, virt 0-1000, loud 0-1000}
 */
public final class AudioFx {

    private AudioFx() { }

    private static Equalizer eq;
    private static BassBoost bass;
    private static Virtualizer virt;
    private static LoudnessEnhancer loud;

    public static synchronized void attach(Context c, int sessionId) {
        release();
        try { eq = new Equalizer(0, sessionId); } catch (Throwable t) { eq = null; }
        try { bass = new BassBoost(0, sessionId); } catch (Throwable t) { bass = null; }
        try { virt = new Virtualizer(0, sessionId); } catch (Throwable t) { virt = null; }
        try { loud = new LoudnessEnhancer(sessionId); } catch (Throwable t) { loud = null; }
        apply(c);
    }

    public static synchronized void release() {
        try { if (eq != null) eq.release(); } catch (Throwable ignored) { }
        try { if (bass != null) bass.release(); } catch (Throwable ignored) { }
        try { if (virt != null) virt.release(); } catch (Throwable ignored) { }
        try { if (loud != null) loud.release(); } catch (Throwable ignored) { }
        eq = null; bass = null; virt = null; loud = null;
    }

    public static boolean available() { return eq != null; }

    /** Put the saved settings on the live effects. */
    public static synchronized void apply(Context c) {
        JSONObject s = Prefs.eq(c);
        boolean on = s.optBoolean("on", false);
        try {
            if (eq != null) {
                eq.setEnabled(on);
                int preset = s.optInt("preset", -1);
                JSONArray bands = s.optJSONArray("bands");
                if (preset >= 0 && preset < eq.getNumberOfPresets()) eq.usePreset((short) preset);
                else if (bands != null) {
                    short[] r = eq.getBandLevelRange();
                    for (short b = 0; b < eq.getNumberOfBands() && b < bands.length(); b++) {
                        int lvl = Math.max(r[0], Math.min(r[1], bands.optInt(b, 0)));
                        eq.setBandLevel(b, (short) lvl);
                    }
                }
            }
        } catch (Throwable ignored) { }
        try {
            if (bass != null) {
                int v = s.optInt("bass", 0);
                bass.setEnabled(on && v > 0);
                if (bass.getStrengthSupported()) bass.setStrength((short) v);
            }
        } catch (Throwable ignored) { }
        try {
            if (virt != null) {
                int v = s.optInt("virt", 0);
                virt.setEnabled(on && v > 0);
                if (virt.getStrengthSupported()) virt.setStrength((short) v);
            }
        } catch (Throwable ignored) { }
        try {
            if (loud != null) {
                int v = s.optInt("loud", 0);
                loud.setTargetGain(v);           // millibels, 0 – 1000 (up to +10 dB)
                loud.setEnabled(on && v > 0);
            }
        } catch (Throwable ignored) { }
    }

    // ── Read-outs for the Equalizer screen ──

    public static int bandCount() {
        try { return eq == null ? 0 : eq.getNumberOfBands(); } catch (Throwable t) { return 0; }
    }

    /** {min, max} in millibels. */
    public static int[] range() {
        try {
            short[] r = eq.getBandLevelRange();
            return new int[]{r[0], r[1]};
        } catch (Throwable t) { return new int[]{-1500, 1500}; }
    }

    public static String bandLabel(int band) {
        try {
            int hz = eq.getCenterFreq((short) band) / 1000;
            return hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz);
        } catch (Throwable t) { return "?"; }
    }

    public static int bandLevel(int band) {
        try { return eq.getBandLevel((short) band); } catch (Throwable t) { return 0; }
    }

    public static String[] presets() {
        try {
            String[] out = new String[eq.getNumberOfPresets()];
            for (short i = 0; i < out.length; i++) out[i] = eq.getPresetName(i);
            return out;
        } catch (Throwable t) { return new String[0]; }
    }
}
