package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;

import androidx.core.content.FileProvider;

import java.io.File;

/** Sends a song, video or photo to another app (WhatsApp, Drive, Bluetooth…). */
public final class Share {

    private Share() { }

    public static void send(Activity a, MediaInfo m) {
        if (m == null) return;
        try {
            Uri uri = m.uri;
            if ("file".equals(uri.getScheme())) {
                uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", new File(uri.getPath()));
            } else if (!"content".equals(uri.getScheme())) {
                // an online link: share the address itself
                Intent t = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, uri.toString());
                a.startActivity(Intent.createChooser(t, a.getString(R.string.share)));
                return;
            }
            String type = m.image ? "image/*" : m.video ? "video/*" : "audio/*";
            Intent i = new Intent(Intent.ACTION_SEND)
                    .setType(type)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, a.getString(R.string.share)));
        } catch (Exception e) {
            Ui.toast(a, R.string.cant_share);
        }
    }

    /** Several files at once. */
    public static void sendMany(Activity a, List<MediaInfo> items) {
        if (items == null || items.isEmpty()) return;
        if (items.size() == 1) { send(a, items.get(0)); return; }
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            boolean allAudio = true, allVideo = true, allImage = true;
            for (MediaInfo m : items) {
                Uri u = m.uri;
                if ("file".equals(u.getScheme())) u = FileProvider.getUriForFile(a, a.getPackageName() + ".files", new File(u.getPath()));
                else if (!"content".equals(u.getScheme())) continue;
                uris.add(u);
                allAudio &= !m.video && !m.image;
                allVideo &= m.video;
                allImage &= m.image;
            }
            if (uris.isEmpty()) { Ui.toast(a, R.string.cant_share); return; }
            String type = allImage ? "image/*" : allVideo ? "video/*" : allAudio ? "audio/*" : "*/*";
            Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType(type)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, a.getString(R.string.share)));
        } catch (Exception e) {
            Ui.toast(a, R.string.cant_share);
        }
    }
}
