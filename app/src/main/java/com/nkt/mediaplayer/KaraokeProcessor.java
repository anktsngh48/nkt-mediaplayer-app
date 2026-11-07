package com.nkt.mediaplayer;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Karaoke: removes the lead vocal by working on the song frequency by frequency (short-time Fourier transform).
 * In each small slice of time and pitch, sound that is the same in the left and right channels (the centre of
 * the mix, where the lead singer sits) is taken out; sound spread to the sides is kept. Bass and drums under
 * ~150 Hz and the very top are left alone, so the music stays full.
 *
 * Mono songs can't be separated: then it passes the sound through and reports it.
 * Latency is fixed at FRAME samples whether karaoke is on or off, so switching never jumps.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class KaraokeProcessor extends BaseAudioProcessor {

    /** Switched from the Equalizer screen. */
    public static volatile boolean enabled = false;

    /** For the Equalizer screen: 0 = nothing playing yet, 1 = stereo (working), 2 = mono song. */
    public static volatile int status = 0;
    public static volatile long lastAudioMs = 0;

    private static final int N = 2048, HOP = N / 4;

    private boolean active;
    private int sampleRate = 44100;

    // analysis history (last N samples), overlap-add output, and the block being played out
    private final float[] histL = new float[N], histR = new float[N];
    private final float[] accL = new float[N], accR = new float[N];
    private final float[] readyL = new float[HOP], readyR = new float[HOP];
    private int filled = 0;

    // FFT work buffers
    private final float[] re = new float[N], im = new float[N];
    private final float[] win = new float[N];
    private final float[] cosT = new float[N / 2], sinT = new float[N / 2];
    private final int[] rev = new int[N];
    private float[] bandWeight = new float[N / 2 + 1];

    private float gain = 0f;            // 0 = normal sound, 1 = full karaoke (moves gently)
    private int wetFrames = 0;          // frames processed since the effect was last fully off
    private boolean wet = false;
    private int zeroFrames = 0;

    public KaraokeProcessor() {
        for (int i = 0; i < N; i++) win[i] = (float) Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));   // √Hann
        for (int i = 0; i < N / 2; i++) {
            cosT[i] = (float) Math.cos(-2 * Math.PI * i / N);
            sinT[i] = (float) Math.sin(-2 * Math.PI * i / N);
        }
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
        if (in.encoding != C.ENCODING_PCM_16BIT) { active = false; return AudioProcessor.AudioFormat.NOT_SET; }
        if (in.channelCount != 2) {
            status = 2;                              // mono: nothing to separate
            active = false;
            return AudioProcessor.AudioFormat.NOT_SET;
        }
        status = 1;
        active = true;
        sampleRate = in.sampleRate;
        bandWeight = new float[N / 2 + 1];
        for (int k = 0; k <= N / 2; k++) {
            float hz = k * (float) sampleRate / N;
            float w;
            if (hz < 120) w = 0;
            else if (hz < 220) w = (hz - 120) / 100f;
            else if (hz < 8000) w = 1;
            else if (hz < 14000) w = 1 - 0.6f * (hz - 8000) / 6000f;
            else w = 0.4f;
            bandWeight[k] = w;
        }
        return in;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int remaining = input.remaining();
        if (remaining == 0) return;
        lastAudioMs = System.currentTimeMillis();
        ByteBuffer out = replaceOutputBuffer(remaining);
        input.order(ByteOrder.nativeOrder());
        out.order(ByteOrder.nativeOrder());
        while (input.remaining() >= 4) {
            float l = input.getShort() / 32768f;
            float r = input.getShort() / 32768f;
            histL[N - HOP + filled] = l;
            histR[N - HOP + filled] = r;
            out.putShort(clip(readyL[filled]));
            out.putShort(clip(readyR[filled]));
            filled++;
            if (filled == HOP) { frame(); filled = 0; }
        }
        input.position(input.limit());
        out.flip();
    }

    /** One hop: produces the next HOP output samples (exactly N samples behind the input). */
    private void frame() {
        float target = enabled ? 1f : 0f;
        boolean needWet = enabled || gain > 0f || wet;

        if (needWet) {
            if (!wet) {                      // starting up: overlap-add must fill before it is used
                wet = true;
                wetFrames = 0;
                java.util.Arrays.fill(accL, 0);
                java.util.Arrays.fill(accR, 0);
            }
            float g = wetFrames >= 3 ? gain : 0f;
            process(g);
            wetFrames++;
            if (wetFrames > 3) gain = gain < target ? Math.min(target, gain + 0.08f) : Math.max(target, gain - 0.08f);
        }

        if (wet && wetFrames > 3) {
            // the overlap-add result for the oldest HOP samples is complete
            for (int i = 0; i < HOP; i++) { readyL[i] = accL[i] * 0.5f; readyR[i] = accR[i] * 0.5f; }
        } else {
            for (int i = 0; i < HOP; i++) { readyL[i] = histL[i]; readyR[i] = histR[i]; }
        }
        if (wet) {
            System.arraycopy(accL, HOP, accL, 0, N - HOP);
            System.arraycopy(accR, HOP, accR, 0, N - HOP);
            java.util.Arrays.fill(accL, N - HOP, N, 0);
            java.util.Arrays.fill(accR, N - HOP, N, 0);
            zeroFrames = !enabled && gain <= 0f ? zeroFrames + 1 : 0;
            if (zeroFrames >= 5) { wet = false; zeroFrames = 0; }      // fully back to normal: rest the CPU
        }
        System.arraycopy(histL, HOP, histL, 0, N - HOP);
        System.arraycopy(histR, HOP, histR, 0, N - HOP);
    }

    /** Window, FFT both channels at once (L real, R imaginary), remove the centre, inverse FFT, overlap-add. */
    private void process(float g) {
        for (int i = 0; i < N; i++) { re[i] = histL[i] * win[i]; im[i] = histR[i] * win[i]; }
        fft(re, im, false);
        if (g > 0f) {
            for (int k = 1; k < N / 2; k++) {
                int nk = N - k;
                // separate the two real spectra
                float lr = (re[k] + re[nk]) * 0.5f, li = (im[k] - im[nk]) * 0.5f;
                float rr = (im[k] + im[nk]) * 0.5f, ri = (re[nk] - re[k]) * 0.5f;
                float pl = lr * lr + li * li, pr = rr * rr + ri * ri;
                float cross = lr * rr + li * ri;                     // Re(L · conj R)
                float sim = 2f * cross / (pl + pr + 1e-12f);        // 1 = same in both channels
                float f = (sim - 0.55f) / 0.40f;
                if (f <= 0) continue;
                if (f > 1) f = 1;
                f = f * f * (3 - 2 * f);                             // smooth step
                float a = g * bandWeight[k] * f;
                float cr = (lr + rr) * 0.5f * a, ci = (li + ri) * 0.5f * a;
                lr -= cr; li -= ci; rr -= cr; ri -= ci;
                // back into the combined spectrum: Z = L + iR, and its mirror
                re[k] = lr - ri; im[k] = li + rr;
                re[nk] = lr + ri; im[nk] = -li + rr;
            }
        }
        fft(re, im, true);
        for (int i = 0; i < N; i++) {
            accL[i] += re[i] * win[i];
            accR[i] += im[i] * win[i];
        }
    }

    private void fft(float[] xr, float[] xi, boolean inverse) {
        for (int i = 0; i < N; i++) {
            int j = rev[i];
            if (j > i) {
                float t = xr[i]; xr[i] = xr[j]; xr[j] = t;
                t = xi[i]; xi[i] = xi[j]; xi[j] = t;
            }
        }
        for (int size = 2; size <= N; size <<= 1) {
            int half = size >> 1, step = N / size;
            for (int start = 0; start < N; start += size) {
                for (int k = 0; k < half; k++) {
                    float wr = cosT[k * step], wi = inverse ? -sinT[k * step] : sinT[k * step];
                    int a = start + k, b = a + half;
                    float tr = xr[b] * wr - xi[b] * wi, ti = xr[b] * wi + xi[b] * wr;
                    xr[b] = xr[a] - tr; xi[b] = xi[a] - ti;
                    xr[a] += tr; xi[a] += ti;
                }
            }
        }
        if (inverse) for (int i = 0; i < N; i++) { xr[i] /= N; xi[i] /= N; }
    }

    private static short clip(float v) {
        float s = v * 32768f;
        if (s > 32767f) return 32767;
        if (s < -32768f) return -32768;
        return (short) Math.round(s);
    }

    @Override
    protected void onFlush() {
        java.util.Arrays.fill(histL, 0); java.util.Arrays.fill(histR, 0);
        java.util.Arrays.fill(accL, 0); java.util.Arrays.fill(accR, 0);
        java.util.Arrays.fill(readyL, 0); java.util.Arrays.fill(readyR, 0);
        filled = 0;
        wet = false;
        gain = enabled ? 1f : 0f;
    }

    @Override
    protected void onReset() {
        onFlush();
        active = false;
    }
}
