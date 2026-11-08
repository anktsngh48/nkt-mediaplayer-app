package com.nkt.mediaplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

public final class Perms {
    private Perms() {}

    public static final int REQ_MEDIA = 11, REQ_WRITE = 12;

    static boolean has(Context c, String p) {
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean media(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            return has(c, Manifest.permission.READ_MEDIA_AUDIO) || has(c, Manifest.permission.READ_MEDIA_VIDEO)
                    || has(c, Manifest.permission.READ_MEDIA_IMAGES);
        }
        return has(c, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    public static void askMedia(Activity a) {
        List<String> l = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(Manifest.permission.READ_MEDIA_AUDIO);
            l.add(Manifest.permission.READ_MEDIA_VIDEO);
            l.add(Manifest.permission.READ_MEDIA_IMAGES);
            l.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            l.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= 29) l.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        a.requestPermissions(l.toArray(new String[0]), REQ_MEDIA);
    }

    /** Full access to every file (needed to browse all folders, hidden files and NKT Share). */
    public static boolean allFiles(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return has(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) && has(c, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    public static void askAllFiles(Activity a, String why) {
        new AlertDialog.Builder(a)
                .setTitle("Allow access to all files")
                .setMessage(why + "\n\nOn the next screen, turn on access for NKT MP, then come back.")
                .setPositiveButton("Allow", (d, w) -> openAllFiles(a))
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static void openAllFiles(Activity a) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            a.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
        }
    }
}
