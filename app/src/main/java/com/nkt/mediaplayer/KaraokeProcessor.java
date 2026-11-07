package com.nkt.mediaplayer;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Karaoke: removes sound panned to the centre of a stereo mix (usually the lead vocal).
 *   mid  = (L + R) / 2     ← vocals, kick, bass
 *   side = (L − R) / 2     ← everything spread left/right
 *   out  = side ± low-passed mid   (the bass is put back so the music stays full)
 * Can be switched on and off while playing. Mono audio passes through untouched.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class KaraokeProcessor extends BaseAudioProcessor {

    /** Switched from the Equalizer screen. */
    public static volatile boolean enabled = false;

    private static final float BASS_CUTOFF_HZ = 150f;

    private float alpha = 0f;      // one-pole low-pass coefficient
    private float low = 0f;        // low-pass state
    private float mix = 0f;        // 0 = normal, 1 = karaoke; eased to avoid clicks

    @Override
    protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
        // Only 16-bit stereo is changed; anything else passes straight through (inactive).
        if (in.encoding != C.ENCODING_PCM_16BIT || in.channelCount != 2) return AudioProcessor.AudioFormat.NOT_SET;
        float dt = 1f / in.sampleRate;
        float rc = 1f / (2f * (float) Math.PI * BASS_CUTOFF_HZ);
        alpha = dt / (rc + dt);
        return in;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int remaining = input.remaining();
        if (remaining == 0) return;
        ByteBuffer out = replaceOutputBuffer(remaining);
        input.order(ByteOrder.LITTLE_ENDIAN);
        out.order(ByteOrder.LITTLE_ENDIAN);
        float target = enabled ? 1f : 0f;
        float step = 1f / 2048f;
        while (input.remaining() >= 4) {
            float l = input.getShort();
            float r = input.getShort();
            float mid = (l + r) * 0.5f;
            low += alpha * (mid - low);
            if (mix != target) mix = target > mix ? Math.min(target, mix + step) : Math.max(target, mix - step);
            float side = (l - r) * 0.5f;
            float kl = side + low, kr = -side + low;
            float ol = l + (kl - l) * mix;
            float or = r + (kr - r) * mix;
            out.putShort(clip(ol));
            out.putShort(clip(or));
        }
        input.position(input.limit());
        out.flip();
    }

    private static short clip(float v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
    }

    @Override
    protected void onReset() {
        low = 0f;
        mix = enabled ? 1f : 0f;
    }
}
