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
import androidx.media3.exoplayer.analytics.AnalyticsListener;

/**
 * The sound focus for the app's players, kept by the app itself instead of by the player, so that songs and videos
 * carry on during a phone call. Another app that starts its own music for good still pauses this one; a short
 * interruption that is not a call (an alarm, a voice assistant) pauses it and it carries on afterwards.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class CallFocus implements Player.Listener, AudioManager.OnAudioFocusChangeListener {

    /** Another app took the sound for good. */
    interface Lost { void onLost(); }

    private final AudioManager am;
    private final ExoPlayer player;
    private final Lost lost;
    private final boolean music;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Object request;                  // AudioFocusRequest on Android 8+
    private boolean has = false, pausedByUs = false, pausing = false, transientLoss = false;

    private CallFocus(Context c, ExoPlayer p, boolean music, Lost lost) {
        this.am = c.getApplicationContext().getSystemService(AudioManager.class);
        this.player = p;
        this.music = music;
        this.lost = lost;
    }

    /** Looks after the sound focus of this player (built with the player's own focus handling switched off). */
    static void attach(Context c, ExoPlayer p, boolean music, Lost lost) {
        CallFocus f = new CallFocus(c, p, music, lost);
        p.addListener(f);
        p.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onPlayerReleased(EventTime t) { f.abandon(); }
        });
    }

    /** A call is ringing or going on (or another app is in a voice or video call). */
    private boolean inCall() {
        if (am == null) return false;
        int m = am.getMode();
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE
                || (Build.VERSION.SDK_INT >= 30 && m == AudioManager.MODE_CALL_SCREENING);
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        if (playWhenReady) { pausedByUs = false; take(); }
        else if (!pausing) { pausedByUs = false; abandon(); }      // paused by you: the focus is given back
    }

    @SuppressWarnings("deprecation")
    private void take() {
        if (has || am == null) return;
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            if (request == null) {
                request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(music ? AudioAttributes.CONTENT_TYPE_MUSIC : AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build())
                        .setWillPauseWhenDucked(false)               // Android lowers the sound by itself for short sounds
                        .setOnAudioFocusChangeListener(this, h)
                        .build();
            }
            r = am.requestAudioFocus((AudioFocusRequest) request);
        } else {
            r = am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        // during a call Android may refuse: the song or video plays anyway
        has = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @SuppressWarnings("deprecation")
    void abandon() {
        h.removeCallbacksAndMessages(null);
        if (!has || am == null) { has = false; return; }
        if (Build.VERSION.SDK_INT >= 26 && request != null) am.abandonAudioFocusRequest((AudioFocusRequest) request);
        else am.abandonAudioFocus(this);
        has = false;
    }

    private void pauseHere(boolean comeBack) {
        pausing = true;
        try { player.setPlayWhenReady(false); } catch (Throwable ignored) { }
        pausing = false;
        pausedByUs = comeBack;
    }

    /** A short interruption that turned out not to be a call: pause until it is over. */
    private void afterTransient() {
        if (transientLoss && !inCall() && player.getPlayWhenReady()) pauseHere(true);
    }

    private final Runnable checkTransient = this::afterTransient;

    @Override
    public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                has = true;
                transientLoss = false;
                h.removeCallbacks(checkTransient);
                if (pausedByUs) { pausedByUs = false; try { player.setPlayWhenReady(true); } catch (Throwable ignored) { } }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                transientLoss = false;
                h.removeCallbacks(checkTransient);
                if (inCall()) break;                                  // a call app asking for good: keep playing
                has = false;
                pauseHere(false);
                abandon();
                if (lost != null) lost.onLost();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                transientLoss = true;
                h.removeCallbacks(checkTransient);
                if (inCall()) break;                                  // a call: carry on playing
                h.postDelayed(checkTransient, 700);                   // the call's own mode can come a moment later
                break;
            default:                                                  // a short sound over this one: Android lowers it
                break;
        }
    }
}
