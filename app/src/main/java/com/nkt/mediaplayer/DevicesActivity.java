package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Finds phones running NKT Share (and other FTP servers) on the same Wi-Fi, and saved devices. */
public class DevicesActivity extends Activity {
    private LinearLayout col, foundBox, savedBox;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener disc;
    private WifiManager.MulticastLock mlock;
    private final List<String[]> found = new ArrayList<>(); // name, host, port
    private final Deque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        col = Ui.col(this);
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 24));
        sv.addView(col);
        setContentView(sv);
        Ui.edgeToEdge(this, sv);
        LinearLayout top = Ui.row(this);
        ImageButton back = Ui.roundBtn(this, "back", 44, Ui.TEXT, true);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = Ui.label(this, "Other devices", 13, Ui.TEXT, 0.2f);
        t.setPadding(Ui.dp(this, 12), 0, 0, 0);
        top.addView(t, Ui.lpw(0, Ui.WRAP, 1));
        ImageButton add = Ui.roundBtn(this, "plus", 44, Ui.ACCENT, true);
        add.setOnClickListener(v -> addDialog(null));
        top.addView(add);
        col.addView(top);
        col.addView(Ui.section(this, "Found on this Wi-Fi"));
        foundBox = Ui.col(this);
        col.addView(foundBox);
        col.addView(Ui.section(this, "Saved"));
        savedBox = Ui.col(this);
        col.addView(savedBox);
        TextView note = Ui.text(this, "Other phones show up here when NKT Share is on. Any FTP server on your Wi-Fi works too — add it with ＋.", 12, Ui.MUTED);
        note.setPadding(Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 4), 0);
        col.addView(note);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showSaved();
        showFound();
        startDiscovery();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopDiscovery();
    }

    private void showFound() {
        foundBox.removeAllViews();
        if (found.isEmpty()) {
            TextView t = Ui.text(this, "Looking…", 13, Ui.SUCCESS);
            t.setPadding(Ui.dp(this, 4), 0, 0, 0);
            foundBox.addView(t);
        }
        for (String[] d : found) {
            LinearLayout r = Ui.infoRow(this, d[0], d[1] + ":" + d[2], Ui.text(this, "›", 22, Ui.MUTED));
            r.setOnClickListener(v -> {
                JSONObject o = new JSONObject();
                try { o.put("name", d[0]).put("host", d[1]).put("port", Integer.parseInt(d[2])).put("guest", true); } catch (Exception ignored) {}
                connect(o, true);
            });
            foundBox.addView(r);
        }
    }

    private void showSaved() {
        savedBox.removeAllViews();
        JSONArray a = Store.devices(this);
        if (a.length() == 0) {
            TextView t = Ui.text(this, "Nothing saved yet", 13, Ui.MUTED);
            t.setPadding(Ui.dp(this, 4), 0, 0, 0);
            savedBox.addView(t);
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            final int idx = i;
            ImageButton more = Ui.iconBtn(this, "more", 44, Ui.MUTED);
            more.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(o.optString("name"))
                    .setItems(new String[]{"Connect", "Edit", "Remove"}, (d, w) -> {
                        if (w == 0) connect(o, false);
                        else if (w == 1) addDialog(o);
                        else { JSONArray b = Store.devices(this); b.remove(idx); Store.saveDevices(this, b); showSaved(); }
                    }).show());
            LinearLayout r = Ui.infoRow(this, o.optString("name"), o.optString("host") + ":" + o.optInt("port", 21), more);
            r.setOnClickListener(v -> connect(o, false));
            savedBox.addView(r);
        }
    }

    private void connect(JSONObject o, boolean fromDiscovery) {
        Intent i = new Intent(this, RemoteActivity.class).putExtra("device", o.toString());
        if (fromDiscovery) i.putExtra("askLogin", true);
        startActivity(i);
    }

    private void addDialog(JSONObject edit) {
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        EditText host = field("Address (e.g. 192.168.1.57)", edit == null ? "" : edit.optString("host"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = field("Port", edit == null ? "2121" : String.valueOf(edit.optInt("port", 21)), InputType.TYPE_CLASS_NUMBER);
        Switch guest = Ui.toggle(this, edit == null || edit.optBoolean("guest", true));
        EditText user = field("User name", edit == null ? "nkt" : edit.optString("user", "nkt"), InputType.TYPE_CLASS_TEXT);
        EditText pass = field("Password", edit == null ? "" : edit.optString("pass"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText name = field("Name (optional)", edit == null ? "" : edit.optString("name"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(host);
        box.addView(port);
        LinearLayout gr = Ui.row(this);
        gr.addView(Ui.text(this, "Log in as guest (no password)", 14, Ui.TEXT), Ui.lpw(0, Ui.WRAP, 1));
        gr.addView(guest);
        box.addView(gr, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 8));
        box.addView(user);
        box.addView(pass);
        box.addView(name);
        guest.setOnCheckedChangeListener((x, on) -> { user.setVisibility(on ? View.GONE : View.VISIBLE); pass.setVisibility(on ? View.GONE : View.VISIBLE); });
        user.setVisibility(guest.isChecked() ? View.GONE : View.VISIBLE);
        pass.setVisibility(guest.isChecked() ? View.GONE : View.VISIBLE);
        new AlertDialog.Builder(this).setTitle(edit == null ? "Add a device" : "Edit device").setView(box)
                .setPositiveButton("Connect & save", (d, w) -> {
                    String h = host.getText().toString().trim().replace("ftp://", "");
                    if (h.isEmpty()) return;
                    int pt = 21;
                    if (h.contains(":")) { try { pt = Integer.parseInt(h.substring(h.indexOf(':') + 1)); } catch (Exception ignored) {} h = h.substring(0, h.indexOf(':')); }
                    else { try { pt = Integer.parseInt(port.getText().toString()); } catch (Exception ignored) {} }
                    JSONObject o = new JSONObject();
                    try {
                        o.put("host", h).put("port", pt).put("guest", guest.isChecked()).put("user", user.getText().toString().trim())
                                .put("pass", pass.getText().toString()).put("name", name.getText().toString().trim().isEmpty() ? h : name.getText().toString().trim());
                    } catch (Exception ignored) {}
                    JSONArray a = Store.devices(this);
                    if (edit != null) {
                        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optString("host").equals(edit.optString("host")) && a.optJSONObject(i).optInt("port") == edit.optInt("port")) { a.remove(i); break; }
                    }
                    a.put(o);
                    Store.saveDevices(this, a);
                    showSaved();
                    connect(o, false);
                }).setNegativeButton("Cancel", null).show();
    }

    private EditText field(String hint, String val, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(val);
        e.setInputType(type);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        e.setSingleLine(true);
        return e;
    }

    // ---------------- discovery ----------------
    private void startDiscovery() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            mlock = wm.createMulticastLock("nkt:discover");
            mlock.setReferenceCounted(false);
            mlock.acquire();
            nsd = (NsdManager) getSystemService(NSD_SERVICE);
            disc = new NsdManager.DiscoveryListener() {
                public void onStartDiscoveryFailed(String t, int e) {}
                public void onStopDiscoveryFailed(String t, int e) {}
                public void onDiscoveryStarted(String t) {}
                public void onDiscoveryStopped(String t) {}
                public void onServiceFound(NsdServiceInfo s) { synchronized (toResolve) { toResolve.add(s); } resolveNext(); }
                public void onServiceLost(NsdServiceInfo s) {
                    runOnUiThread(() -> { found.removeIf(d -> d[0].equals(s.getServiceName())); showFound(); });
                }
            };
            nsd.discoverServices("_ftp._tcp.", NsdManager.PROTOCOL_DNS_SD, disc);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        NsdServiceInfo s;
        synchronized (toResolve) {
            if (resolving || toResolve.isEmpty()) return;
            resolving = true;
            s = toResolve.poll();
        }
        try {
            nsd.resolveService(s, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo si, int e) { resolving = false; resolveNext(); }
                public void onServiceResolved(NsdServiceInfo si) {
                    resolving = false;
                    String host = si.getHost() == null ? null : si.getHost().getHostAddress();
                    String me = FtpService.ip();
                    if (host != null && !host.equals(me) && !host.contains(":")) {
                        runOnUiThread(() -> {
                            found.removeIf(d -> d[0].equals(si.getServiceName()));
                            found.add(new String[]{si.getServiceName(), host, String.valueOf(si.getPort())});
                            showFound();
                        });
                    }
                    resolveNext();
                }
            });
        } catch (Throwable t) { resolving = false; }
    }

    private void stopDiscovery() {
        try { if (nsd != null && disc != null) nsd.stopServiceDiscovery(disc); } catch (Throwable ignored) {}
        disc = null;
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Throwable ignored) {}
    }
}
