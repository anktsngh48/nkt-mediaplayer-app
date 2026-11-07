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
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.apache.ftpserver.ConnectionConfigFactory;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory;
import org.apache.ftpserver.ftplet.Authentication;
import org.apache.ftpserver.ftplet.AuthenticationFailedException;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.FtpletResult;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.AnonymousAuthentication;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.TransferRatePermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NKT Share: an FTP server so a computer (or any device) on the same Wi-Fi can read and write the phone's files.
 * Runs until you press Stop — there is no automatic time-out.
 */
public class FtpService extends Service {

    public static final int PORT = 2121;
    public static final String ACTION_STOP = "com.nkt.mediaplayer.STOP_SHARE";
    private static final String CHANNEL = "nkt_share";
    private static final int NOTIF_ID = 42;

    // ── State the screen reads ──
    public static volatile boolean running = false;
    public static volatile String error = null;
    public static final AtomicInteger connected = new AtomicInteger(0);

    // ── Settings ──
    public static boolean passwordOn(Context c) { return Prefs.sp(c).getBoolean("share_pw_on", false); }
    public static void setPasswordOn(Context c, boolean v) { Prefs.sp(c).edit().putBoolean("share_pw_on", v).apply(); }
    public static String user() { return "nkt"; }

    public static String password(Context c) {
        String p = Prefs.sp(c).getString("share_pw", null);
        if (p == null) p = newPassword(c);
        return p;
    }

    public static String newPassword(Context c) {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++) b.append(chars.charAt(r.nextInt(chars.length())));
        Prefs.sp(c).edit().putString("share_pw", b.toString()).apply();
        return b.toString();
    }

    @SuppressWarnings("deprecation")
    public static String root(Context c) {
        String def = Environment.getExternalStorageDirectory().getAbsolutePath();
        String r = Prefs.sp(c).getString("share_root", def);
        return new File(r).isDirectory() ? r : def;
    }

    public static void setRoot(Context c, String path) { Prefs.sp(c).edit().putString("share_root", path).apply(); }

    /** The phone's address on Wi-Fi (or its own hotspot), or null if not on a network. */
    public static String ipAddress() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(java.util.Locale.ROOT);
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth")) return a.getHostAddress();
                    if (fallback == null && !n.startsWith("rmnet") && !n.startsWith("ccmni")) fallback = a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, FtpService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, FtpService.class).setAction(ACTION_STOP));
    }

    // ── Service ──────────────────────────────────────────────────────────────

    private FtpServer server;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        if (server == null) startServer();
        return START_NOT_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.nkt_share), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, NktShareActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, FtpService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String ip = ipAddress();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_wifi)
                .setContentTitle(getString(R.string.share_notif_title))
                .setContentText(ip == null ? getString(R.string.share_no_wifi) : "ftp://" + ip + ":" + PORT)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop_caps), stop).build());
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
    }

    @SuppressWarnings("deprecation")
    private void startServer() {
        error = null;
        connected.set(0);
        final String home = root(this);
        final boolean pwOn = passwordOn(this);
        final String pw = password(this);
        new Thread(() -> {
            try {
                FtpServerFactory sf = new FtpServerFactory();

                ListenerFactory lf = new ListenerFactory();
                lf.setPort(PORT);
                lf.setIdleTimeout(0);                       // never drop a connection for being idle
                DataConnectionConfigurationFactory dc = new DataConnectionConfigurationFactory();
                dc.setPassivePorts("2122-2140");
                lf.setDataConnectionConfiguration(dc.createDataConnectionConfiguration());
                sf.addListener("default", lf.createListener());

                ConnectionConfigFactory cc = new ConnectionConfigFactory();
                cc.setAnonymousLoginEnabled(!pwOn);
                cc.setMaxLogins(30);
                cc.setMaxAnonymousLogins(30);
                cc.setMaxLoginFailures(10);
                sf.setConnectionConfig(cc.createConnectionConfig());

                NativeFileSystemFactory fs = new NativeFileSystemFactory();
                fs.setCreateHome(true);
                sf.setFileSystem(fs);
                sf.setUserManager(new SimpleUsers(home, pwOn, pw));

                sf.getFtplets().put("count", new DefaultFtplet() {
                    @Override public FtpletResult onConnect(FtpSession s) { connected.incrementAndGet(); return FtpletResult.DEFAULT; }
                    @Override public FtpletResult onDisconnect(FtpSession s) {
                        if (connected.decrementAndGet() < 0) connected.set(0);
                        return FtpletResult.DEFAULT;
                    }
                });

                FtpServer s = sf.createServer();
                s.start();
                server = s;
                running = true;
                ui.post(this::holdLocks);
            } catch (Throwable t) {
                error = getString(R.string.share_failed);
                running = false;
                ui.post(this::stopSelf);
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private void holdLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nkt:share");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) { }
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nkt:share");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();                              // released when sharing is stopped
        } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        running = false;
        connected.set(0);
        final FtpServer s = server;
        server = null;
        if (s != null) new Thread(() -> { try { s.stop(); } catch (Throwable ignored) { } }).start();
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    // ── Users: anyone (no password) or nkt + password. Always read & write. ──

    private static final class SimpleUsers implements UserManager {
        private final String home, pw;
        private final boolean pwOn;

        SimpleUsers(String home, boolean pwOn, String pw) { this.home = home; this.pwOn = pwOn; this.pw = pw; }

        private User make(String name) {
            BaseUser u = new BaseUser();
            u.setName(name);
            u.setHomeDirectory(home);
            u.setEnabled(true);
            u.setMaxIdleTime(0);
            List<Authority> a = new ArrayList<>();
            a.add(new WritePermission());
            a.add(new ConcurrentLoginPermission(30, 30));
            a.add(new TransferRatePermission(0, 0));
            u.setAuthorities(a);
            return u;
        }

        @Override public User authenticate(Authentication auth) throws AuthenticationFailedException {
            if (auth instanceof UsernamePasswordAuthentication) {
                UsernamePasswordAuthentication up = (UsernamePasswordAuthentication) auth;
                if (!pwOn) return make(up.getUsername() == null ? "anonymous" : up.getUsername());   // any name works
                if (user().equals(up.getUsername()) && pw.equals(up.getPassword())) return make(user());
            } else if (auth instanceof AnonymousAuthentication && !pwOn) {
                return make("anonymous");
            }
            throw new AuthenticationFailedException("Wrong user name or password");
        }

        @Override public User getUserByName(String name) { return !pwOn || user().equals(name) ? make(name) : null; }
        @Override public String[] getAllUserNames() { return new String[]{pwOn ? user() : "anonymous"}; }
        @Override public void delete(String name) { }
        @Override public void save(User user) { }
        @Override public boolean doesExist(String name) { return !pwOn || user().equals(name); }
        @Override public String getAdminName() { return user(); }
        @Override public boolean isAdmin(String name) { return false; }
    }
}
