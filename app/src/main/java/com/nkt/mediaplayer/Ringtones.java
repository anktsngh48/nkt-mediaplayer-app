package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;

/** "Set as ringtone": phone ringtone, notification sound or alarm. */
public final class Ringtones {

    private Ringtones() { }

    public static void set(Activity a, MediaInfo m) {
        if (m == null) return;
        if (!"content".equals(m.uri.getScheme())) {
            Ui.toast(a, R.string.ringtone_local_only);
            return;
        }
        if (!Settings.System.canWrite(a)) {
            Dialogs.builder(a)
                    .setTitle(R.string.ringtone_perm_title)
                    .setMessage(R.string.ringtone_perm_text)
                    .setPositiveButton(R.string.allow, (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception ignored) { }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        String[] choices = {a.getString(R.string.rt_phone), a.getString(R.string.rt_notification), a.getString(R.string.rt_alarm)};
        int[] types = {RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_ALARM};
        String[] flags = {MediaStore.Audio.Media.IS_RINGTONE, MediaStore.Audio.Media.IS_NOTIFICATION, MediaStore.Audio.Media.IS_ALARM};
        Dialogs.builder(a)
                .setTitle(a.getString(R.string.set_as_ringtone_for, m.title))
                .setItems(choices, (d, w) -> {
                    try {
                        // Mark the song so the system sound pickers list it too (allowed on some phones only).
                        try {
                            ContentValues v = new ContentValues();
                            v.put(flags[w], 1);
                            a.getContentResolver().update(m.uri, v, null, null);
                        } catch (Exception ignored) { }
                        RingtoneManager.setActualDefaultRingtoneUri(a, types[w], m.uri);
                        Ui.toast(a, a.getString(R.string.ringtone_set, choices[w]));
                    } catch (Exception e) {
                        Ui.toast(a, R.string.ringtone_failed);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
