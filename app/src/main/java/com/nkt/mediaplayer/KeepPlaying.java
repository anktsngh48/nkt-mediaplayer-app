package com.nkt.mediaplayer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * The app's own hold on the sound. Android's usual handling pauses music for a phone call; here a call, an
 * alarm or a notification (a short loss) is ignored, so songs and videos keep playing during calls. Only when
 * another player takes the sound for good (you start music in another app, or Listen starts) does it pause.
 */
final class KeepPlaying implements AudioManager.OnAudioFocusChangeListener {

    private final AudioManager am;
    private final ExoPlayer player;
    private final Runnable onLost;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioFocusRequest request;
    private boolean held = false;

    private KeepPlaying(Context c, ExoPlayer p, Runnable onLost) {
        this.am = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        this.player = p;
        this.onLost = onLost;
    }

    /** Builds the players with handleAudioFocus = false, then calls this. {@code onLost} runs when another player takes the sound for good (null: pause). */
    static KeepPlaying attach(Context c, ExoPlayer p, Runnable onLost) {
        final KeepPlaying k = new KeepPlaying(c, p, onLost);
        p.addListener(new Player.Listener() {
            @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (playWhenReady) k.take(); else k.release();
            }
        });
        if (p.getPlayWhenReady()) k.take();
        return k;
    }

    @SuppressWarnings("deprecation")
    private void take() {
        if (held || am == null) return;
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            if (request == null) {
                request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(this, main)
                        .build();
            }
            r = am.requestAudioFocus(request);
        } else {
            r = am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        held = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED || r == AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
    }

    @SuppressWarnings("deprecation")
    void release() {
        if (!held || am == null) return;
        held = false;
        if (Build.VERSION.SDK_INT >= 26) { if (request != null) am.abandonAudioFocusRequest(request); }
        else am.abandonAudioFocus(this);
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (change != AudioManager.AUDIOFOCUS_LOSS) return;      // a call, an alarm, a notification: keep playing
        held = false;
        main.post(() -> {
            if (onLost != null) onLost.run();
            else { try { player.pause(); } catch (Throwable ignored) { } }
        });
    }
}
