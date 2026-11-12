package com.nkt.mediaplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.session.MediaController;

import com.google.common.util.concurrent.ListenableFuture;

/** Previous / play-pause / next from the home-screen widgets. */
public class WidgetReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent in) {
        final String action = in.getAction();
        if (action == null) return;
        PlaybackService svc = PlaybackService.instance;
        if (svc != null) { svc.widgetCommand(action); return; }
        // the player is not running (just after the phone started): start it, then do the same
        final PendingResult pending = goAsync();
        final Context app = c.getApplicationContext();
        final ListenableFuture<MediaController> f = Media.connect(app);
        f.addListener(() -> {
            try {
                MediaController mc = f.get();
                if (WidgetUpdater.ACTION_PLAY.equals(action)) Media.play(mc);
                else if (WidgetUpdater.ACTION_NEXT.equals(action)) mc.seekToNext();
                else if (WidgetUpdater.ACTION_PREV.equals(action)) mc.seekToPrevious();
            } catch (Exception ignored) {
            } finally {
                MediaController.releaseFuture(f);
                pending.finish();
            }
        }, new Handler(Looper.getMainLooper())::post);
    }
}
