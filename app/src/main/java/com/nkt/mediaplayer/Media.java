package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Talks to the background music service and opens any file in the right screen. */
public final class Media {
    private Media() {}

    private static MediaController controller;
    private static ListenableFuture<MediaController> pending;
    private static final List<Consumer<MediaController>> waiting = new ArrayList<>();

    /** Passes the connected controller (connecting first if needed). */
    public static void get(Context ctx, Consumer<MediaController> use) {
        if (controller != null && controller.isConnected()) { use.accept(controller); return; }
        waiting.add(use);
        if (pending != null) return;
        Context c = ctx.getApplicationContext();
        SessionToken token = new SessionToken(c, new ComponentName(c, PlaybackService.class));
        pending = new MediaController.Builder(c, token).buildAsync();
        pending.addListener(() -> {
            try {
                controller = pending.get();
            } catch (Exception e) {
                controller = null;
            }
            pending = null;
            List<Consumer<MediaController>> l = new ArrayList<>(waiting);
            waiting.clear();
            if (controller != null) for (Consumer<MediaController> u : l) u.accept(controller);
        }, r -> new android.os.Handler(android.os.Looper.getMainLooper()).post(r));
    }

    public static MediaController now() { return controller != null && controller.isConnected() ? controller : null; }

    public static MediaItem item(MediaInfo m) {
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .setTitle(m.title.isEmpty() ? m.name : m.title)
                .setArtist(m.isVideo() ? "Video" : m.artistOrUnknown())
                .setAlbumTitle(m.album)
                .setMediaType(m.isVideo() ? MediaMetadata.MEDIA_TYPE_VIDEO : MediaMetadata.MEDIA_TYPE_MUSIC);
        if (m.albumId > 0 && Build.VERSION.SDK_INT < 29) {
            md.setArtworkUri(Uri.parse("content://media/external/audio/albumart/" + m.albumId));
        }
        Bundle extras = new Bundle();
        extras.putString("json", m.toJson().toString());
        md.setExtras(extras);
        return new MediaItem.Builder()
                .setMediaId(m.uri)
                .setUri(m.uri)
                .setMediaMetadata(md.build())
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder().setMediaUri(m.u()).setExtras(extras).build())
                .build();
    }

    public static MediaInfo info(MediaItem it) {
        if (it == null) return null;
        Bundle e = it.mediaMetadata.extras;
        if (e != null && e.getString("json") != null) {
            try { return MediaInfo.fromJson(new org.json.JSONObject(e.getString("json"))); } catch (Exception ignored) {}
        }
        MediaInfo m = new MediaInfo();
        m.uri = it.mediaId;
        m.title = it.mediaMetadata.title == null ? "" : it.mediaMetadata.title.toString();
        m.artist = it.mediaMetadata.artist == null ? "" : it.mediaMetadata.artist.toString();
        return m;
    }

    /** Play songs in the background service, starting at index. */
    public static void playSongs(Activity a, List<MediaInfo> list, int index, boolean openNowPlaying) {
        if (list.isEmpty()) return;
        List<MediaItem> items = new ArrayList<>();
        for (MediaInfo m : list) items.add(item(m));
        get(a, c -> {
            c.setMediaItems(items, Math.max(0, index), 0);
            c.prepare();
            c.play();
        });
        if (openNowPlaying) a.startActivity(new Intent(a, NowPlayingActivity.class));
    }

    // ---------- shared hand-off lists for screens ----------
    public static List<MediaInfo> handoff = new ArrayList<>();

    public static void playVideos(Activity a, List<MediaInfo> list, int index) {
        handoff = new ArrayList<>(list);
        Intent i = new Intent(a, PlayerActivity.class);
        i.putExtra("index", index);
        if (index >= 0 && index < list.size()) i.putExtra("uri", list.get(index).uri).putExtra("title", list.get(index).title);
        a.startActivity(i);
    }

    public static void view(Activity a, List<MediaInfo> list, int index) {
        handoff = new ArrayList<>(list);
        Intent i = new Intent(a, ViewerActivity.class);
        i.putExtra("index", index);
        a.startActivity(i);
    }

    /** Open any file the way a file manager would. */
    public static void openFile(Activity a, File f, List<File> siblings) {
        String n = f.getName();
        int kind = MediaInfo.kindOf(n);
        if (kind >= 0) {
            List<MediaInfo> same = new ArrayList<>();
            int idx = 0;
            for (File s : siblings) {
                int k = MediaInfo.kindOf(s.getName());
                boolean match = kind == MediaInfo.AUDIO ? k == MediaInfo.AUDIO : (k == MediaInfo.IMAGE || k == MediaInfo.VIDEO);
                if (kind == MediaInfo.VIDEO && k != MediaInfo.VIDEO && !match) continue;
                if (!match) continue;
                if (s.equals(f)) idx = same.size();
                MediaInfo m = MediaInfo.fromFile(s);
                MediaInfo known = null;
                for (MediaInfo x : k == MediaInfo.AUDIO ? Library.songs : k == MediaInfo.VIDEO ? Library.videos : Library.images)
                    if (x.path.equals(s.getAbsolutePath())) { known = x; break; }
                same.add(known != null ? known : m);
            }
            if (kind == MediaInfo.AUDIO) playSongs(a, same, idx, true);
            else if (kind == MediaInfo.VIDEO) {
                List<MediaInfo> vids = new ArrayList<>();
                int vi = 0;
                for (MediaInfo m : same) { if (m.isVideo()) { if (m.path.equals(f.getAbsolutePath())) vi = vids.size(); vids.add(m); } }
                playVideos(a, vids, vi);
            } else view(a, same, idx);
            return;
        }
        String e = MediaInfo.ext(n);
        if (MediaInfo.isText(n)) {
            a.startActivity(new Intent(a, TextEditorActivity.class).putExtra("path", f.getAbsolutePath()));
        } else if (e.equals("pdf")) {
            a.startActivity(new Intent(a, PdfActivity.class).putExtra("path", f.getAbsolutePath()));
        } else {
            openExternal(a, f, MediaInfo.mimeOf(n));
        }
    }

    /** Hands a file to another app; for .apk files Android shows its own install prompt. */
    public static void openExternal(Activity a, File f, String mime) {
        try {
            Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (mime.equals("application/vnd.android.package-archive")) {
                a.startActivity(i);
            } else {
                a.startActivity(Intent.createChooser(i, "Open with"));
            }
        } catch (ActivityNotFoundException ex) {
            Ui.msg(a, "No app on this phone can open this file");
        } catch (Exception ex) {
            Ui.msg(a, "Couldn't open this file");
        }
    }

    public static Uri shareUri(Context c, MediaInfo m) {
        Uri u = m.u();
        if ("file".equals(u.getScheme())) return FileProvider.getUriForFile(c, c.getPackageName() + ".files", new File(u.getPath()));
        return u;
    }

    public static void share(Activity a, List<MediaInfo> items) {
        if (items.isEmpty()) return;
        try {
            Intent i;
            if (items.size() == 1) {
                MediaInfo m = items.get(0);
                if (m.isOnline()) {
                    i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, m.uri);
                } else {
                    i = new Intent(Intent.ACTION_SEND).setType(m.mime.isEmpty() ? "*/*" : m.mime).putExtra(Intent.EXTRA_STREAM, shareUri(a, m));
                }
            } else {
                ArrayList<Uri> us = new ArrayList<>();
                for (MediaInfo m : items) us.add(shareUri(a, m));
                i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, us);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, "Share"));
        } catch (Exception e) {
            Ui.msg(a, "Couldn't share this");
        }
    }

    public static void shareFile(Activity a, File f) {
        try {
            Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_SEND).setType(MediaInfo.mimeOf(f.getName())).putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, "Share"));
        } catch (Exception e) {
            Ui.msg(a, "Couldn't share this");
        }
    }

    /** Builds a MediaInfo for something opened from another app. */
    public static MediaInfo fromUri(Context c, Uri u, int kind) {
        MediaInfo m = new MediaInfo();
        m.uri = u.toString();
        m.kind = kind;
        m.folder = "Shared";
        String name = u.getLastPathSegment();
        try (android.database.Cursor q = c.getContentResolver().query(u, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) name = q.getString(0);
        } catch (Exception ignored) {}
        if (name == null) name = "File";
        m.name = name;
        int d = name.lastIndexOf('.');
        m.title = d > 0 ? name.substring(0, d) : name;
        if ("file".equals(u.getScheme())) m.path = u.getPath();
        return m;
    }
}
