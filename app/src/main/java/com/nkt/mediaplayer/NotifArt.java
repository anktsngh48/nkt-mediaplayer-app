package com.nkt.mediaplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.media3.common.util.BitmapLoader;
import androidx.media3.datasource.DataSourceBitmapLoader;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The picture for the notification, the lock screen and Bluetooth displays: the song's album art when it has
 * some (or the one downloaded for it), otherwise the app logo. Items carry an address like
 * nktart://song?u=…&a=…  or  nktart://logo  that this class turns into a picture.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class NotifArt implements BitmapLoader {

    public static final String SCHEME = "nktart";
    private static final int SIZE = 512;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private final Context app;
    private final DataSourceBitmapLoader fallback;

    public NotifArt(Context c) {
        app = c.getApplicationContext();
        fallback = new DataSourceBitmapLoader(app);
    }

    /** The address to put in a song's metadata. */
    public static Uri forSong(MediaInfo m) {
        if (m.video) return logoUri();
        return new Uri.Builder().scheme(SCHEME).authority("song")
                .appendQueryParameter("u", m.uri.toString())
                .appendQueryParameter("a", String.valueOf(m.albumId))
                .appendQueryParameter("h", m.hidden ? "1" : "0")
                .build();
    }

    public static Uri logoUri() { return new Uri.Builder().scheme(SCHEME).authority("logo").build(); }

    @Override public boolean supportsMimeType(String mimeType) { return fallback.supportsMimeType(mimeType); }

    @Override public ListenableFuture<Bitmap> decodeBitmap(byte[] data) { return fallback.decodeBitmap(data); }

    @Override
    public ListenableFuture<Bitmap> loadBitmap(Uri uri) {
        if (!SCHEME.equals(uri.getScheme())) return fallback.loadBitmap(uri);
        SettableFuture<Bitmap> f = SettableFuture.create();
        POOL.execute(() -> {
            try {
                f.set(resolve(uri));
            } catch (Throwable t) {
                f.set(AppLogo.bitmap(app, SIZE));
            }
        });
        return f;
    }

    /** The picture Now Playing would show for this song (own art, one found online, or the app logo). Not on the main thread. */
    public static Bitmap bitmapFor(Context c, MediaInfo m, int size) {
        try {
            Bitmap b = new NotifArt(c).resolve(forSong(m));
            if (b != null) return b;
        } catch (Throwable ignored) { }
        return AppLogo.bitmap(c.getApplicationContext(), size);
    }

    /** The song's own (or downloaded) picture, or null when it has none (the app logo is not returned). Not on the main thread. */
    public static Bitmap artFor(Context c, MediaInfo m) {
        try { return new NotifArt(c).resolveArt(forSong(m)); } catch (Throwable t) { return null; }
    }

    private Bitmap resolve(Uri uri) {
        Bitmap b = resolveArt(uri);
        return b != null ? b : AppLogo.bitmap(app, SIZE);
    }

    private Bitmap resolveArt(Uri uri) {
        if ("song".equals(uri.getAuthority())) {
            String u = uri.getQueryParameter("u");
            if (u != null) {
                Uri song = Uri.parse(u);
                long id = -1;
                try { if ("content".equals(song.getScheme())) id = android.content.ContentUris.parseId(song); } catch (Exception ignored) { }
                long albumId = -1;
                try { albumId = Long.parseLong(uri.getQueryParameter("a")); } catch (Exception ignored) { }
                MediaInfo m = new MediaInfo(id, song, "", "", "", albumId, "", 0, false);
                m.hidden = "1".equals(uri.getQueryParameter("h"));
                Bitmap own = Thumbs.fetch(app, m, SIZE);
                if (own != null) return own;
                // none yet: look on the internet for it (the notification shows the logo until this is done)
                Info info = Info.of(app, song);
                MediaInfo named = new MediaInfo(id, song, info.title, info.artist, info.album, albumId, "", 0, false);
                if (ArtFinder.ensure(app, named)) {
                    Bitmap got = ArtStore.load(app, m, SIZE);
                    if (got != null) return got;
                }
            }
        }
        return null;
    }

    /** Title, artist and album read from the file, for the internet search. */
    private static final class Info {
        String title = "", artist = "", album = "";

        static Info of(Context c, Uri uri) {
            Info i = new Info();
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(c, uri);
                String t = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
                String a = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
                String b = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM);
                i.title = t == null ? "" : t;
                i.artist = a == null ? "" : a;
                i.album = b == null ? "" : b;
            } catch (Throwable ignored) {
            } finally {
                try { r.release(); } catch (Throwable ignored) { }
            }
            if (i.title.isEmpty()) {
                String n = uri.getLastPathSegment();
                if (n != null) {
                    int slash = n.lastIndexOf('/');
                    if (slash >= 0) n = n.substring(slash + 1);
                    int dot = n.lastIndexOf('.');
                    i.title = dot > 0 ? n.substring(0, dot) : n;
                }
            }
            return i;
        }
    }
}
