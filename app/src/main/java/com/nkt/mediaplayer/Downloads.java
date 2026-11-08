package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;

/** Downloads a link with Android's download manager into Download/NKT MP. */
public final class Downloads {
    private Downloads() {}

    public static void get(Activity a, String url, String title) {
        try {
            String name = Uri.parse(url).getLastPathSegment();
            if (name == null || name.isEmpty()) name = title;
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (name.length() > 100) name = name.substring(name.length() - 100);
            if (!name.contains(".")) name = name + ".mp4";
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "NKT MP/" + name);
            ((DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Ui.msg(a, "Downloading " + name + " — see the notification");
        } catch (Exception e) {
            Ui.msg(a, "Couldn't start the download");
        }
    }
}
