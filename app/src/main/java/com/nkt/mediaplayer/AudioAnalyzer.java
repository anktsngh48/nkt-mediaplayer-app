package com.nkt.mediaplayer;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

/**
 * Finds what the visualiser needs in the song that is playing: beats (and the tempo), and the pitch being
 * sung or played. Works on a mono copy at about 11 kHz from {@link AnalysisProcessor}; runs only while a
 * visualiser is on screen.
 */
public final class AudioAnalyzer {

    private AudioAnalyzer() { }

    /** Target sample rate of the copy, Hz. */
    public static final int RATE = 11025;

    private static final int RING = 1 << 14;                 // about 1.5 s
    private static final float[] ring = new float[RING];
    private static volatile long written = 0;                // total samples ever written
    private static final ThreadLocal<float[]> SCRATCH = new ThreadLocal<float[]>() {
        @Override protected float[] initialValue() { return new float[2048]; }
    };
    private static volatile int sourceRate = RATE;

    // ── results, read by the visualiser (any thread) ──
    /** Goes up by one at every beat / hit found. */
    public static volatile int beatCount = 0;
    /** SystemClock.uptimeMillis() of the latest beat. */
    public static volatile long lastBeatMs = 0;
    /** Beats per minute, 0 until it is known. */
    public static volatile float bpm = 0f;
    /** The note being sung, in Hz; 0 when nothing clear is heard. */
    public static volatile float pitchHz = 0f;
    /** The note that came up most often so far, 0..11 (C = 0), -1 until enough was heard. */
    public static volatile int autoSa = -1;
    /** SystemClock.uptimeMillis() when sound last arrived. */
    public static volatile long lastAudioMs = 0;
    /** Loudness 0..1. */
    public static volatile float level = 0f;

    // ── life cycle ──
    private static int users = 0;
    private static HandlerThread thread;
    private static Handler handler;

