package com.nkt.mediaplayer;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Listens to the sound on its way to the speaker (it does not change it) and hands a mono, low-rate copy to
 * {@link AudioAnalyzer}, which finds the beat and the sung note for the visualiser. Needs no microphone
 * permission: it only sees what the player itself is playing.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class AnalysisProcessor extends BaseAudioProcessor {

    private boolean floatPcm;
    private int channels = 2;
    private int decim = 4;                 // ~11 kHz out of 44.1 / 48 kHz
    private int phase = 0;
    private float acc = 0f;

    @Override
    protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
        if (in.encoding != C.ENCODING_PCM_16BIT && in.encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET;
        floatPcm = in.encoding == C.ENCODING_PCM_FLOAT;
        channels = Math.max(1, in.channelCount);
        decim = Math.max(1, Math.round(in.sampleRate / (float) AudioAnalyzer.RATE));
        AudioAnalyzer.setSourceRate(in.sampleRate / decim);
        phase = 0;
        acc = 0f;
        return in;                         // same format out: nothing is changed
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int remaining = input.remaining();
        if (remaining == 0) return;
        ByteBuffer out = replaceOutputBuffer(remaining);
        if (AudioAnalyzer.wanted()) tap(input.duplicate().order(ByteOrder.nativeOrder()));
        out.put(input);
        out.flip();
    }

    private void tap(ByteBuffer b) {
        int step = floatPcm ? 4 : 2;
        int frame = channels * step;
        float[] block = AudioAnalyzer.scratch();
        int n = 0;
        while (b.remaining() >= frame) {
            float s = floatPcm ? b.getFloat() : b.getShort() / 32768f;
            if (channels > 1) {
                float r = floatPcm ? b.getFloat() : b.getShort() / 32768f;
                s = (s + r) * 0.5f;
                if (channels > 2) b.position(b.position() + (channels - 2) * step);
            }
            acc += s;
            if (++phase >= decim) {
                block[n++] = acc / decim;
                acc = 0f;
                phase = 0;
                if (n == block.length) { AudioAnalyzer.push(block, n); n = 0; }
            }
        }
        if (n > 0) AudioAnalyzer.push(block, n);
    }

    @Override protected void onFlush() { AudioAnalyzer.flushed(); phase = 0; acc = 0f; }

    @Override protected void onReset() { onFlush(); }
}
