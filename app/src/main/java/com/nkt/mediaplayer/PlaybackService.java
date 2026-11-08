package com.nkt.mediaplayer;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
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

/** Plays music in the background with notification and lock-screen controls. */
public class PlaybackService extends MediaSessionService {
    public static PlaybackService instance;
    private MediaSession session;
    private ExoPlayer player;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long timerEndAt;           // 0 = off
    private boolean timerEndOfSong;
    private float savedVolume = 1f;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Context c = this;
        DefaultRenderersFactory rf = new DefaultRenderersFactory(c) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(new AudioProcessor[]{new KaraokeProcessor()})
                        .build();
            }
        };
        player = new ExoPlayer.Builder(c, rf)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int sid = am.generateAudioSessionId();
        player.setAudioSessionId(sid);
        AudioFx.attach(c, sid);
        player.setSkipSilenceEnabled(Store.bool(c, "skip_silence", false));
        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                MediaInfo m = Media.info(item);
                if (m != null) {
                    Store.countPlay(c, m.uri);
                    Store.addHistory(c, m);
                }
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (timerEndOfSong && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    timerEndOfSong = false;
                    player.setPauseAtEndOfMediaItems(false);
                }
            }
        });

        Intent open = new Intent(c, NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(c, player)
                .setSessionActivity(pi)
                .setCallback(new MediaSession.Callback() {
                    @Override
                    public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession s, MediaSession.ControllerInfo ci, List<MediaItem> items) {
                        List<MediaItem> out = new ArrayList<>();
                        for (MediaItem it : items) {
                            Uri u = it.requestMetadata.mediaUri;
                            out.add(it.buildUpon().setUri(u != null ? u : Uri.parse(it.mediaId)).build());
                        }
                        return Futures.immediateFuture(out);
                    }
                })
                .build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        AudioFx.release();
        if (session != null) { session.release(); session = null; }
        if (player != null) { player.release(); player = null; }
        instance = null;
        super.onDestroy();
    }

    public void setSkipSilence(boolean on) { if (player != null) player.setSkipSilenceEnabled(on); }

    // ---------------- sleep timer ----------------
    /** minutes > 0: stop after that long; -1: stop after this song; 0: off. */
    public void setTimer(int minutes) {
        h.removeCallbacks(tick);
        if (player == null) return;
        player.setVolume(savedVolume);
        timerEndOfSong = false;
        player.setPauseAtEndOfMediaItems(false);
        timerEndAt = 0;
        if (minutes == -1) {
            timerEndOfSong = true;
            player.setPauseAtEndOfMediaItems(true);
        } else if (minutes > 0) {
            timerEndAt = System.currentTimeMillis() + minutes * 60_000L;
            h.post(tick);
        }
    }

    public long timerLeftMs() { return timerEndAt == 0 ? 0 : Math.max(0, timerEndAt - System.currentTimeMillis()); }
    public boolean timerEndOfSong() { return timerEndOfSong; }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player == null || timerEndAt == 0) return;
            long left = timerEndAt - System.currentTimeMillis();
            if (left <= 0) {
                player.pause();
                player.setVolume(savedVolume);
                timerEndAt = 0;
                return;
            }
            if (left < 10_000) player.setVolume(Math.max(0.05f, savedVolume * left / 10_000f));
            h.postDelayed(this, 250);
        }
    };
}
