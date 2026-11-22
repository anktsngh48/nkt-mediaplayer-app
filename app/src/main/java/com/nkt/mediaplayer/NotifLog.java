package com.nkt.mediaplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Notifications Log: the settings, and one log file for each app, kept in this app's own storage
 * (files/notif_log/&lt;package&gt;.log, one notification a line: time, chat, sender, text).
 */
final class NotifLog {
    private NotifLog() { }

    /** One logged notification. chat is the group it came in ("" for a person's own chat or an ordinary notice). */
    static final class Entry {
        final long time;
        final String chat, sender, text;
        Entry(long time, String chat, String sender, String text) {
            this.time = time; this.chat = chat == null ? "" : chat; this.sender = sender == null ? "" : sender; this.text = text == null ? "" : text;
        }
    }

    /** An app that has a log. */
    static final class AppLog {
        String pkg, label;
        int count;
        long last, first;
    }

    // ── Settings ──

    private static SharedPreferences sp(Context c) { return Prefs.sp(c); }
    static boolean on(Context c) { return sp(c).getBoolean("notif_log_on", false); }
    static void setOn(Context c, boolean v) { sp(c).edit().putBoolean("notif_log_on", v).apply(); }
    /** All apps (also the ones installed later), or only the ticked ones. */
    static boolean all(Context c) { return sp(c).getBoolean("notif_log_all", true); }
    static Set<String> apps(Context c) { return new HashSet<>(sp(c).getStringSet("notif_log_apps", new HashSet<>())); }
    static void setApps(Context c, boolean all, Set<String> apps) {
        sp(c).edit().putBoolean("notif_log_all", all).putStringSet("notif_log_apps", new HashSet<>(apps)).apply();
    }
    static boolean wants(Context c, String pkg) { return on(c) && (all(c) || apps(c).contains(pkg)); }

