package com.nkt.mediaplayer;

import android.content.Context;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Other devices: saved FTP servers (passwords locked with the phone's keystore), the logins in use for
 * streaming, and connecting.
 */
public final class Remote {

    private Remote() { }

    public static final class Device {
        public String name = "", host = "", user = "", pass = "", saveTo = "";
        public int port = 21;
        public boolean guest = true, ask = false;
        public long lastUsed;
        // from Wi-Fi discovery (not saved)
        public boolean found, app, needsPassword, readWrite = true;
        public String version = "";

        public String key() { return host.toLowerCase(Locale.ROOT) + ":" + port; }

        public String label() { return name.isEmpty() ? host : name; }

        public String address() { return host + (port == 21 ? "" : ":" + port); }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name).put("host", host).put("port", port).put("user", user)
                        .put("pass", pass.isEmpty() ? "" : lock(pass)).put("guest", guest)
                        .put("saveTo", saveTo).put("ask", ask).put("last", lastUsed).put("app", app);
            } catch (Exception ignored) { }
            return o;
        }

        static Device fromJson(JSONObject o) {
            Device d = new Device();
            d.name = o.optString("name");
            d.host = o.optString("host");
            d.port = o.optInt("port", 21);
            d.user = o.optString("user");
            String p = o.optString("pass");
            d.pass = p.isEmpty() ? "" : unlock(p);
            d.guest = o.optBoolean("guest", true);
            d.saveTo = o.optString("saveTo");
            d.ask = o.optBoolean("ask", false);
            d.lastUsed = o.optLong("last");
            d.app = o.optBoolean("app");
            return d;
        }
    }

    // ── Saved devices ────────────────────────────────────────────────────────

    public static List<Device> saved(Context c) {
        List<Device> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString("remote_devices", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Device.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    private static void write(Context c, List<Device> list) {
        JSONArray a = new JSONArray();
        for (Device d : list) a.put(d.toJson());
        Prefs.sp(c).edit().putString("remote_devices", a.toString()).apply();
    }

    /** Adds or updates (same address and port). */
    public static void save(Context c, Device d) {
        List<Device> list = saved(c);
        for (int i = 0; i < list.size(); i++) if (list.get(i).key().equals(d.key())) { list.set(i, d); write(c, list); return; }
        list.add(0, d);
        write(c, list);
    }

    public static void forget(Context c, Device d) {
        List<Device> list = saved(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).key().equals(d.key())) list.remove(i);
        write(c, list);
    }

    public static Device find(Context c, String host, int port) {
        String k = host.toLowerCase(Locale.ROOT) + ":" + port;
        for (Device d : saved(c)) if (d.key().equals(k)) return d;
        return null;
    }

    // ── Logins in use (the player streams with them) ─────────────────────────

    private static final Map<String, Device> ACTIVE = new ConcurrentHashMap<>();

    public static void setActive(Device d) { ACTIVE.put(d.key(), d); }

    public static void clearActive(Device d) { ACTIVE.remove(d.key()); }

    static Device login(Context c, String host, int port) {
        Device d = ACTIVE.get(host.toLowerCase(Locale.ROOT) + ":" + port);
        if (d != null) return d;
        d = find(c, host, port);
        if (d != null) return d;
        d = new Device();
        d.host = host;
        d.port = port;
        return d;
    }

    // ── Addresses ────────────────────────────────────────────────────────────

    public static Uri uri(Device d, String path) {
        return new Uri.Builder().scheme("ftp").encodedAuthority(d.host + ":" + d.port).path(path).build();
    }

    /** host/path for showing (never the login). */
    public static String visible(Uri u) {
        if (u == null) return "";
        String host = u.getHost() == null ? "" : u.getHost();
        return host + (u.getPath() == null ? "" : u.getPath());
    }

    public static String parent(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) return "/";
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = p.lastIndexOf('/');
        return i <= 0 ? "/" : p.substring(0, i);
    }

    public static String child(String dir, String name) { return dir.endsWith("/") ? dir + name : dir + "/" + name; }

    // ── Connecting ───────────────────────────────────────────────────────────

    public static final class LoginException extends IOException {
        LoginException() { super("login"); }
    }

    /** Runs on a background thread. Logged in, binary, passive. */
    public static FTPClient connect(Device d) throws IOException {
        FTPClient f = new FTPClient();
        f.setControlEncoding("UTF-8");
        f.setAutodetectUTF8(true);
        f.setConnectTimeout(8000);
        f.setDefaultTimeout(15000);
        f.connect(d.host, d.port);
        if (!FTPReply.isPositiveCompletion(f.getReplyCode())) { quietly(f); throw new IOException("refused"); }
        f.setSoTimeout(20000);
        boolean ok = d.guest || d.user.isEmpty() ? f.login("anonymous", "guest@") : f.login(d.user, d.pass);
        if (!ok) { quietly(f); throw new LoginException(); }
        f.enterLocalPassiveMode();
        f.setFileType(FTP.BINARY_FILE_TYPE);
        f.setBufferSize(256 * 1024);
        f.setListHiddenFiles(true);
        f.setControlKeepAliveTimeout(60);
        return f;
    }

    public static void quietly(FTPClient f) {
        if (f == null) return;
        try { if (f.isConnected()) { try { f.logout(); } catch (Exception ignored) { } f.disconnect(); } } catch (Exception ignored) { }
    }

    // ── Passwords: AES in the Android keystore ───────────────────────────────

    private static final String ALIAS = "nkt_media_player_devices";

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return g.generateKey();
    }

    static String lock(String plain) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV(), data = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return "k1:" + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";          // never store it in the clear
        }
    }

    static String unlock(String locked) {
        try {
            String[] p = locked.split(":");
            if (p.length != 3 || !"k1".equals(p[0])) return "";
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p[1], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(p[2], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
