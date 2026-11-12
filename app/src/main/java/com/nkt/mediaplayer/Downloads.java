package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;

/** Saves an online link (AWS/S3, any direct file link) to Download/NKT Media Player using Android's downloader. */
public final class Downloads {

    private Downloads() { }

    public static boolean isOnline(MediaInfo m) {
        if (m == null || m.uri.getScheme() == null) return false;
        String s = m.uri.getScheme();
        return ("http".equals(s) || "https".equals(s)) && !YouTube.isYouTube(m.uri.toString());
    }

    public static void start(Activity a, Uri link, String fallbackName) {
        if (Net.off()) { Ui.toast(a, R.string.net_off_toast); return; }
        try {
            String name = fileName(link, fallbackName);
            DownloadManager.Request r = new DownloadManager.Request(link)
                    .setTitle(name)
                    .setDescription(a.getString(R.string.app_name))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "NKT Media Player/" + name);
            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(r);
            Ui.toast(a, a.getString(R.string.download_started, name));
        } catch (Exception e) {
            Ui.toast(a, R.string.download_failed);
        }
    }

    /** The file name from the link (without ?query), or the title. */
    static String fileName(Uri link, String fallback) {
        String last = link.getLastPathSegment();
        String n = last == null ? "" : last.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.isEmpty() || !n.contains(".")) {
            String f = fallback == null || fallback.trim().isEmpty() ? "download" : fallback.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            n = n.isEmpty() ? f : n;
            if (!n.contains(".")) n = n + ".mp4";
        }
        if (n.length() > 100) n = n.substring(n.length() - 100);
        return n;
    }
}
