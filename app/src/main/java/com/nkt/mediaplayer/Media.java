package com.nkt.mediaplayer;

import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.os.Build;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/** Helpers for talking to the background player. */
public final class Media {

    private Media() { }

    static final Uri LOGO = Uri.parse("android.resource://com.nkt.mediaplayer/drawable/art_logo");

    public static MediaItem item(MediaInfo m) {
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .setTitle(m.title)
                .setArtist(m.artist.isEmpty() ? null : m.artist)
                .setAlbumTitle(m.album.isEmpty() ? null : m.album)
                .setMediaType(m.video ? MediaMetadata.MEDIA_TYPE_VIDEO : MediaMetadata.MEDIA_TYPE_MUSIC);
        // Older phones: point the notification at the album art. Newer ones read it from the file itself.
        if (!m.video && m.albumId > 0 && Build.VERSION.SDK_INT < 29) {
            md.setArtworkUri(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), m.albumId));
        } else {
            // lock screen & notification: the song's own art when it has some, otherwise the NKT Media Player logo
            md.setArtworkUri(LOGO);
        }
        return new MediaItem.Builder()
                .setMediaId(m.uri.toString())
                .setUri(m.uri)
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder().setMediaUri(m.uri).build())
                .setMediaMetadata(md.build())
                .build();
    }

    /** Play; a queue put back from last time is prepared first. */
    public static void play(androidx.media3.common.Player p) {
        if (p == null) return;
        if (p.getPlaybackState() == androidx.media3.common.Player.STATE_IDLE && p.getMediaItemCount() > 0) p.prepare();
        if (p.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED) p.seekToDefaultPosition();
        p.play();
    }

    public static List<MediaItem> items(List<MediaInfo> list) {
        List<MediaItem> out = new ArrayList<>();
        for (MediaInfo m : list) out.add(item(m));
        return out;
    }

    public static ListenableFuture<MediaController> connect(Context c) {
        SessionToken token = new SessionToken(c, new ComponentName(c, PlaybackService.class));
        return new MediaController.Builder(c, token).buildAsync();
    }

    /** The song currently loaded in the player, as a MediaInfo (for "Add to playlist"). */
    public static MediaInfo current(MediaController ctl) {
        if (ctl == null || ctl.getCurrentMediaItem() == null) return null;
        return info(ctl.getCurrentMediaItem(), ctl.getDuration() > 0 ? ctl.getDuration() : 0);
    }

    /** Any queued item, as a MediaInfo. */
    public static MediaInfo info(MediaItem it, long durationMs) {
        MediaMetadata md = it.mediaMetadata;
        Uri uri = Uri.parse(it.mediaId);
        long id = -1;
        try { if ("content".equals(uri.getScheme())) id = ContentUris.parseId(uri); } catch (Exception ignored) { }
        MediaInfo m = new MediaInfo(id, uri,
                md.title == null ? "Unknown" : md.title.toString(),
                md.artist == null ? "" : md.artist.toString(),
                md.albumTitle == null ? "" : md.albumTitle.toString(),
                -1, "", Math.max(0, durationMs), isVideo(md));
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            m.path = uri.getPath();
            m.fileName = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        }
        return m;
    }

    public static boolean isVideo(MediaMetadata md) {
        return md != null && md.mediaType != null && md.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO;
    }
}
