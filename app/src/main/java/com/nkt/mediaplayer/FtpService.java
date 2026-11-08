package com.nkt.mediaplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;

import org.apache.ftpserver.ConnectionConfigFactory;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.FtpletResult;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.TransferRatePermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** NKT Share: an FTP server on port 2121 that runs until the user presses STOP. */
public class FtpService extends Service {
    public static final int PORT = 2121;
    public static volatile boolean running;
    public static volatile String error;
    public static final AtomicInteger devices = new AtomicInteger();
    private static final String CH = "nkt_share";
    private FtpServer server;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;

    public static void start(Context c) {
        Intent i = new Intent(c, FtpService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void stop(Context c) { c.stopService(new Intent(c, FtpService.class)); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        String ip = ip();
        Notification n = notification(ip == null ? "Starting…" : "ftp://" + ip + ":" + PORT);
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(7, n);
        if (server == null) {
            new Thread(this::startServer).start();
        }
        return START_NOT_STICKY;
    }

    private void startServer() {
        try {
            error = null;
            FtpServerFactory sf = new FtpServerFactory();
            ListenerFactory lf = new ListenerFactory();
            lf.setPort(PORT);
            lf.setIdleTimeout(0);
            DataConnectionConfigurationFactory dc = new DataConnectionConfigurationFactory();
            dc.setPassivePorts("2122-2140");
            lf.setDataConnectionConfiguration(dc.createDataConnectionConfiguration());
            sf.addListener("default", lf.createListener());
            boolean pw = Store.bool(this, "share_pw_on", false);
            ConnectionConfigFactory cc = new ConnectionConfigFactory();
            cc.setAnonymousLoginEnabled(!pw);
            cc.setMaxLogins(30);
            cc.setMaxAnonymousLogins(30);
            cc.setMaxLoginFailures(10);
            sf.setConnectionConfig(cc.createConnectionConfig());
            UserManager um = new PropertiesUserManagerFactory().createUserManager();
            String home = Store.str(this, "share_root", Environment.getExternalStorageDirectory().getAbsolutePath());
            boolean rw = Store.bool(this, "share_rw", true);
            List<Authority> auth = new ArrayList<>();
            if (rw) auth.add(new WritePermission());
            auth.add(new ConcurrentLoginPermission(30, 30));
            auth.add(new TransferRatePermission(0, 0));
            BaseUser u = new BaseUser();
            if (pw) {
                u.setName("nkt");
                u.setPassword(password(this));
            } else {
                u.setName("anonymous");
            }
            u.setHomeDirectory(home);
            u.setAuthorities(auth);
            u.setMaxIdleTime(0);
            um.save(u);
            sf.setUserManager(um);
            Map<String, org.apache.ftpserver.ftplet.Ftplet> ftplets = new HashMap<>();
            ftplets.put("count", new DefaultFtplet() {
                @Override public FtpletResult onConnect(FtpSession s) { devices.incrementAndGet(); return FtpletResult.DEFAULT; }
                @Override public FtpletResult onDisconnect(FtpSession s) { devices.decrementAndGet(); return FtpletResult.DEFAULT; }
            });
            sf.setFtplets(ftplets);
            server = sf.createServer();
            server.start();
            running = true;
            devices.set(0);
            locks(true);
            announce(true);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(7, notification("ftp://" + ip() + ":" + PORT));
        } catch (Throwable t) {
            error = "Couldn't start sharing. Try again.";
            running = false;
            server = null;
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        announce(false);
        locks(false);
        final FtpServer s = server;
        server = null;
        if (s != null) new Thread(() -> { try { s.stop(); } catch (Throwable ignored) {} }).start();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void locks(boolean on) {
        try {
            if (on) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nkt:share");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nkt:share");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            } else {
                if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            }
        } catch (Throwable ignored) {}
    }

    /** Lets other NKT MP phones (and FTP apps that browse the network) find this phone. */
    private void announce(boolean on) {
        try {
            if (on) {
                nsd = (NsdManager) getSystemService(NSD_SERVICE);
                NsdServiceInfo si = new NsdServiceInfo();
                si.setServiceName("NKT MP · " + Build.MODEL);
                si.setServiceType("_ftp._tcp.");
                si.setPort(PORT);
                si.setAttribute("app", "nktmp");
                si.setAttribute("pw", Store.bool(this, "share_pw_on", false) ? "1" : "0");
                nsdListener = new NsdManager.RegistrationListener() {
                    public void onRegistrationFailed(NsdServiceInfo s, int e) {}
                    public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
                    public void onServiceRegistered(NsdServiceInfo s) {}
                    public void onServiceUnregistered(NsdServiceInfo s) {}
                };
                nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, nsdListener);
            } else if (nsd != null && nsdListener != null) {
                nsd.unregisterService(nsdListener);
                nsdListener = null;
            }
        } catch (Throwable ignored) {}
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "NKT Share", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, NktShareActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, FtpService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat_nkt)
                .setContentTitle("NKT Share is on")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "STOP", stop).build())
                .build();
    }

    public static String password(Context c) {
        String p = Store.str(c, "share_pw", null);
        if (p == null) p = newPassword(c);
        return p;
    }

    public static String newPassword(Context c) {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        Store.put(c, "share_pw", sb.toString());
        return sb.toString();
    }

    /** The phone's Wi-Fi or hotspot address. */
    public static String ip() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth")) return a.getHostAddress();
                    if (!n.startsWith("rmnet") && !n.startsWith("ccmni") && fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }
}
