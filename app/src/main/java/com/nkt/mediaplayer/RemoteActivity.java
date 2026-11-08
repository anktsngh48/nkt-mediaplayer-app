package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Other devices on the same Wi-Fi: phones running NKT Share announce themselves; any FTP server can be added with ＋.
 * Browse, play songs and videos straight from the other device, copy files to this phone, disconnect.
 */
public class RemoteActivity extends Activity implements RowAdapter.Listener, Selection.Changed {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService checks = Executors.newFixedThreadPool(3);

    private RecyclerView list;
    private RowAdapter rows;
    private Selection sel;
    private TextView title, sub, empty, status, sortBtn, selCount;
    private View bar, disconnectBtn, addBtn, selectBar;
    private ImageButton viewList, viewGrid;

    // discovery
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final Map<String, Remote.Device> found = new LinkedHashMap<>();
    private final Map<String, Boolean> online = new LinkedHashMap<>();
    private final List<NsdServiceInfo> toResolve = new ArrayList<>();
    private boolean resolving = false, searching = false;

    // connected
    private Remote.Device device;
    private FTPClient ftp;
    private String path = "/";
    private final List<FTPFile> files = new ArrayList<>();
    private boolean listing = false;

    private ListenableFuture<MediaController> future;
    private MediaController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote);
        Ui.padForSystemBars(findViewById(R.id.root));
        title = findViewById(R.id.rm_title);
        title.setTypeface(Ui.orbitron(this));
        sub = findViewById(R.id.rm_sub);
        empty = findViewById(R.id.rm_empty);
        status = findViewById(R.id.rm_status);
        bar = findViewById(R.id.rm_bar);
        sortBtn = findViewById(R.id.rm_sort);
        sortBtn.setTypeface(Ui.orbitron(this));
        viewList = findViewById(R.id.rm_view_list);
        viewGrid = findViewById(R.id.rm_view_grid);
        disconnectBtn = findViewById(R.id.rm_disconnect);
        addBtn = findViewById(R.id.rm_add);
        selectBar = findViewById(R.id.rm_select_bar);
        selCount = findViewById(R.id.rm_sel_count);

        findViewById(R.id.rm_back).setOnClickListener(v -> onBackPressed());
        addBtn.setOnClickListener(v -> editDevice(null));
        disconnectBtn.setOnClickListener(v -> disconnect());
        sortBtn.setOnClickListener(v -> SortSheet.show(this, "remote", Prefs.sort(this, "remote", "name"), view(),
                Prefs.foldersFirst(this, "remote"), new SortSheet.Listener() {
                    @Override public void onSort(String k) { Prefs.setSort(RemoteActivity.this, "remote", k); show(); }
                    @Override public void onView(String v) { Prefs.setView(RemoteActivity.this, "remote", v); show(); }
                    @Override public void onFoldersFirst(boolean on) { Prefs.setFoldersFirst(RemoteActivity.this, "remote", on); show(); }
                }));
        viewList.setOnClickListener(v -> { Prefs.setView(this, "remote", "list"); show(); });
        viewGrid.setOnClickListener(v -> { Prefs.setView(this, "remote", "grid"); show(); });

        sel = new Selection(this);
        findViewById(R.id.rm_sel_close).setOnClickListener(v -> sel.clear());
        findViewById(R.id.rm_sel_all).setOnClickListener(v -> sel.addAll(shownFiles()));
        findViewById(R.id.rm_sel_copy).setOnClickListener(v -> {
            List<MediaInfo> l = sel.items();
            String[] opts = {getString(R.string.copy_to_phone), getString(R.string.copy_files), getString(R.string.cut)};
            Dialogs.builder(this).setItems(opts, (d, w) -> {
                sel.clear();
                if (w == 0) copy(l);
                else { FileJobs.put(this, l, w == 2, device); show(); }
            }).show();
        });

        list = findViewById(R.id.rm_list);
        list.setItemAnimator(null);
        rows = new RowAdapter(this, sel);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(rows);

        show();
        discover();
        checkSaved();
    }

    private String view() { return Prefs.view(this, "remote", "list"); }

    // ── Finding phones on this Wi-Fi ─────────────────────────────────────────

    private android.net.wifi.WifiManager.MulticastLock multicast;

    private void discover() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            multicast = wm.createMulticastLock("nkt:devices");
            multicast.setReferenceCounted(false);
            multicast.acquire();
        } catch (Throwable ignored) { }
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            discovery = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int e) { ui.post(() -> { searching = false; show(); }); }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
                @Override public void onDiscoveryStarted(String t) { ui.post(() -> { searching = true; show(); }); ui.postDelayed(() -> { searching = false; show(); }, 8000); }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onServiceFound(NsdServiceInfo info) {
                    ui.post(() -> { toResolve.add(info); resolveNext(); });
                }
                @Override public void onServiceLost(NsdServiceInfo info) {
                    ui.post(() -> {
                        for (java.util.Iterator<Map.Entry<String, Remote.Device>> it = found.entrySet().iterator(); it.hasNext(); )
                            if (it.next().getValue().name.equals(info.getServiceName())) it.remove();
                        show();
                    });
                }
            };
            nsd.discoverServices("_ftp._tcp", NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Throwable t) { discovery = null; }
    }

    /** Android resolves one service at a time. */
    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || toResolve.isEmpty() || nsd == null) return;
        resolving = true;
        NsdServiceInfo info = toResolve.remove(0);
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int e) { ui.post(() -> { resolving = false; resolveNext(); }); }
                @Override public void onServiceResolved(NsdServiceInfo i) {
                    ui.post(() -> {
                        resolving = false;
                        InetAddress host = i.getHost();
                        boolean ours = "nktmediaplayer".equals(attr(i.getAttributes(), "app"));
                        if (host != null && ours && !isMe(host)) {
                            Remote.Device d = new Remote.Device();
                            d.found = true;
                            d.name = i.getServiceName();
                            d.host = host.getHostAddress();
                            d.port = i.getPort();
                            Map<String, byte[]> attrs = i.getAttributes();
                            d.app = "nktmediaplayer".equals(attr(attrs, "app"));
                            d.version = attr(attrs, "ver");
                            d.needsPassword = "1".equals(attr(attrs, "pw"));
                            d.readWrite = !"0".equals(attr(attrs, "rw"));
                            d.guest = !d.needsPassword;
                            if (d.needsPassword) d.user = "nkt";
                            found.put(d.key(), d);
                            online.put(d.key(), true);
                            show();
                        }
                        resolveNext();
                    });
                }
            });
        } catch (Throwable t) { resolving = false; }
    }

    private static String attr(Map<String, byte[]> a, String k) {
        byte[] v = a == null ? null : a.get(k);
        return v == null ? "" : new String(v, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean isMe(InetAddress a) {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress x : Collections.list(n.getInetAddresses())) if (x.equals(a)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    /** Saved devices: online or offline (a quick knock on the port). */
    private void checkSaved() {
        for (Remote.Device d : Remote.saved(this)) {
            checks.execute(() -> {
                boolean up;
                try (Socket s = new Socket()) { s.connect(new InetSocketAddress(d.host, d.port), 1500); up = true; }
                catch (Exception e) { up = false; }
                final boolean u = up;
                ui.post(() -> { online.put(d.key(), u); if (device == null) show(); });
            });
        }
    }

    // ── Screen ───────────────────────────────────────────────────────────────

    private void show() {
        if (isFinishing()) return;
        List<RowAdapter.Row> out = new ArrayList<>();
        if (device == null) {
            title.setText(R.string.other_devices_caps);
            sub.setText(R.string.devices_hint);
            sub.setMaxLines(4);                                   // the whole hint, on as many lines as it needs
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            bar.setVisibility(View.GONE);
            disconnectBtn.setVisibility(View.GONE);
            addBtn.setVisibility(View.VISIBLE);
            rows.setGrid(false);
            if (list.getLayoutManager() instanceof GridLayoutManager) list.setLayoutManager(new LinearLayoutManager(this));

            out.add(RowAdapter.Row.header(getString(R.string.found_wifi_caps) + (searching ? "   " + getString(R.string.looking) : "")));
            if (found.isEmpty()) out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.nothing_found), null, null, "none", R.drawable.ic_wifi));
            for (Remote.Device d : found.values()) out.add(deviceRow(d, true));
            out.add(RowAdapter.Row.header(getString(R.string.saved_caps)));
            List<Remote.Device> saved = Remote.saved(this);
            if (saved.isEmpty()) out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.no_saved), null, null, "add", R.drawable.ic_add));
            for (Remote.Device d : saved) out.add(deviceRow(d, false));
            empty.setVisibility(View.GONE);
            rows.setRows(out);
            return;
        }

        title.setText(device.label().toUpperCase(java.util.Locale.ROOT));
        sub.setText(device.address() + path);
        sub.setMaxLines(1);                                       // a long path keeps its end in view
        sub.setEllipsize(android.text.TextUtils.TruncateAt.START);
        bar.setVisibility(View.VISIBLE);
        disconnectBtn.setVisibility(View.VISIBLE);
        addBtn.setVisibility(View.GONE);
        status.setText(listing ? getString(R.string.connecting) : getString(device.readWrite ? R.string.connected_rw : R.string.connected_ro));
        sortBtn.setText(SortSheet.chip(this, Prefs.sort(this, "remote", "name")));
        showPaste();
        boolean grid = "grid".equals(view());
        viewList.setSelected(!grid);
        viewGrid.setSelected(grid);
        viewList.setImageTintList(ColorStateList.valueOf(getColor(!grid ? R.color.accent : R.color.muted)));
        viewGrid.setImageTintList(ColorStateList.valueOf(getColor(grid ? R.color.accent : R.color.muted)));

        if (!"/".equals(path)) out.add(new RowAdapter.Row(RowAdapter.BACK, getString(R.string.back_to, nameOf(Remote.parent(path))), null, null, "up", R.drawable.ic_arrow_up));
        List<MediaInfo> media = shownFiles();
        int songs = 0;
        for (MediaInfo m : media) if (m.kind() == MediaInfo.AUDIO) songs++;
        if (songs > 1) out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.play_all_songs_here), null, null, "play_all", R.drawable.ic_play));
        String saveTo = device.ask ? getString(R.string.ask_every_time) : prettySave(saveFolder(device));
        out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.copies_save_to) + " · " + getString(R.string.change_caps), saveTo, null, "save_to", R.drawable.ic_download));

        List<FTPFile> sorted = new ArrayList<>(files);
        String sort = Prefs.sort(this, "remote", "name");
        Collections.sort(sorted, order(sort));
        if (Prefs.foldersFirst(this, "remote")) {
            List<FTPFile> dirs = new ArrayList<>(), rest = new ArrayList<>();
            for (FTPFile f : sorted) (f.isDirectory() ? dirs : rest).add(f);
            sorted.clear(); sorted.addAll(dirs); sorted.addAll(rest);
        }
        for (FTPFile f : sorted) {
            String name = f.getName();
            if (name == null || ".".equals(name) || "..".equals(name)) continue;
            long t = f.getTimestamp() == null ? 0 : f.getTimestamp().getTimeInMillis();
            if (f.isDirectory()) {
                RowAdapter.Row fr = new RowAdapter.Row(RowAdapter.FOLDER, name, t > 0 ? date(t) : "", null, "dir:" + Remote.child(path, name), R.drawable.ic_folder);
                MediaInfo dm = MediaInfo.remoteFile(Remote.uri(device, Remote.child(path, name)), name, 0, t);
                dm.other = true;                          // a folder: long-press picks it for copy / cut
                List<MediaInfo> one = new ArrayList<>();
                one.add(dm);
                fr.pick = one;
                out.add(fr);
            } else {
                MediaInfo m = MediaInfo.remoteFile(Remote.uri(device, Remote.child(path, name)), name, f.getSize(), t);
                out.add(RowAdapter.Row.media(m, getString(R.string.file_line, Formatter.formatShortFileSize(this, f.getSize()), t > 0 ? date(t) : "")));
            }
        }
        rows.setGrid(grid);
        if (grid) {
            if (!(list.getLayoutManager() instanceof GridLayoutManager)) {
                GridLayoutManager glm = new GridLayoutManager(this, 3);
                glm.setSpanSizeLookup(rows.spans(3));
                list.setLayoutManager(glm);
            }
        } else if (list.getLayoutManager() instanceof GridLayoutManager) list.setLayoutManager(new LinearLayoutManager(this));
        rows.setRows(out);
        empty.setText(listing ? R.string.connecting : R.string.folder_empty);
        empty.setVisibility(listing || sorted.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private TextView pasteChip;

    /** PASTE n ITEMS on the bar while something is copied or cut (from this phone or any device). */
    private void showPaste() {
        if (pasteChip == null) {
            int dp = Ui.dp(this, 1);
            pasteChip = new TextView(this);
            pasteChip.setTypeface(Ui.orbitron(this));
            pasteChip.setTextSize(9.5f);
            pasteChip.setTextColor(getColor(R.color.accent));
            pasteChip.setGravity(Gravity.CENTER);
            pasteChip.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_paste, 0, 0, 0);
            pasteChip.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.accent)));
            pasteChip.setCompoundDrawablePadding(6 * dp);
            pasteChip.setBackgroundResource(R.drawable.bg_tile_btn);
            pasteChip.setPadding(10 * dp, 0, 12 * dp, 0);
            pasteChip.setOnClickListener(v -> pasteHere());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 30 * dp);
            lp.setMarginEnd(8 * dp);
            ((LinearLayout) bar).addView(pasteChip, 1, lp);
        }
        boolean on = device != null && FileJobs.hasClip();
        pasteChip.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) pasteChip.setText(FileJobs.pasteLabel(this));
    }

    private void pasteHere() {
        if (device == null || !FileJobs.hasClip()) return;
        final Remote.Device d = device;
        final String dir = path;
        FileJobs.pasteRemote(this, d, dir, (ok, bad) -> { if (device == d && dir.equals(path)) list(path); });
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (device != null) showPaste();
    }

    private RowAdapter.Row deviceRow(Remote.Device d, boolean wifi) {
        StringBuilder b = new StringBuilder();
        if (d.app || wifi) b.append(d.app ? getString(R.string.badge_app) : "FTP").append(" · ");
        b.append(d.address());
        if (wifi) b.append(" · ").append(getString(d.needsPassword ? R.string.with_password : R.string.open_login));
        else {
            Boolean up = online.get(d.key());
            b.append(" · ").append(getString(up == null ? R.string.checking : up ? R.string.online : R.string.offline));
            if (d.lastUsed > 0) b.append(" · ").append(getString(R.string.last_used, DateUtils.getRelativeTimeSpanString(d.lastUsed)));
        }
        return new RowAdapter.Row(RowAdapter.PLAYLIST, d.label(), b.toString(), null, (wifi ? "found:" : "saved:") + d.key(),
                d.app ? R.drawable.ic_phone : R.drawable.ic_pc);
    }

    private static String nameOf(String p) {
        if ("/".equals(p)) return "/";
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    private String date(long t) {
        return DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_YEAR);
    }

    private static Comparator<FTPFile> order(String sort) {
        Comparator<FTPFile> byName = (a, b) -> String.valueOf(a.getName()).compareToIgnoreCase(String.valueOf(b.getName()));
        switch (sort) {
            case "name_desc": return (a, b) -> byName.compare(b, a);
            case "new": return (a, b) -> Long.compare(time(b), time(a));
            case "old": return (a, b) -> Long.compare(time(a), time(b));
            case "large": return (a, b) -> Long.compare(b.getSize(), a.getSize());
            case "small": return (a, b) -> Long.compare(a.getSize(), b.getSize());
        }
        return byName;
    }

    private static long time(FTPFile f) { return f.getTimestamp() == null ? 0 : f.getTimestamp().getTimeInMillis(); }

    private List<MediaInfo> shownFiles() {
        List<MediaInfo> out = new ArrayList<>();
        for (RowAdapter.Row r : rows.rows()) if (r.type == RowAdapter.MEDIA) out.add(r.media);
        if (out.isEmpty() && device != null) {
            for (FTPFile f : files) if (!f.isDirectory() && f.getName() != null)
                out.add(MediaInfo.remoteFile(Remote.uri(device, Remote.child(path, f.getName())), f.getName(), f.getSize(), time(f)));
        }
        return out;
    }

    @Override
    public void onSelectionChanged() {
        selectBar.setVisibility(sel.active() ? View.VISIBLE : View.GONE);
        selCount.setText(getString(R.string.n_selected, sel.count()));
        rows.notifyDataSetChanged();
    }

    // ── Connecting & browsing ────────────────────────────────────────────────

    private void connect(Remote.Device d) {
        device = d;
        path = "/";
        files.clear();
        listing = true;
        show();
        net.execute(() -> {
            try {
                FTPClient f = Remote.connect(d);
                ui.post(() -> {
                    ftp = f;
                    Remote.setActive(d);
                    d.lastUsed = System.currentTimeMillis();
                    if (Remote.find(this, d.host, d.port) != null) Remote.save(this, d);
                    list(path);
                });
            } catch (Remote.LoginException e) {
                ui.post(() -> { leave(); Ui.toast(this, R.string.wrong_login); editDevice(d); });
            } catch (Exception e) {
                ui.post(() -> { leave(); failed(getString(R.string.cant_connect, d.label()), () -> connect(d)); });
            }
        });
    }

    private void list(String p) {
        if (device == null) return;
        listing = true;
        show();
        final Remote.Device d = device;
        net.execute(() -> {
            FTPFile[] got = null;
            IOException err = null;
            for (int attempt = 0; attempt < 2 && got == null; attempt++) {
                try {
                    if (ftp == null || !ftp.isConnected()) ftp = Remote.connect(d);
                    got = ftp.listFiles(p);
                    if (got == null) throw new IOException("list");
                } catch (IOException e) {
                    err = e;
                    Remote.quietly(ftp);
                    ftp = null;
                }
            }
            final FTPFile[] result = got;
            final boolean lost = result == null && err != null;
            ui.post(() -> {
                if (device != d) return;
                listing = false;
                if (lost) { failed(getString(R.string.lost_connection, d.label()), () -> list(p)); show(); return; }
                path = p;
                files.clear();
                for (FTPFile f : result) if (f != null) files.add(f);
                show();
                list.scrollToPosition(0);
            });
        });
    }

    private void failed(String msg, Runnable retry) {
        Dialogs.builder(this).setMessage(msg)
                .setPositiveButton(R.string.retry_caps, (x, y) -> retry.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void disconnect() {
        Remote.Device d = device;
        leave();
        if (d != null) Remote.clearActive(d);
    }

    private void leave() {
        final FTPClient f = ftp;
        ftp = null;
        device = null;
        files.clear();
        listing = false;
        if (sel.active()) sel.clear();
        net.execute(() -> Remote.quietly(f));
        show();
    }

    // ── Add / edit a device ──────────────────────────────────────────────────

    private void editDevice(Remote.Device start) {
        int dp = Ui.dp(this, 1);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(22 * dp, 4 * dp, 22 * dp, 0);
        TextView help = new TextView(this);
        help.setText(R.string.add_device_help);
        help.setTextColor(getColor(R.color.muted));
        help.setTextSize(12);
        box.addView(help);
        EditText name = field(box, R.string.f_name_opt, start == null ? "" : start.name, InputType.TYPE_CLASS_TEXT);
        EditText host = field(box, R.string.f_address, start == null ? "" : start.host, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        host.setHint("192.168.1.20");
        EditText port = field(box, R.string.f_port, start == null ? "21" : String.valueOf(start.port), InputType.TYPE_CLASS_NUMBER);
        box.addView(label(R.string.login_as_caps));
        RadioGroup rg = new RadioGroup(this);
        RadioButton guest = new RadioButton(this), userPw = new RadioButton(this);
        guest.setText(R.string.guest);
        userPw.setText(R.string.user_password);
        guest.setId(View.generateViewId());
        userPw.setId(View.generateViewId());
        for (RadioButton r : new RadioButton[]{guest, userPw}) {
            r.setTextColor(getColor(R.color.text));
            r.setButtonTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        }
        rg.setOrientation(RadioGroup.HORIZONTAL);
        rg.addView(guest);
        rg.addView(userPw);
        box.addView(rg);
        EditText user = field(box, R.string.f_user, start == null ? "" : start.user, InputType.TYPE_CLASS_TEXT);
        EditText pass = field(box, R.string.f_password, start == null ? "" : start.pass, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        boolean isGuest = start == null || start.guest;
        rg.check(isGuest ? guest.getId() : userPw.getId());
        Runnable sync = () -> {
            boolean g = rg.getCheckedRadioButtonId() == guest.getId();
            user.setEnabled(!g); pass.setEnabled(!g);
            user.setAlpha(g ? 0.4f : 1f); pass.setAlpha(g ? 0.4f : 1f);
        };
        rg.setOnCheckedChangeListener((g, id) -> sync.run());
        sync.run();
        CheckBox save = new CheckBox(this);
        save.setText(R.string.save_device);
        save.setTextColor(getColor(R.color.text));
        save.setButtonTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        save.setChecked(start == null || !start.found || Remote.find(this, start.host, start.port) != null);
        box.addView(save);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        android.app.AlertDialog dlg = Dialogs.builder(this)
                .setTitle(R.string.add_device)
                .setView(scroll)
                .setPositiveButton(R.string.connect_caps, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String h = host.getText().toString().trim();
            if (h.startsWith("ftp://")) h = h.substring(6);
            if (h.endsWith("/")) h = h.substring(0, h.length() - 1);
            int p = 21;
            int colon = h.lastIndexOf(':');
            if (colon > 0 && h.indexOf(':') == colon) {             // "host:port" typed in the address box
                try { p = Integer.parseInt(h.substring(colon + 1)); } catch (Exception ignored) { }
                h = h.substring(0, colon);
            }
            if (h.isEmpty()) { Ui.toast(this, R.string.address_needed); return; }
            try { String ps = port.getText().toString().trim(); if (!ps.isEmpty() && colon < 0) p = Integer.parseInt(ps); } catch (Exception ignored) { }
            Remote.Device d = start != null && !start.found ? start : new Remote.Device();
            if (start != null && start.found) { d.app = start.app; d.readWrite = start.readWrite; }
            d.name = name.getText().toString().trim();
            d.host = h;
            d.port = p;
            d.guest = rg.getCheckedRadioButtonId() == guest.getId();
            d.user = d.guest ? "" : user.getText().toString().trim();
            d.pass = d.guest ? "" : pass.getText().toString();
            if (start != null && !start.key().equals(d.key()) && !start.found) Remote.forget(this, start);
            if (save.isChecked()) Remote.save(this, d);
            dlg.dismiss();
            connect(d);
        }));
        dlg.show();
    }

    private EditText field(LinearLayout box, int label, String value, int type) {
        box.addView(label(label));
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setTextColor(getColor(R.color.text));
        box.addView(e);
        return e;
    }

    private TextView label(int text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Ui.orbitron(this));
        t.setTextSize(9.5f);
        t.setLetterSpacing(0.12f);
        t.setTextColor(getColor(R.color.muted));
        t.setPadding(0, Ui.dp(this, 12), 0, 0);
        return t;
    }

    // ── Clicks ───────────────────────────────────────────────────────────────

    @Override
    public void onClick(RowAdapter.Row r, int position) {
        switch (r.type) {
            case RowAdapter.PLAYLIST: {
                Remote.Device d = deviceFor(r.key);
                if (d == null) return;
                if (d.found && d.needsPassword && Remote.find(this, d.host, d.port) == null) editDevice(d);
                else connect(d);
                break;
            }
            case RowAdapter.FOLDER:
                if (sel.active() && r.pick != null) { sel.toggleAll(r.pick); return; }
                if (r.key.startsWith("dir:")) list(r.key.substring(4));
                break;
            case RowAdapter.BACK: list(Remote.parent(path)); break;
            case RowAdapter.MEDIA:
                if (sel.active()) { sel.toggle(r.media); return; }
                open(r.media);
                break;
            case RowAdapter.ACTION:
                if ("add".equals(r.key)) editDevice(null);
                else if ("play_all".equals(r.key)) {
                    List<MediaInfo> songs = new ArrayList<>();
                    for (MediaInfo m : shownFiles()) if (m.kind() == MediaInfo.AUDIO) songs.add(m);
                    playSongs(songs, 0);
                } else if ("save_to".equals(r.key)) chooseSaveTo(device, null);
                break;
        }
    }

    private Remote.Device deviceFor(String key) {
        if (key.startsWith("found:")) return found.get(key.substring(6));
        if (key.startsWith("saved:")) {
            String k = key.substring(6);
            for (Remote.Device d : Remote.saved(this)) if (d.key().equals(k)) {
                Remote.Device f = found.get(k);
                if (f != null) { d.app = f.app; d.readWrite = f.readWrite; }
                return d;
            }
        }
        return null;
    }

    @Override
    public void onLongClick(RowAdapter.Row r, int position) {
        if (r.type == RowAdapter.PLAYLIST) { deviceMenu(r); return; }
        if (r.type == RowAdapter.FOLDER && r.pick != null) { sel.toggleAll(r.pick); return; }
        if (r.type == RowAdapter.MEDIA) { if (sel.active()) sel.toggle(r.media); else sel.start(r.media); }
    }

    @Override
    public void onMore(RowAdapter.Row r, int position) {
        if (r.type == RowAdapter.PLAYLIST) { deviceMenu(r); return; }
        if (r.type != RowAdapter.MEDIA) return;
        MediaInfo m = r.media;
        String[] opts = {getString(m.kind() <= MediaInfo.VIDEO ? R.string.play : R.string.open), getString(R.string.copy_to_phone),
                getString(R.string.copy_files), getString(R.string.cut), getString(R.string.select), getString(R.string.properties)};
        Dialogs.builder(this).setTitle(m.fileName).setItems(opts, (d, w) -> {
            List<MediaInfo> one = new ArrayList<>();
            one.add(m);
            if (w == 0) open(m);
            else if (w == 1) copy(one);
            else if (w == 2 || w == 3) { FileJobs.put(this, one, w == 3, device); show(); }
            else if (w == 4) sel.start(m);
            else FileOps.properties(this, m, null, null, null);
        }).show();
    }

    private void deviceMenu(RowAdapter.Row r) {
        Remote.Device d = deviceFor(r.key);
        if (d == null) return;
        boolean saved = Remote.find(this, d.host, d.port) != null;
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.connect_caps));
        opts.add(getString(R.string.edit));
        opts.add(getString(R.string.save_copies_caps));
        if (saved) opts.add(getString(R.string.forget));
        Dialogs.builder(this).setTitle(d.label()).setItems(opts.toArray(new String[0]), (x, w) -> {
            if (w == 0) connect(d);
            else if (w == 1) editDevice(d);
            else if (w == 2) chooseSaveTo(d, null);
            else { Remote.forget(this, d); show(); }
        }).show();
    }

    /** Songs play in the music player, videos in the video player, photos in the viewer; anything else is copied first. */
    private void open(MediaInfo m) {
        List<MediaInfo> all = shownFiles();
        int k = m.kind();
        if (k == MediaInfo.AUDIO) {
            List<MediaInfo> songs = new ArrayList<>();
            for (MediaInfo x : all) if (x.kind() == MediaInfo.AUDIO) songs.add(x);
            playSongs(songs, Math.max(0, songs.indexOf(m)));
        } else if (k == MediaInfo.VIDEO) {
            List<MediaInfo> vids = new ArrayList<>();
            for (MediaInfo x : all) if (x.kind() == MediaInfo.VIDEO) vids.add(x);
            PlayerActivity.open(this, vids, Math.max(0, vids.indexOf(m)));
        } else {
            // photos, text, PDF and others: fetch a copy into the cache, then open it here
            Ui.toast(this, R.string.opening);
            final Remote.Device d = device;
            checks.execute(() -> {
                File f = cacheCopy(d, m);
                ui.post(() -> {
                    if (f == null) { Ui.toast(this, getString(R.string.copy_failed, m.fileName)); return; }
                    MediaInfo local = MediaInfo.fromFile(f);
                    if (k == MediaInfo.IMAGE) {
                        List<MediaInfo> one = new ArrayList<>();
                        one.add(local);
                        ViewerActivity.open(this, one, 0);
                    } else if (k == MediaInfo.TEXT) TextEditorActivity.open(this, Uri.fromFile(f));
                    else if (k == MediaInfo.PDF) PdfActivity.open(this, Uri.fromFile(f));
                    else FileOps.openWith(this, local);
                });
            });
        }
    }

    private File cacheCopy(Remote.Device d, MediaInfo m) {
        File dir = new File(getCacheDir(), "remote/" + Lyrics.idOf(m.key()));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, m.fileName);
        if (f.exists() && f.length() == m.size) return f;
        FTPClient c = null;
        try (OutputStream out = new FileOutputStream(f)) {
            c = Remote.connect(d);
            if (!c.retrieveFile(m.uri.getPath(), out)) throw new IOException("get");
            return f;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        } finally { Remote.quietly(c); }
    }

    private void playSongs(List<MediaInfo> songs, int index) {
        if (songs.isEmpty()) return;
        final List<MediaInfo> copy = new ArrayList<>(songs);
        future = Media.connect(this);
        future.addListener(() -> {
            try {
                controller = future.get();
                controller.setMediaItems(Media.items(copy), index, 0);
                controller.prepare();
                controller.play();
                startActivity(new Intent(this, NowPlayingActivity.class));
            } catch (Exception e) { Ui.toast(this, R.string.cant_play); }
        }, ui::post);
    }

    // ── Where copies go ──────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static File defaultSave() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NKT Media Player");
    }

    private File saveFolder(Remote.Device d) {
        return d == null || d.saveTo.isEmpty() ? defaultSave() : new File(d.saveTo);
    }

    private String prettySave(File f) { return FolderPicker.pretty(this, FolderPicker.roots(this), f); }

    /** SAVE COPIES TO: the folder (with storage switch, new folder) and Ask every time. then = run after choosing. */
    private void chooseSaveTo(Remote.Device d, java.util.function.Consumer<File> then) {
        if (d == null) return;
        int dp = Ui.dp(this, 1);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(22 * dp, 6 * dp, 22 * dp, 0);
        TextView help = new TextView(this);
        help.setText(getString(R.string.save_copies_help, d.label()));
        help.setTextColor(getColor(R.color.muted));
        help.setTextSize(12.5f);
        box.addView(help);
        final File[] chosen = {saveFolder(d)};
        TextView where = new TextView(this);
        where.setText(prettySave(chosen[0]));
        where.setTextColor(getColor(R.color.accent));
        where.setTextSize(14);
        where.setPadding(0, 12 * dp, 0, 4 * dp);
        box.addView(where);
        // quick places
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        @SuppressWarnings("deprecation") File root = Environment.getExternalStorageDirectory();
        String[] quick = {"Download", "Music", "Movies", "Pictures", "DCIM"};
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        for (String q : quick) {
            TextView c = new TextView(this);
            c.setText(q);
            c.setTextColor(getColor(R.color.text));
            c.setTextSize(12);
            c.setBackgroundResource(R.drawable.bg_chip);
            c.setGravity(Gravity.CENTER);
            c.setPadding(12 * dp, 0, 12 * dp, 0);
            c.setOnClickListener(v -> { chosen[0] = new File(root, q); where.setText(prettySave(chosen[0])); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 34 * dp);
            lp.setMarginEnd(6 * dp);
            chips.addView(c, lp);
        }
        hs.addView(chips);
        box.addView(hs);
        TextView browse = new TextView(this);
        browse.setText(R.string.change_caps);
        browse.setTypeface(Ui.orbitron(this));
        browse.setTextSize(10);
        browse.setTextColor(getColor(R.color.accent));
        browse.setPadding(0, 12 * dp, 0, 8 * dp);
        browse.setOnClickListener(v -> FolderPicker.show(this, chosen[0].exists() ? chosen[0].getAbsolutePath() : root.getAbsolutePath(), p -> {
            chosen[0] = new File(p);
            where.setText(prettySave(chosen[0]));
        }));
        box.addView(browse);
        Switch ask = new Switch(this);
        ask.setText(R.string.ask_every_time);
        ask.setTextColor(getColor(R.color.text));
        ask.setChecked(d.ask);
        ask.setThumbTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        box.addView(ask);
        TextView askSub = new TextView(this);
        askSub.setText(R.string.ask_every_time_sub);
        askSub.setTextColor(getColor(R.color.muted));
        askSub.setTextSize(11.5f);
        box.addView(askSub);

        Dialogs.builder(this)
                .setTitle(then == null ? R.string.save_copies_caps : R.string.copy_to_phone)
                .setView(box)
                .setPositiveButton(R.string.save_here_caps, (x, w) -> {
                    d.saveTo = chosen[0].getAbsolutePath();
                    d.ask = ask.isChecked();
                    if (Remote.find(this, d.host, d.port) != null) Remote.save(this, d);
                    if (then != null) then.accept(chosen[0]);
                    show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Copy to this phone ───────────────────────────────────────────────────

    private void copy(List<MediaInfo> items) {
        if (items.isEmpty() || device == null) return;
        Remote.Device d = device;
        if (d.ask) chooseSaveTo(d, dir -> startCopy(d, items, dir));
        else startCopy(d, items, saveFolder(d));
    }

    private static final String CHANNEL = "copies";
    private static int notifId = 5100;

    @SuppressWarnings("deprecation")
    private void startCopy(Remote.Device d, List<MediaInfo> items, File dir) {
        final Context app = getApplicationContext();
        final NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.copies_channel), NotificationManager.IMPORTANCE_LOW));
        final int id = notifId++;
        final String where = prettySave(dir);
        Ui.toast(this, getString(R.string.saving_to, where));
        new Thread(() -> {
            int ok = 0;
            String failedName = null;
            FTPClient c = null;
            try {
                c = Remote.connect(d);
                for (int i = 0; i < items.size(); i++) {
                    MediaInfo m = items.get(i);
                    notify(app, nm, id, items.size() == 1 ? app.getString(R.string.copying_one) : app.getString(R.string.copying_n, i + 1, items.size()),
                            m.fileName, i, items.size(), true);
                    if (save(app, c, m, dir)) ok++; else failedName = m.fileName;
                }
            } catch (Exception e) {
                failedName = items.get(0).fileName;
            } finally { Remote.quietly(c); }
            final int n = ok;
            final String bad = failedName;
            String text = n > 0 ? app.getString(R.string.copied_to, where) : app.getString(R.string.copy_failed, bad);
            notify(app, nm, id, text, bad == null ? null : app.getString(R.string.copy_failed, bad), 1, 1, false);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isFinishing()) Ui.toast(this, bad != null && n > 0 ? text + " · " + app.getString(R.string.copy_failed, bad) : text);
            });
        }).start();
    }

    private static void notify(Context app, NotificationManager nm, int id, String title, String text, int done, int total, boolean ongoing) {
        if (nm == null) return;
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(app, CHANNEL)
                    : new Notification.Builder(app);
            b.setSmallIcon(R.drawable.ic_download).setContentTitle(title).setOngoing(ongoing).setOnlyAlertOnce(true);
            if (text != null) b.setContentText(text);
            if (ongoing) b.setProgress(total, done, false);
            nm.notify(id, b.build());
        } catch (Exception ignored) { }
    }

    /** One file into dir: straight to the folder when allowed, otherwise through Android's media store. */
    private static boolean save(Context c, FTPClient ftp, MediaInfo m, File dir) {
        String name = m.fileName;
        boolean direct = Build.VERSION.SDK_INT < 29 || HiddenScanner.hasAllFilesAccess(c);
        if (direct) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = unique(dir, name);
            try (OutputStream out = new FileOutputStream(f)) {
                if (!ftp.retrieveFile(m.uri.getPath(), out)) throw new IOException("get");
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return false;
            }
            android.media.MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
            return true;
        }
        // Android 10+: Download, Music, Movies, Pictures and DCIM work through the media store
        @SuppressWarnings("deprecation") String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        String abs = dir.getAbsolutePath();
        if (!abs.startsWith(root + "/")) return false;
        String rel = abs.substring(root.length() + 1);
        String top = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
        Uri collection;
        int k = m.kind();
        if (top.equalsIgnoreCase("Download")) collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        else if (top.equalsIgnoreCase("Music") && k == MediaInfo.AUDIO) collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        else if ((top.equalsIgnoreCase("Movies") || top.equalsIgnoreCase("DCIM") || top.equalsIgnoreCase("Pictures")) && k == MediaInfo.VIDEO)
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        else if ((top.equalsIgnoreCase("Pictures") || top.equalsIgnoreCase("DCIM")) && k == MediaInfo.IMAGE)
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        else return false;
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel + "/");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = null;
        try {
            u = c.getContentResolver().insert(collection, v);
            if (u == null) return false;
            try (OutputStream out = c.getContentResolver().openOutputStream(u)) {
                if (out == null || !ftp.retrieveFile(m.uri.getPath(), out)) throw new IOException("get");
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            c.getContentResolver().update(u, done, null, null);
            return true;
        } catch (Exception e) {
            if (u != null) try { c.getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
            return false;
        }
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = MediaInfo.stripExt(name), ext = MediaInfo.ext(name);
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + (ext.isEmpty() ? "" : "." + ext));
            if (!f.exists()) return f;
        }
        return f;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (sel.active()) { sel.clear(); return; }
        if (device != null) {
            if (!"/".equals(path)) { list(Remote.parent(path)); return; }
            disconnect();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (nsd != null && discovery != null) try { nsd.stopServiceDiscovery(discovery); } catch (Throwable ignored) { }
        try { if (multicast != null && multicast.isHeld()) multicast.release(); } catch (Throwable ignored) { }
        if (future != null) MediaController.releaseFuture(future);
        final FTPClient f = ftp;
        net.execute(() -> Remote.quietly(f));
        net.shutdown();
        checks.shutdown();
        super.onDestroy();
    }
}
