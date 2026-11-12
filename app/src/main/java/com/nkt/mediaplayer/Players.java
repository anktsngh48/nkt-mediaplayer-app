package com.nkt.mediaplayer;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** One way to build a player: karaoke in the sound path, and files on other devices (ftp://) playable. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class Players {

    private Players() { }

    public static ExoPlayer build(Context c, boolean music) {
        final Context app = c.getApplicationContext();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(app) {
            @Override
            protected AudioSink buildAudioSink(Context ctx, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                // 16-bit output keeps every song in the processor chain (float output would skip it)
                return new DefaultAudioSink.Builder(ctx)
                        .setAudioProcessors(new AudioProcessor[]{new AnalysisProcessor(), new KaraokeProcessor()})
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build();
            }
        };
        return new ExoPlayer.Builder(app, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(() -> new Routing(app)))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(music ? C.AUDIO_CONTENT_TYPE_MUSIC : C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build();
    }

    /** ftp:// to FtpDataSource; everything else (files, content, http) to the usual source. */
    static final class Routing implements DataSource {
        private final Context app;
        private final List<TransferListener> listeners = new ArrayList<>();
        private DataSource current;

        Routing(Context app) { this.app = app; }

        @Override public void addTransferListener(TransferListener t) { listeners.add(t); }

        @Override
        public long open(DataSpec spec) throws IOException {
            Uri u = spec.uri;
            current = "ftp".equalsIgnoreCase(u.getScheme()) ? new FtpDataSource(app) : new DefaultDataSource.Factory(app).createDataSource();
            for (TransferListener t : listeners) current.addTransferListener(t);
            return current.open(spec);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException { return current.read(buffer, offset, length); }

        @Override public Uri getUri() { return current == null ? null : current.getUri(); }

        @Override
        public java.util.Map<String, List<String>> getResponseHeaders() {
            return current == null ? java.util.Collections.emptyMap() : current.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            if (current != null) { try { current.close(); } finally { current = null; } }
        }
    }
}