    /** Android's "Notification access" is switched on for this app. */
    static boolean hasAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (s == null) return false;
        String me = new ComponentName(c, NotifListener.class).flattenToString(), mine = c.getPackageName() + "/";
        for (String part : s.split(":")) if (part.equals(me) || part.startsWith(mine)) return true;
        return false;
    }

    /** Android's page where Notification access is switched on (straight to this app where Android allows it). */
    static Intent accessIntent(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(c, NotifListener.class).flattenToString());
            if (i.resolveActivity(c.getPackageManager()) != null) return i;
        }
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }

    /** The line under "Notifications Log" in the menu; on = shown in green. */
    static String status(Context c, boolean[] on) {
        on[0] = false;
        if (!on(c)) return c.getString(R.string.nlog_off);
        if (!hasAccess(c)) return c.getString(R.string.nlog_needs_access);
        on[0] = true;
        if (all(c)) return c.getString(R.string.nlog_logging_all);
        int n = apps(c).size();
        return c.getResources().getQuantityString(R.plurals.nlog_logging_n, n, n);
    }

    // ── Files ──

    private static final ExecutorService writer = Executors.newSingleThreadExecutor();

    static File dir(Context c) { return new File(c.getFilesDir(), "notif_log"); }
    private static File logFile(Context c, String pkg) { return new File(dir(c), pkg + ".log"); }
    private static File nameFile(Context c, String pkg) { return new File(dir(c), pkg + ".name"); }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "").replace("\n", "\\n");
    }

    private static String unesc(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else b.append(ch);
        }
        return b.toString();
    }

    static String line(Entry e) { return e.time + "\t" + esc(e.chat) + "\t" + esc(e.sender) + "\t" + esc(e.text) + "\n"; }

    static Entry parse(String l) {
        String[] p = l.split("\t", -1);
        if (p.length < 4) return null;
        try { return new Entry(Long.parseLong(p[0]), unesc(p[1]), unesc(p[2]), unesc(p[3])); } catch (Exception e) { return null; }
    }

    /** Adds notifications to an app's log, in the background. */
    static void add(Context c, String pkg, String label, List<Entry> entries) {
        if (entries.isEmpty()) return;
        final Context app = c.getApplicationContext();
        writer.execute(() -> {
            try {
                File d = dir(app);
                if (!d.isDirectory()) d.mkdirs();
                try (Writer w = new OutputStreamWriter(new FileOutputStream(logFile(app, pkg), true), StandardCharsets.UTF_8)) {
                    for (Entry e : entries) w.write(line(e));
                }
                File nf = nameFile(app, pkg);
                if (label != null && !label.isEmpty() && !nf.exists()) {
                    try (Writer w = new OutputStreamWriter(new FileOutputStream(nf), StandardCharsets.UTF_8)) { w.write(label); }
                }
            } catch (Throwable ignored) { }
        });
    }

    /** The app's name: from the phone, or as it was when it was logged (the app may be gone). */
    static String label(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable ignored) { }
        File nf = nameFile(c, pkg);
        if (nf.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(nf), StandardCharsets.UTF_8))) {
                String s = r.readLine();
                if (s != null && !s.isEmpty()) return s;
            } catch (Throwable ignored) { }
        }
        return pkg;
    }

    /** Every app that has a log, newest first. Not on the main thread. */
    static List<AppLog> list(Context c) {
        List<AppLog> out = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            String n = f.getName();
            if (!n.endsWith(".log") || f.length() == 0) continue;
            AppLog a = new AppLog();
            a.pkg = n.substring(0, n.length() - 4);
            a.label = label(c, a.pkg);
            int count = 0;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int k;
                while ((k = in.read(buf)) > 0) for (int i = 0; i < k; i++) if (buf[i] == '\n') count++;
            } catch (Throwable ignored) { }
            a.count = count;
            Entry last = lastEntry(f);
            a.last = last == null ? f.lastModified() : last.time;
            out.add(a);
        }
        Collections.sort(out, (x, y) -> Long.compare(y.last, x.last));
        return out;
    }

    private static Entry lastEntry(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            int back = (int) Math.min(len, 16 * 1024);
            byte[] b = new byte[back];
            r.seek(len - back);
            r.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            String[] ls = s.split("\n");
            for (int i = ls.length - 1; i >= 0; i--) { Entry e = parse(ls[i]); if (e != null) return e; }
        } catch (Throwable ignored) { }
        return null;
    }

    /** The whole log of an app, oldest first. Not on the main thread. */
    static List<Entry> read(Context c, String pkg) {
        List<Entry> out = new ArrayList<>();
        File f = logFile(c, pkg);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) { Entry e = parse(l); if (e != null) out.add(e); }
        } catch (Throwable ignored) { }
        Collections.sort(out, (x, y) -> Long.compare(x.time, y.time));
        return out;
    }

    static void delete(Context c, String pkg) {
        writer.execute(() -> { logFile(c, pkg).delete(); nameFile(c, pkg).delete(); });
    }

    // ── As text ──

    static String dayLine(long t) { return "── " + new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(new Date(t)) + " ──"; }
    static String timeOf(long t) { return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(t)); }

    /** "10:42  Family Group › Mummy: Lunch at 1?" */
    static String textLine(Entry e) {
        StringBuilder b = new StringBuilder(timeOf(e.time)).append("  ");
        if (!e.chat.isEmpty()) b.append(e.chat).append(" › ");
        if (!e.sender.isEmpty()) b.append(e.sender).append(": ");
        b.append(e.text);
        return b.toString();
    }

    /** The entries as a text file, newest first, a date line before each day. */
    static String asText(String title, List<Entry> newestFirst) {
        StringBuilder b = new StringBuilder();
        b.append(title).append('\n');
        b.append(new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date())).append(" · ").append(newestFirst.size()).append('\n');
        String day = null;
        for (Entry e : newestFirst) {
            String d = dayLine(e.time);
            if (!d.equals(day)) { b.append('\n').append(d).append('\n'); day = d; }
            b.append(textLine(e)).append('\n');
        }
        return b.toString();
    }
}
