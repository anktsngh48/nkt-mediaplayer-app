package com.nkt.mediaplayer;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notifications Log: Android hands every notification to this service (once "Notification access" is on).
 * Those of the chosen apps are written to their logs exactly as they are sent: every notification and every
 * update, with all the messages it carries (a chat's earlier messages appear again each time the app repeats them).
 */
public class NotifListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try { take(sbn); } catch (Throwable ignored) { }
    }

    private void take(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || !NotifLog.wants(this, pkg)) return;
        Notification n = sbn.getNotification();
        if (n == null) return;

        List<NotifLog.Entry> out = new ArrayList<>();
        NotificationCompat.MessagingStyle ms = null;
        try { ms = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n); } catch (Throwable ignored) { }
        if (ms != null && !ms.getMessages().isEmpty()) {
            CharSequence ct = ms.getConversationTitle();
            String chat = ct == null ? "" : ct.toString().replaceAll("\\s*\\(\\d+\\s+\\S+\\)$", "").trim();   // "Family Group (3 messages)"
            Person me = ms.getUser();
            String self = me == null || me.getName() == null ? getString(R.string.nlog_you) : me.getName().toString();
            for (NotificationCompat.MessagingStyle.Message m : ms.getMessages()) {
                CharSequence text = m.getText();
                if (text == null) continue;
                Person p = m.getPerson();
                String sender = p == null || p.getName() == null ? self : p.getName().toString();
                if (chat.equals(sender)) chat = "";             // a person's own chat: just the person
                long t = m.getTimestamp() > 0 ? m.getTimestamp() : sbn.getPostTime();
                out.add(new NotifLog.Entry(t, ms.isGroupConversation() ? chat : "", sender, text.toString()));
            }
        } else {
            Bundle x = n.extras;
            String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
            String text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.isEmpty()) text = str(x.getCharSequence(Notification.EXTRA_TEXT));
            if (text.isEmpty()) {
                CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null) {
                    StringBuilder b = new StringBuilder();
                    for (CharSequence l : lines) { if (l == null) continue; if (b.length() > 0) b.append('\n'); b.append(l); }
                    text = b.toString();
                }
            }
            if (title.isEmpty() && text.isEmpty()) return;
            out.add(new NotifLog.Entry(sbn.getPostTime(), "", title, text));
        }
        if (!out.isEmpty()) NotifLog.add(this, pkg, label(pkg), out);
    }

    private static String str(CharSequence s) { return s == null ? "" : s.toString().trim(); }

    private final Map<String, String> labels = new HashMap<>();

    private String label(String pkg) {
        String l = labels.get(pkg);
        if (l != null) return l;
        try {
            PackageManager pm = getPackageManager();
            l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) { l = pkg; }
        labels.put(pkg, l);
        return l;
    }
}
