package com.nkt.mediaplayer;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Lets phones on the same Wi-Fi find a running Stream by themselves (the Listen screen), so nobody has to type the
 * address. The stream's own PIN, when set, still has to be entered.
 */
final class StreamNsd {

    static final String TYPE = "_nktstream._tcp.";
    private static NsdManager.RegistrationListener reg;

    private StreamNsd() { }

    // ── The phone that streams announces itself ──

    static synchronized void register(Context c, int port, String name) {
        unregister(c);
        try {
            NsdManager nm = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("NKT " + StreamHub.deviceName());
            info.setServiceType(TYPE);
            info.setPort(port);
            info.setAttribute("p", name);
            reg = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo i, int e) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
                @Override public void onServiceRegistered(NsdServiceInfo i) { }
                @Override public void onServiceUnregistered(NsdServiceInfo i) { }
            };
            nm.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg);
        } catch (Throwable t) {
            reg = null;
        }
    }

    static synchronized void unregister(Context c) {
        if (reg == null) return;
        try {
            ((NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE)).unregisterService(reg);
        } catch (Throwable ignored) { }
        reg = null;
    }

    // ── The phone that listens looks around ──

    interface Found {
        void found(String key, String label, String url);
        void lost(String key);
    }

    static final class Finder {
        private final Context c;
        private final Found cb;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
        private NsdManager nm;
        private NsdManager.DiscoveryListener dl;
        private boolean busy;

        Finder(Context c, Found cb) {
            this.c = c.getApplicationContext();
            this.cb = cb;
        }

        void start() {
            try {
                nm = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
                dl = new NsdManager.DiscoveryListener() {
                    @Override public void onStartDiscoveryFailed(String t, int e) { }
                    @Override public void onStopDiscoveryFailed(String t, int e) { }
                    @Override public void onDiscoveryStarted(String t) { }
                    @Override public void onDiscoveryStopped(String t) { }
                    @Override public void onServiceFound(NsdServiceInfo s) { main.post(() -> { queue.add(s); next(); }); }
                    @Override public void onServiceLost(NsdServiceInfo s) { main.post(() -> cb.lost(s.getServiceName())); }
                };
                nm.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, dl);
            } catch (Throwable t) {
                dl = null;
            }
        }

        // the system resolves one service at a time
        private void next() {
            if (busy || queue.isEmpty() || nm == null || dl == null) return;
            final NsdServiceInfo s = queue.poll();
            busy = true;
            try {
                nm.resolveService(s, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int e) { main.post(() -> { busy = false; next(); }); }
                    @Override public void onServiceResolved(NsdServiceInfo i) {
                        main.post(() -> {
                            busy = false;
                            try {
                                java.net.InetAddress a = i.getHost();
                                byte[] p = i.getAttributes().get("p");
                                if (a != null && p != null && !(a instanceof java.net.Inet6Address)) {
                                    String url = "http://" + a.getHostAddress() + ":" + i.getPort() + "/" + new String(p, StandardCharsets.UTF_8);
                                    cb.found(i.getServiceName(), i.getServiceName().replaceFirst("^NKT ", ""), url);
                                }
                            } catch (Throwable ignored) { }
                            next();
                        });
                    }
                });
            } catch (Throwable t) {
                busy = false;
            }
        }

        void stop() {
            try { if (nm != null && dl != null) nm.stopServiceDiscovery(dl); } catch (Throwable ignored) { }
            dl = null;
            queue.clear();
            busy = false;
        }
    }
}
