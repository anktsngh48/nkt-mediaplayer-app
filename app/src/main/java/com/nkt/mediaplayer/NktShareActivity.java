package com.nkt.mediaplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NktShareActivity extends Activity {
    private LinearLayout col;
    private ImageView wifi;
    private TextView status, line, address, login, startStop;
    private LinearLayout addrBox;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean startWhenGranted;

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
        build();
    }

    private void build() {
        col.removeAllViews();
        LinearLayout top = Ui.row(this);
        ImageButton back = Ui.roundBtn(this, "back", 44, Ui.TEXT, true);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        LinearLayout tt = Ui.col(this);
        tt.setPadding(Ui.dp(this, 12), 0, 0, 0);
        tt.addView(Ui.label(this, "NKT Share", 13, Ui.TEXT, 0.2f));
        tt.addView(Ui.text(this, "Share files with a computer or other devices on Wi-Fi", 12, Ui.MUTED));
        top.addView(tt, Ui.lpw(0, Ui.WRAP, 1));
        col.addView(top);

        LinearLayout card = Ui.col(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.rect(this, Ui.CARD, 16, Ui.BORDER, 1));
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        wifi = new ImageView(this);
        card.addView(wifi, Ui.lp(Ui.dp(this, 56), Ui.dp(this, 56)));
        status = Ui.text(this, "", 16, Ui.TEXT);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        status.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(status);
        line = Ui.text(this, "", 13, Ui.MUTED);
        line.setGravity(Gravity.CENTER);
        card.addView(line);
        addrBox = Ui.row(this);
        addrBox.setBackground(Ui.rect(this, Ui.FILL, 12, 0x4000E5FF, 1));
        addrBox.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        address = Ui.text(this, "", 17, Ui.ACCENT);
        address.setTextIsSelectable(true);
        addrBox.addView(address, Ui.lpw(0, Ui.WRAP, 1));
        ImageButton copy = Ui.iconBtn(this, "file", 44, Ui.ACCENT);
        copy.setContentDescription("Copy address");
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("address", address.getText()));
            Ui.msg(this, "Address copied");
        });
        addrBox.addView(copy);
        card.addView(addrBox, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        login = Ui.text(this, "", 12, Ui.MUTED);
        login.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(login);
        col.addView(card, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));

        startStop = Ui.pill(this, "START", true, Ui.ACCENT);
        startStop.setOnClickListener(v -> toggle());
        col.addView(startStop, Ui.lpm(this, Ui.MATCH, Ui.dp(this, 54), 0, 14, 0, 0));

        TextView help = Ui.text(this, "Windows: type the address in File Explorer's address bar\nMac: Finder → Go → Connect to Server\n"
                + "Another phone: NKT MP → Other devices, or any FTP app\nSharing stays on until you press STOP.", 12, Ui.MUTED);
        help.setLineSpacing(Ui.dp(this, 3), 1f);
        help.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4), 0);
        col.addView(help);

        col.addView(Ui.section(this, "Settings"));
        TextView folderArrow = Ui.text(this, "›", 22, Ui.MUTED);
        LinearLayout folderRow = Ui.infoRow(this, "Shared folder", friendly(Store.str(this, "share_root", Environment.getExternalStorageDirectory().getAbsolutePath())), folderArrow);
        folderRow.setOnClickListener(v -> pickFolder(new File(Store.str(this, "share_root", Environment.getExternalStorageDirectory().getAbsolutePath()))));
        col.addView(folderRow);
        boolean pw = Store.bool(this, "share_pw_on", false);
        Switch ps = Ui.toggle(this, pw);
        ps.setOnCheckedChangeListener((x, on) -> { Store.put(this, "share_pw_on", on); FtpService.password(this); restartIfRunning(); build(); });
        LinearLayout pwRow = Ui.infoRow(this, "Use a password", pw ? "nkt · " + FtpService.password(this) : "Off — anyone on your Wi-Fi can connect", ps);
        if (pw) {
            TextView np = Ui.chip(this, "New password", false);
            np.setOnClickListener(v -> { FtpService.newPassword(this); restartIfRunning(); build(); });
            pwRow.addView(np, 1, Ui.lpm(this, Ui.WRAP, Ui.dp(this, 34), 0, 0, 8, 0));
        }
        col.addView(pwRow);
        boolean rw = Store.bool(this, "share_rw", true);
        String access = !Perms.allFiles(this) ? "Tap to allow file access" : rw ? "Read & write · no time limit" : "Read only · no time limit";
        LinearLayout acc = Ui.infoRow(this, "Access", access, Ui.text(this, "›", 22, Ui.MUTED));
        acc.setOnClickListener(v -> {
            if (!Perms.allFiles(this)) { Perms.askAllFiles(this, "NKT Share needs access to your files to share them."); return; }
            String[] o = {"Read & write — open, copy, add, rename, delete", "Read only — open and copy, can't change anything"};
            new AlertDialog.Builder(this).setTitle("Access").setSingleChoiceItems(o, rw ? 0 : 1, (d, w) -> {
                Store.put(this, "share_rw", w == 0); d.dismiss(); restartIfRunning(); build();
            }).show();
        });
        col.addView(acc);
        refresh();
    }

    private void restartIfRunning() {
        if (!FtpService.running) return;
        FtpService.stop(this);
        h.postDelayed(() -> { FtpService.start(this); Ui.msg(this, "Restarted with the new setting"); }, 600);
    }

    private void toggle() {
        if (FtpService.running) { FtpService.stop(this); h.postDelayed(this::refresh, 300); return; }
        if (FtpService.ip() == null) { Ui.msg(this, "Connect to Wi-Fi (or turn on hotspot) first"); return; }
        if (!Perms.allFiles(this)) {
            startWhenGranted = true;
            new AlertDialog.Builder(this).setTitle("Allow file access")
                    .setMessage("To share your files, NKT MP needs access to all files. Turn it on in the next screen, then come back.")
                    .setPositiveButton("Allow", (d, w) -> Perms.openAllFiles(this)).setNegativeButton("Cancel", null).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
        FtpService.start(this);
        h.postDelayed(this::refresh, 500);
    }

    private void refresh() {
        boolean on = FtpService.running;
        wifi.setImageDrawable(new Icon(this, "wifi", on ? Ui.SUCCESS : Ui.MUTED));
        status.setText(on ? "Sharing is ON" : "Sharing is OFF");
        String ip = FtpService.ip();
        int n = Math.max(0, FtpService.devices.get());
        line.setText(FtpService.error != null && !on ? FtpService.error : on ? (n == 0 ? "Waiting for a device to connect" : n == 1 ? "1 device connected" : n + " devices connected")
                : ip == null ? "Connect to Wi-Fi (or turn on hotspot) first" : "Tap START, then type the address on your computer");
        addrBox.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
        address.setText("ftp://" + ip + ":" + FtpService.PORT);
        boolean pw = Store.bool(this, "share_pw_on", false);
        login.setVisibility(on && pw ? android.view.View.VISIBLE : android.view.View.GONE);
        login.setText("User: nkt · Password: " + FtpService.password(this));
        startStop.setText(on ? "STOP" : "START");
        startStop.setTextColor(on ? Ui.DANGER : Ui.BG);
        startStop.setBackground(on ? Ui.pressable(Ui.rect(this, 0x14FF4F6A, 27, Ui.DANGER, 2), Ui.rect(this, 0x33FF4F6A, 27, Ui.DANGER, 2))
                : Ui.pressable(Ui.rect(this, Ui.ACCENT, 27, 0, 0), Ui.rect(this, 0xFF00B8CC, 27, 0, 0)));
    }

    private final Runnable tick = new Runnable() { @Override public void run() { refresh(); h.postDelayed(this, 1000); } };

    @Override
    protected void onResume() {
        super.onResume();
        if (startWhenGranted && Perms.allFiles(this)) { startWhenGranted = false; FtpService.start(this); }
        build();
        h.post(tick);
    }

    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }

    private String friendly(String p) {
        String r = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (p.equals(r)) return "Phone storage";
        if (p.startsWith(r)) return "Phone storage" + p.substring(r.length());
        return p;
    }

    private void pickFolder(File at) {
        List<File> dirs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean isRoot = false;
        for (File r : MainActivity.storageRoots(this)) if (r.equals(at)) isRoot = true;
        names.add(isRoot ? "⬆ All storage" : "⬆ Up one folder");
        dirs.add(null);
        File[] kids = at.listFiles();
        List<File> sub = new ArrayList<>();
        if (kids != null) for (File f : kids) if (f.isDirectory() && !f.getName().startsWith(".")) sub.add(f);
        Collections.sort(sub, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : sub) { names.add("📁  " + f.getName()); dirs.add(f); }
        final boolean root = isRoot;
        new AlertDialog.Builder(this).setTitle(friendly(at.getAbsolutePath()))
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        if (root) pickRoot(); else pickFolder(at.getParentFile() == null ? at : at.getParentFile());
                    } else pickFolder(dirs.get(w));
                })
                .setPositiveButton("Share this folder", (d, w) -> { Store.put(this, "share_root", at.getAbsolutePath()); restartIfRunning(); build(); })
                .setNegativeButton("Cancel", null).show();
    }

    private void pickRoot() {
        List<File> roots = MainActivity.storageRoots(this);
        String[] n = new String[roots.size()];
        for (int i = 0; i < n.length; i++) n[i] = i == 0 ? "Phone storage" : "SD card / USB (" + roots.get(i).getName() + ")";
        new AlertDialog.Builder(this).setTitle("Choose a storage").setItems(n, (d, w) -> pickFolder(roots.get(w))).show();
    }
}
