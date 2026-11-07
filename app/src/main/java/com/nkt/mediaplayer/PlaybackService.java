package com.nkt.mediaplayer;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays music in the background. Media3 shows the notification and the lock-screen
 * controls (play/pause, next, previous, seek) automatically from this session.
 * Also runs the equalizer, karaoke, sleep timer, skip-silence and play counts.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlaybackService extends MediaSessionService {

    private static final long FADE_MS = 10_000;

    /** The running service (same process), for the timer and effects screens. */
    public static PlaybackService instance;

    private MediaSession session;
    private ExoPlayer player;
    private final Handler h = new Handler(Looper.getMainLooper());

    private long sleepAt = 0;              // clock time to stop, 0 = off
    private boolean sleepEndOfSong = false;

    @Override
    public void onCreate() {
        super.onCreate();
        // Karaoke runs inside the audio pipeline so it can be switched on while playing.
        KaraokeProcessor.enabled = Prefs.sp(this).getBoolean("karaoke", false);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(Context c, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(c)
                        .setAudioProcessors(new AudioProcessor[]{new KaraokeProcessor()})
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build();
            }
        };
        player = new ExoPlayer.Builder(this, renderers)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)          // pause when headphones are unplugged
                .setWakeMode(C.WAKE_MODE_NETWORK)           // keep playing with the screen off
                .build();

        // A fixed audio session so the equalizer always has something to attach to.
        try {
            AudioManager am = getSystemService(AudioManager.class);
            int sid = am.generateAudioSessionId();
            if (sid > 0) {
                player.setAudioSessionId(sid);
                AudioFx.attach(this, sid);
            }
        } catch (Throwable ignored) { }

        player.setSkipSilenceEnabled(Prefs.sp(this).getBoolean("skip_silence", false));
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                if (item == null) return;
                Prefs.countPlay(PlaybackService.this, item.mediaId);
                History.record(PlaybackService.this, Media.info(item, 0));
            }
            @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepEndOfSong) {
                    sleepEndOfSong = false;
                    player.setPauseAtEndOfMediaItems(false);
                }
            }
        });

        Intent open = new Intent(this, NowPlayingActivity.class);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        session = new MediaSession.Builder(this, player)
                .setSessionActivity(tap)
                .setCallback(new MediaSession.Callback() {
                    @Override
                    public ListenableFuture<List<MediaItem>> onAddMediaItems(
                            MediaSession mediaSession, MediaSession.ControllerInfo controller, List<MediaItem> mediaItems) {
                        // Items sent from the screens carry their address in requestMetadata.mediaUri
                        List<MediaItem> out = new ArrayList<>();
                        for (MediaItem it : mediaItems) {
                            if (it.localConfiguration == null && it.requestMetadata.mediaUri != null) {
                                out.add(it.buildUpon().setUri(it.requestMetadata.mediaUri).build());
                            } else {
                                out.add(it);
                            }
                        }
                        return Futures.immediateFuture(out);
                    }
                })
                .build();
        instance = this;
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    // ── Sleep timer ──────────────────────────────────────────────────────────

    public void setSleepMinutes(int minutes) {
        cancelSleep();
        if (minutes <= 0) return;
        sleepAt = System.currentTimeMillis() + minutes * 60_000L;
        h.post(sleepTick);
    }

    public void setSleepEndOfSong() {
        cancelSleep();
        sleepEndOfSong = true;
        player.setPauseAtEndOfMediaItems(true);
    }

    public void cancelSleep() {
        h.removeCallbacks(sleepTick);
        sleepAt = 0;
        if (sleepEndOfSong) player.setPauseAtEndOfMediaItems(false);
        sleepEndOfSong = false;
        player.setVolume(1f);
    }

    public boolean sleepIsEndOfSong() { return sleepEndOfSong; }

    /** Milliseconds left, or 0 if no timer. */
    public long sleepLeftMs() { return sleepAt == 0 ? 0 : Math.max(0, sleepAt - System.currentTimeMillis()); }

    private final Runnable sleepTick = new Runnable() {
        @Override public void run() {
            if (sleepAt == 0) return;
            long left = sleepAt - System.currentTimeMillis();
            if (left <= 0) {
                player.pause();
                player.setVolume(1f);
                sleepAt = 0;
                return;
            }
            // gentle fade-out over the last 10 seconds
            player.setVolume(left < FADE_MS ? Math.max(0.05f, left / (float) FADE_MS) : 1f);
            h.postDelayed(this, left < FADE_MS ? 200 : 1000);
        }
    };

    // ── Skip silence ─────────────────────────────────────────────────────────

    public boolean skipSilence() { return player.getSkipSilenceEnabled(); }

    public void setSkipSilence(boolean on) {
        player.setSkipSilenceEnabled(on);
        Prefs.sp(this).edit().putBoolean("skip_silence", on).apply();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiped away from recent apps: keep playing if music is on, otherwise stop.
        Player p = session == null ? null : session.getPlayer();
        if (p == null || !p.getPlayWhenReady() || p.getMediaItemCount() == 0) stopSelf();
    }

    @Override
    public void onDestroy() {
        instance = null;
        h.removeCallbacksAndMessages(null);
        AudioFx.release();
        if (session != null) {
            session.getPlayer().release();
            session.release();
            session = null;
        }
        super.onDestroy();
    }
}
