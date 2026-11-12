package com.nkt.mediaplayer;

import android.app.PendingIntent;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
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

    private boolean silent = false;        // swapping the picture of the current song is not a new song

    private long sleepAt = 0;              // clock time to stop, 0 = off
    private boolean sleepEndOfSong = false;

    @Override
    public void onCreate() {
        super.onCreate();
        // Karaoke runs inside the audio pipeline so it can be switched on while playing.
        KaraokeProcessor.enabled = Prefs.karaoke(this);
        KaraokeProcessor.strength = Prefs.karaokeStrength(this);
        player = Players.build(this, true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);           // keep playing with the screen off

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
                if (item == null || silent) return;
                Prefs.countPlay(PlaybackService.this, item.mediaId);
                History.record(PlaybackService.this, Media.info(item, 0));
                ArtFinder.auto(PlaybackService.this, Media.info(item, 0));
                widgetsSoon();
                applyKey();
                saveQueue();
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                saveQueue();
                widgetsSoon();
                h.removeCallbacks(saveTick);
                if (playing) h.postDelayed(saveTick, 5000);
            }
            @Override public void onTimelineChanged(androidx.media3.common.Timeline t, int reason) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue();
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
                .setBitmapLoader(new NotifArt(this))
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
        // status bar & lock screen: the NKT logo as the small icon (the app's name shows beside it)
        androidx.media3.session.DefaultMediaNotificationProvider np = new androidx.media3.session.DefaultMediaNotificationProvider.Builder(this).build();
        np.setSmallIcon(R.drawable.ic_stat_nkt);
        setMediaNotificationProvider(np);
        restoreQueue();
        instance = this;
    }

    /**
     * The logo or art changed: show the new picture in the notification now. The current item is
     * replaced with an identical copy, which makes the notification ask for its picture again.
     */
    public static void refreshArt() {
        PlaybackService s = instance;
        if (s == null || s.player == null) return;
        s.h.post(() -> {
            try {
                ExoPlayer p = s.player;
                if (p.getMediaItemCount() == 0 || p.isPlaying()) return;     // playing: the new picture shows from the next song
                int i = p.getCurrentMediaItemIndex();
                long pos = p.getCurrentPosition();
                MediaItem cur = p.getMediaItemAt(i);
                s.silent = true;
                p.replaceMediaItem(i, cur.buildUpon().setMediaMetadata(cur.mediaMetadata.buildUpon()
                        .setArtworkUri(cur.mediaMetadata.artworkUri == null ? null
                                : cur.mediaMetadata.artworkUri.buildUpon().appendQueryParameter("r", String.valueOf(System.nanoTime())).build())
                        .build()).build());
                p.seekTo(i, pos);
                s.h.postDelayed(() -> s.silent = false, 1500);
            } catch (Throwable ignored) { s.silent = false; }
        });
    }

    // ── Home-screen widgets ──

    private final Runnable widgetRun = new Runnable() {
        @Override public void run() {
            WidgetUpdater.update(PlaybackService.this);
            // keeps the progress line (and the lyric line) moving
            if (player != null && player.isPlaying()) h.postDelayed(this, Prefs.npNow(PlaybackService.this) == 1 ? 4_000 : 10_000);
        }
    };

    private void widgetsSoon() {
        h.removeCallbacks(widgetRun);
        h.postDelayed(widgetRun, 250);
    }

    public WidgetUpdater.Info widgetInfo() {
        WidgetUpdater.Info i = new WidgetUpdater.Info();
        if (player == null || player.getMediaItemCount() == 0) return i;
        androidx.media3.common.MediaMetadata md = player.getMediaMetadata();
        i.title = md.title == null ? "" : md.title.toString();
        i.artist = md.artist == null ? "" : md.artist.toString();
        i.playing = player.isPlaying();
        i.posMs = Math.max(0, player.getCurrentPosition());
        i.durMs = Math.max(0, player.getDuration());
        MediaItem cur = player.getCurrentMediaItem();
        if (cur != null) i.cur = Media.info(cur, i.durMs);
        return i;
    }

    /** Previous / play-pause / next, from a widget. */
    public void widgetCommand(String action) {
        if (player == null) return;
        if (WidgetUpdater.ACTION_PREV.equals(action)) player.seekToPrevious();
        else if (WidgetUpdater.ACTION_NEXT.equals(action)) player.seekToNext();
        else if (WidgetUpdater.ACTION_PLAY.equals(action)) {
            if (player.isPlaying()) player.pause(); else Media.play(player);
        }
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    // ── Karaoke key: semitones per song, speed unchanged ──

    public void applyKey() {
        if (player == null) return;
        androidx.media3.common.MediaItem item = player.getCurrentMediaItem();
        int semis = item == null ? 0 : Prefs.key(this, item.mediaId);
        float speed = player.getPlaybackParameters().speed;
        player.setPlaybackParameters(new androidx.media3.common.PlaybackParameters(speed, Prefs.pitchFor(semis)));
    }

    public String currentMediaId() {
        androidx.media3.common.MediaItem item = player == null ? null : player.getCurrentMediaItem();
        return item == null ? null : item.mediaId;
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

    // ── Carry on where you left off: the queue, the song and the moment ─────

    private final Runnable saveTick = new Runnable() {
        @Override public void run() {
            saveQueue();
            if (player != null && player.isPlaying()) h.postDelayed(this, 5000);
        }
    };

    private boolean restoring = false;

    void saveQueue() {
        if (player == null || restoring) return;
        try {
            int n = player.getMediaItemCount();
            if (n == 0) { Prefs.sp(this).edit().remove("last_queue").apply(); return; }
            org.json.JSONArray items = new org.json.JSONArray();
            for (int i = 0; i < n; i++) items.put(Media.info(player.getMediaItemAt(i), 0).toJson());
            org.json.JSONObject o = new org.json.JSONObject()
                    .put("items", items)
                    .put("index", player.getCurrentMediaItemIndex())
                    .put("pos", Math.max(0, player.getCurrentPosition()))
                    .put("shuffle", player.getShuffleModeEnabled())
                    .put("repeat", player.getRepeatMode());
            Prefs.sp(this).edit().putString("last_queue", o.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Puts the last queue back, paused at the same moment; play continues from there. */
    private void restoreQueue() {
        String json = Prefs.sp(this).getString("last_queue", null);
        if (json == null || player.getMediaItemCount() > 0) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            org.json.JSONArray a = o.getJSONArray("items");
            List<MediaInfo> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) list.add(MediaInfo.fromJson(a.getJSONObject(i)));
            if (list.isEmpty()) return;
            int index = Math.max(0, Math.min(list.size() - 1, o.optInt("index")));
            restoring = true;
            player.setMediaItems(Media.items(list), index, o.optLong("pos"));
            player.setShuffleModeEnabled(o.optBoolean("shuffle"));
            player.setRepeatMode(o.optInt("repeat", Player.REPEAT_MODE_OFF));
            player.setPlayWhenReady(false);
            restoring = false;
            // not prepared yet (no notification until you press play); play() prepares it
        } catch (Exception e) { restoring = false; }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiped away from recent apps: keep playing if music is on, otherwise stop.
        saveQueue();
        Player p = session == null ? null : session.getPlayer();
        if (p == null || !p.getPlayWhenReady() || p.getMediaItemCount() == 0) stopSelf();
    }

    @Override
    public void onDestroy() {
        saveQueue();
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
