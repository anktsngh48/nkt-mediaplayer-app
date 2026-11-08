package com.nkt.mediaplayer;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Removes the lead vocal from 16-bit stereo audio by taking out what is identical in both channels,
 * in the frequency range of the human voice only, so bass and drums stay.
 * Always delays audio by the same fixed amount, so switching on or off never skips.
 */
public class KaraokeProcessor extends BaseAudioProcessor {
    public static volatile boolean enabled;
    public static volatile float strength = 0.85f;
    public static volatile long lastAudioMs;
    public static volatile boolean lastWasMono;

    private static final int N = 2048, HOP = 512, D = N - HOP;
    private final float[] win = new float[N];
    private final float[] inL = new float[N], inR = new float[N];   // last N input samples
    private final float[] olaL = new float[N], olaR = new float[N]; // overlap-add accumulator
    private final float[] delL = new float[D], delR = new float[D]; // plain delay line
    private final float[] re = new float[N], im = new float[N];
    private final float[] bandW = new float[N / 2 + 1];
    private int fill, delPos, ring, offHops = 100, warm;
    private float gain;
    private boolean stereo16;
    private final float[] outL = new float[HOP], outR = new float[HOP];

    public KaraokeProcessor() {
        for (int i = 0; i < N; i++) win[i] = (float) Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat f) throws UnhandledAudioFormatException {
        stereo16 = f.encoding == C.ENCODING_PCM_16BIT && f.channelCount == 2;
        lastWasMono = f.channelCount == 1;
        if (!stereo16) return AudioFormat.NOT_SET;
        for (int k = 0; k <= N / 2; k++) {
            float hz = (float) k * f.sampleRate / N, w;
            if (hz < 120) w = 0;
            else if (hz < 220) w = (hz - 120) / 100f;
            else if (hz <= 8000) w = 1;
            else if (hz < 14000) w = 1 - 0.6f * (hz - 8000) / 6000f;
            else w = 0.4f;
            bandW[k] = w;
        }
        return f;
    }

    @Override
    public void queueInput(ByteBuffer in) {
        int frames = in.remaining() / 4;
        if (frames == 0) return;
        lastAudioMs = System.currentTimeMillis();
        in.order(ByteOrder.nativeOrder());
        int hops = (fill + frames) / HOP;
        ByteBuffer out = replaceOutputBuffer(hops * HOP * 4);
        out.order(ByteOrder.nativeOrder());
        for (int i = 0; i < frames; i++) {
            float l = in.getShort() / 32768f, r = in.getShort() / 32768f;
            inL[ring] = l;
            inR[ring] = r;
            ring = (ring + 1) % N;
            // plain delay line, D = N - HOP samples late (lines up with the overlap-add output)
            outL[fill] = delL[delPos];
            outR[fill] = delR[delPos];
            delL[delPos] = l;
            delR[delPos] = r;
            delPos = (delPos + 1) % D;
            fill++;
            if (fill == HOP) {
                processHop();
                for (int k = 0; k < HOP; k++) {
                    out.putShort(clip(outL[k]));
                    out.putShort(clip(outR[k]));
                }
                fill = 0;
            }
        }
        out.flip();
    }

    private void processHop() {
        float target = enabled ? strength : 0f;
        if (gain < target) gain = Math.min(target, gain + 0.08f);
        else if (gain > target) gain = Math.max(target, gain - 0.08f);
        if (gain <= 0f && !enabled) {
            offHops++;
            if (offHops > 5) { warm = 0; return; } // passthrough via delay line
        } else {
            offHops = 0;
        }
        // Analysis: window last N samples, one complex FFT holding L (real) and R (imag).
        for (int i = 0; i < N; i++) {
            int p = (ring + i) % N; // oldest sample first
            re[i] = inL[p] * win[i];
            im[i] = inR[p] * win[i];
        }
        fft(re, im, false);
        for (int k = 1; k < N / 2; k++) {
            int j = N - k;
            float zr = re[k], zi = im[k], wr = re[j], wi = im[j];
            float lr = (zr + wr) / 2f, li = (zi - wi) / 2f;   // L_k
            float rr = (zi + wi) / 2f, ri = (wr - zr) / 2f;   // R_k
            float dot = lr * rr + li * ri, en = lr * lr + li * li + rr * rr + ri * ri;
            float sim = en > 1e-12f ? 2f * dot / en : 0f;
            float t = Math.max(0f, Math.min(1f, (sim - 0.55f) / 0.40f));
            float f = t * t * (3 - 2 * t);
            float a = gain * bandW[k] * f;
            float cr = (lr + rr) / 2f, ci = (li + ri) / 2f;
            lr -= a * cr; li -= a * ci; rr -= a * cr; ri -= a * ci;
            // back to Z_k = L + iR and Z_{N-k} = conj(L) + i conj(R)
            re[k] = lr - ri; im[k] = li + rr;
            re[j] = lr + ri; im[j] = -li + rr;
        }
        fft(re, im, true);
        // Overlap-add with synthesis window, scale 0.5 (sqrt-Hann² at 75% overlap sums to 2).
        System.arraycopy(olaL, HOP, olaL, 0, N - HOP);
        System.arraycopy(olaR, HOP, olaR, 0, N - HOP);
        for (int i = N - HOP; i < N; i++) { olaL[i] = 0; olaR[i] = 0; }
        for (int i = 0; i < N; i++) { olaL[i] += re[i] * win[i] * 0.5f; olaR[i] += im[i] * win[i] * 0.5f; }
        if (warm < 3) { warm++; return; } // keep delayed passthrough until the accumulator is full
        // The first HOP samples of the accumulator are complete and line up with the N-sample delay.
        for (int k = 0; k < HOP; k++) { outL[k] = olaL[k]; outR[k] = olaR[k]; }
    }

    private static short clip(float v) {
        int s = Math.round(v * 32767f);
        return (short) Math.max(-32768, Math.min(32767, s));
    }

    @Override
    protected void onFlush() {
        java.util.Arrays.fill(inL, 0); java.util.Arrays.fill(inR, 0);
        java.util.Arrays.fill(olaL, 0); java.util.Arrays.fill(olaR, 0);
        java.util.Arrays.fill(delL, 0); java.util.Arrays.fill(delR, 0);
        fill = 0; delPos = 0; ring = 0; warm = 0;
    }

    @Override
    protected void onReset() { onFlush(); gain = 0; offHops = 100; }

    /** In-place radix-2 FFT. */
    static void fft(float[] re, float[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    float t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
        if (inverse) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }

    public static String status() {
        if (lastWasMono) return "This song is mono (one channel), so the voice can't be separated";
        if (System.currentTimeMillis() - lastAudioMs < 2500) return enabled ? "● Removing vocals on this song" : "Ready";
        return "Play a song to hear it";
    }
}
