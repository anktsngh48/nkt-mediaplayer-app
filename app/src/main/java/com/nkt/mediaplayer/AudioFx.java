package com.nkt.mediaplayer;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;

import org.json.JSONArray;
import org.json.JSONObject;

/** The phone's own audio effects attached to the music player's audio session. Missing effects are skipped. */
public final class AudioFx {
    private AudioFx() {}

    public static Equalizer eq;
    public static BassBoost bass;
    public static Virtualizer virt;
    public static LoudnessEnhancer loud;
    private static int session;

    public static void attach(Context c, int sessionId) {
        if (sessionId == session && eq != null) return;
        release();
        session = sessionId;
        try { eq = new Equalizer(0, sessionId); } catch (Throwable t) { eq = null; }
        try { bass = new BassBoost(0, sessionId); } catch (Throwable t) { bass = null; }
        try { virt = new Virtualizer(0, sessionId); } catch (Throwable t) { virt = null; }
        try { loud = new LoudnessEnhancer(sessionId); } catch (Throwable t) { loud = null; }
        apply(c);
        KaraokeProcessor.enabled = Store.bool(c, "karaoke", false);
        KaraokeProcessor.strength = Store.prefs(c).getFloat("karaoke_strength", 0.85f);
    }

    public static void release() {
        try { if (eq != null) eq.release(); } catch (Throwable ignored) {}
        try { if (bass != null) bass.release(); } catch (Throwable ignored) {}
        try { if (virt != null) virt.release(); } catch (Throwable ignored) {}
        try { if (loud != null) loud.release(); } catch (Throwable ignored) {}
        eq = null; bass = null; virt = null; loud = null;
    }

    public static JSONObject state(Context c) {
        try { return new JSONObject(Store.str(c, "eq", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    public static void save(Context c, JSONObject o) {
        Store.put(c, "eq", o.toString());
        apply(c);
    }

    public static void apply(Context c) {
        JSONObject o = state(c);
        boolean on = o.optBoolean("on", false);
        try {
            if (eq != null) {
                eq.setEnabled(on);
                int preset = o.optInt("preset", -1);
                if (preset >= 0 && preset < eq.getNumberOfPresets()) {
                    eq.usePreset((short) preset);
                } else {
                    JSONArray b = o.optJSONArray("bands");
                    if (b != null) for (short i = 0; i < eq.getNumberOfBands() && i < b.length(); i++) eq.setBandLevel(i, (short) b.optInt(i));
                }
            }
        } catch (Throwable ignored) {}
        try { if (bass != null) { bass.setEnabled(on && o.optInt("bass") > 0); if (bass.getStrengthSupported()) bass.setStrength((short) o.optInt("bass")); } } catch (Throwable ignored) {}
        try { if (virt != null) { virt.setEnabled(on && o.optInt("virt") > 0); if (virt.getStrengthSupported()) virt.setStrength((short) o.optInt("virt")); } } catch (Throwable ignored) {}
        try { if (loud != null) { loud.setEnabled(on && o.optInt("loud") > 0); loud.setTargetGain(o.optInt("loud")); } } catch (Throwable ignored) {}
    }
}
