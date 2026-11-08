package com.nkt.mediaplayer;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * NKT Share in the quick settings panel (pull down the status bar), like the torch:
 * tap to START sharing, tap again to STOP. The tile is lit while sharing and shows the address.
 */
public class NktShareTile extends TileService {

    /** Called by the share service whenever it starts or stops. */
    static void refresh(Context c) {
        try { TileService.requestListeningState(c, new ComponentName(c, NktShareTile.class)); } catch (Throwable ignored) { }
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        show();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (FtpService.running) {
            FtpService.stop(this);
            later();
            return;
        }
        // sharing needs "All files access" (Android 11+); the first time, the NKT Share screen asks for it
        if (!HiddenScanner.hasAllFilesAccess(this)) { openShareScreen(); return; }
        // no Wi-Fi: the NKT Share screen says so
        if (FtpService.ipAddress() == null) { openShareScreen(); return; }
        try {
            FtpService.start(this);
            later();
        } catch (Throwable t) {
            openShareScreen();      // Android refused to start it from here: open the screen instead
        }
    }

    private void later() {
        Tile t = getQsTile();
        if (t != null) {
            t.setState(Tile.STATE_UNAVAILABLE);              // greyed while starting / stopping
            t.updateTile();
        }
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(this::show, 700);
        h.postDelayed(this::show, 2000);
    }

    private void show() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = FtpService.running;
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_pc));
        t.setLabel(getString(R.string.nkt_share));
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            String ip = on ? FtpService.ipAddress() : null;
            t.setSubtitle(on ? (ip == null ? getString(R.string.tile_on) : ip + ":" + FtpService.PORT) : getString(R.string.tile_off));
        }
        t.setContentDescription(getString(on ? R.string.tile_on_desc : R.string.tile_off_desc));
        t.updateTile();
    }

    @SuppressWarnings("deprecation")
    private void openShareScreen() {
        Intent i = new Intent(this, NktShareActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