    /** A visualiser appeared. */
    public static synchronized void start() {
        if (users++ > 0) return;
        resetResults();
        thread = new HandlerThread("nkt-analysis", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(loop);
    }

    /** A visualiser went away. */
    public static synchronized void stop() {
        if (users == 0 || --users > 0) return;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quit();
        thread = null;
        handler = null;
    }

    static boolean wanted() { return users > 0; }

    static float[] scratch() { return SCRATCH.get(); }

    static void setSourceRate(int hz) { sourceRate = hz <= 0 ? RATE : hz; }

    static void push(float[] block, int n) {
        long w = written;
        for (int i = 0; i < n; i++) ring[(int) ((w + i) & (RING - 1))] = block[i];
        written = w + n;
        lastAudioMs = SystemClock.uptimeMillis();
    }

    static void flushed() { resetStateRequested = true; }

    /** A new song: forget the key found for the last one. */
    public static volatile int songNo = 0;
    public static void newSong() { songNo++; resetStateRequested = true; saHist = new float[12]; autoSa = -1; }

    private static volatile boolean resetStateRequested = false;

    private static void resetResults() {
        beatCount = 0; lastBeatMs = 0; bpm = 0f; pitchHz = 0f; autoSa = -1; level = 0f;
        saHist = new float[12];
    }

    // ── analysis loop ──
    private static final int PERIOD_MS = 25;
    private static long readPos = 0;
    private static final float[] win = new float[1024];

    // onset detection
    private static float prevRms = 0f;
    private static final float[] fluxHist = new float[48];
    private static int fluxAt = 0;
    private static long lastOnsetMs = 0;
    private static final long[] onsets = new long[32];
    private static int onsetN = 0;

    // pitch
    private static final float[] diff = new float[400];
    private static final float[] cmnd = new float[400];
    private static float[] saHist = new float[12];
    private static float lastHz = 0f;
    private static float lastHz2 = 0f;

    private static final Runnable loop = new Runnable() {
        @Override public void run() {
            Handler h = handler;
            if (h == null) return;
            try { step(); } catch (Throwable ignored) { }
            h.postDelayed(this, PERIOD_MS);
        }
    };

    private static void step() {
        long now = SystemClock.uptimeMillis();
        if (resetStateRequested) {
            resetStateRequested = false;
            readPos = written;
            prevRms = 0f;
            onsetN = 0;
            java.util.Arrays.fill(fluxHist, 0f);
            bpm = 0f;
            pitchHz = 0f;
        }
        long w = written;
        if (now - lastAudioMs > 400 || w < 1024) {            // paused or nothing yet
            level *= 0.8f;
            pitchHz = 0f;
            readPos = w;
            return;
        }
        if (w - readPos > RING / 2) readPos = w - 1024;        // fell behind: skip ahead
        int fresh = (int) Math.min(w - readPos, 4096);
        if (fresh < 128) return;
        long from = w - 1024;
        for (int i = 0; i < 1024; i++) win[i] = ring[(int) ((from + i) & (RING - 1))];

        // loudness of the newest slice
        double e = 0;
        int n = Math.min(fresh, 1024);
        for (int i = 1024 - n; i < 1024; i++) e += win[i] * win[i];
        float rms = (float) Math.sqrt(e / n);
        level = Math.min(1f, rms * 4f);
        readPos = w;

        // beat: a sudden rise in loudness, above what the last second or so looked like
        float flux = Math.max(0f, rms - prevRms);
        prevRms = rms;
        float mean = 0f;
        for (float f : fluxHist) mean += f;
        mean /= fluxHist.length;
        fluxHist[fluxAt] = flux;
        fluxAt = (fluxAt + 1) % fluxHist.length;
        if (flux > mean * 2.2f + 0.004f && rms > 0.01f && now - lastOnsetMs > 260) {
            lastOnsetMs = now;
            lastBeatMs = now;
            beatCount++;
            if (onsetN == onsets.length) { System.arraycopy(onsets, 1, onsets, 0, onsets.length - 1); onsetN--; }
            onsets[onsetN++] = now;
            tempo();
        }

        pitch(rms);
    }

    /** Tempo from the gaps between hits, folded into 70..180 per minute. */
    private static void tempo() {
        if (onsetN < 8) return;
        float[] bins = new float[111];                         // 70..180
        int count = 0;
        for (int i = 1; i < onsetN; i++) {
            float gap = (onsets[i] - onsets[i - 1]) / 1000f;
            if (gap <= 0.05f) continue;
            float b = 60f / gap;
            while (b < 70f) b *= 2f;
            while (b > 180f) b /= 2f;
            int k = Math.round(b) - 70;
            if (k >= 0 && k < bins.length) {
                for (int d = -2; d <= 2; d++) if (k + d >= 0 && k + d < bins.length) bins[k + d] += 3 - Math.abs(d);
                count++;
            }
        }
        if (count < 6) return;
        int best = 0;
        for (int k = 1; k < bins.length; k++) if (bins[k] > bins[best]) best = k;
        float est = best + 70;
        bpm = bpm == 0f ? est : bpm * 0.7f + est * 0.3f;
    }

    /** Which note is being sung or played (YIN): 80..800 Hz. */
    private static void pitch(float rms) {
        float r = sourceRate;
        int tMin = Math.max(2, (int) (r / 800f)), tMax = Math.min(diff.length - 1, (int) (r / 80f));
        int W = 512;
        if (rms < 0.012f) { lastHz2 = lastHz; lastHz = 0f; pitchHz = 0f; return; }
        int off = 1024 - W - tMax;
        if (off < 0) { tMax += off; off = 0; }
        for (int t = 1; t <= tMax; t++) {
            float s = 0f;
            for (int i = 0; i < W; i++) {
                float d = win[off + tMax + i] - win[off + tMax + i - t];
                s += d * d;
            }
            diff[t] = s;
        }
        float run = 0f;
        cmnd[0] = 1f;
        for (int t = 1; t <= tMax; t++) {
            run += diff[t];
            cmnd[t] = run == 0f ? 1f : diff[t] * t / run;
        }
        int found = -1;
        for (int t = tMin; t < tMax; t++) {
            if (cmnd[t] < 0.15f) {
                while (t + 1 < tMax && cmnd[t + 1] < cmnd[t]) t++;
                found = t;
                break;
            }
        }
        if (found < 0) {
            // not a clean note (voice over instruments): take the clearest dip, if it is clear enough, preferring the highest pitch of similar dips
            float bestV = 1f;
            for (int t = tMin; t < tMax; t++) if (cmnd[t] < bestV) bestV = cmnd[t];
            if (bestV < 0.38f) {
                for (int t = Math.max(tMin, 2); t < tMax - 1; t++) {
                    if (cmnd[t] <= bestV * 1.2f && cmnd[t] <= cmnd[t - 1] && cmnd[t] <= cmnd[t + 1]) { found = t; break; }
                }
            }
        }
        if (found < 0) { lastHz2 = lastHz; lastHz = 0f; pitchHz = 0f; return; }
        float tau = found;
        if (found > 1 && found < tMax) {                      // between samples
            float a = cmnd[found - 1], b = cmnd[found], c = cmnd[found + 1];
            float den = a + c - 2 * b;
            if (Math.abs(den) > 1e-9f) tau = found + (a - c) / (2 * den);
        }
        float hz = r / tau;
        // a clear note must agree with the one just before it, otherwise it is probably a glitch
        float out = hz;
        if (lastHz > 0 && lastHz2 > 0) {
            float a = Math.min(Math.min(hz, lastHz), lastHz2), c = Math.max(Math.max(hz, lastHz), lastHz2);
            float mid = hz + lastHz + lastHz2 - a - c;
            out = mid;
        }
        lastHz2 = lastHz;
        lastHz = hz;
        pitchHz = out;
        // the note heard most often is taken as Sa until the listener says otherwise
        float semis = (float) (12.0 * Math.log(out / 261.6256) / Math.log(2));
        int cls = ((Math.round(semis) % 12) + 12) % 12;
        for (int i = 0; i < 12; i++) saHist[i] *= 0.9995f;
        saHist[cls] += 1f - cmnd[found];
        int best = 0;
        float sum = 0f;
        for (int i = 0; i < 12; i++) { sum += saHist[i]; if (saHist[i] > saHist[best]) best = i; }
        if (sum > 40f) autoSa = best;
    }
}
