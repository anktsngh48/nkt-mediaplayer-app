package com.nkt.mediaplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Stream: keeps the web server (and the hand-over of what is playing) running in the background until STOP is pressed.
 * Leaving the Stream screen, or the app, does not stop it.
 */
public class StreamService extends Service {

    public static final String ACTION_STOP = "com.nkt.mediaplayer.STOP_STREAM";
    public static final String ACTION_FINISH = "com.nkt.mediaplayer.FINISH_STREAM";
    private static final String CHANNEL = "nkt_stream";
    private static final int NOTIF_ID = 43;

    public static volatile boolean running = false;
    /** STOP was pressed with "let them finish": the listeners play the last song out, then it ends by itself. */
    public static volatile boolean draining = false;
    public static volatile String error = null;

    private StreamServer server;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int shownListeners = -1;

    /** The address listeners type or scan, or null when the phone is not on a network. */
    public static String address(Context c) {
        String ip = FtpService.ipAddress();
        if (ip == null) return null;
        return "http://" + ip + ":" + StreamPrefs.port(c) + "/" + StreamPrefs.name(c);
    }

    public static void start(Context c) {
        Intent i = new Intent(c, StreamService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, StreamService.class).setAction(ACTION_STOP));
    }

    /** Stop streaming, but let the listeners finish the song that is playing. */
    public static void finishLast(Context c, boolean wholeQueue) {
        c.startService(new Intent(c, StreamService.class).setAction(ACTION_FINISH).putExtra("all", wholeQueue));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_FINISH.equals(intent.getAction())) {
            if (running && !draining && StreamHub.freeze(intent.getBooleanExtra("all", false))) {
                draining = true;
                shownListeners = -1;
                try { getSystemService(NotificationManager.class).notify(NOTIF_ID, build()); } catch (Throwable ignored) { }
            } else if (!draining) stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        if (server == null) startServer();
        return START_NOT_STICKY;
    }

    private Notification build() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.stream), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, StreamActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 4, new Intent(this, StreamService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String addr = address(this);
        int n = StreamHub.listenerCount();
        String text = draining ? getString(R.string.stream_draining_notif, StreamHub.listenerCount())
                : addr == null ? getString(R.string.share_no_wifi)
                : (n > 0 ? getResources().getQuantityString(R.plurals.stream_listeners, n, n) + " · " : "") + addr;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_wifi)
                .setContentTitle(getString(R.string.stream_notif_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop_caps), stop).build());
        shownListeners = n;
        return b.build();
    }

    private void goForeground() {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (draining && StreamHub.drainDone()) { stopSelf(); return; }
            if (draining || StreamHub.listenerCount() != shownListeners) {
                try { getSystemService(NotificationManager.class).notify(NOTIF_ID, build()); } catch (Throwable ignored) { }
            }
            ui.postDelayed(this, draining ? 1000 : 3000);
        }
    };

    private void startServer() {
        error = null;
        final int port = StreamPrefs.port(this);
        final String name = StreamPrefs.name(this);
        new Thread(() -> {
            try {
                StreamServer s = new StreamServer(this, port, name);
                s.start();
                server = s;
                StreamHub.begin(this);
                running = true;
                StreamNsd.register(this, port, name);
                ui.post(this::holdLocks);
                ui.post(refresh);
            } catch (Throwable t) {
                error = getString(R.string.stream_failed);
                running = false;
                ui.post(this::stopSelf);
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private void holdLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nkt:stream");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) { }
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nkt:stream");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        running = false;
        draining = false;
        ui.removeCallbacksAndMessages(null);
        final StreamServer s = server;
        server = null;
        StreamNsd.unregister(this);
        StreamHub.end();                                   // tells the listeners it is over first
        if (s != null) new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            s.stop();
        }, "nkt-stream-stop").start();
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
