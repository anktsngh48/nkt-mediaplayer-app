package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Browses another phone (NKT Share) or any FTP server; opens files or copies them to this phone. */
public class RemoteActivity extends Activity {
    private JSONObject dev;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private FTPClient ftp;
    private String path = "/";
    private final List<FTPFile> files = new ArrayList<>();
    private TextView statusView, pathView, countView, progress;
    private RecyclerView list;
    private String sort = Sorts.AZ;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { dev = new JSONObject(getIntent().getStringExtra("device")); } catch (Exception e) { finish(); return; }
        LinearLayout col = Ui.col(this);
        col.setBackgroundColor(Ui.BG);
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        LinearLayout top = Ui.row(this);
        ImageButton back = Ui.roundBtn(this, "back", 44, Ui.TEXT, true);
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back);
        LinearLayout tt = Ui.col(this);
        tt.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView name = Ui.single(Ui.text(this, dev.optString("name", dev.optString("host")), 16, Ui.TEXT));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        tt.addView(name);
        statusView = Ui.single(Ui.text(this, "Connecting…", 12, Ui.MUTED));
        tt.addView(statusView);
        top.addView(tt, Ui.lpw(0, Ui.WRAP, 1));
        TextView disc = Ui.chip(this, "Disconnect", false);
        disc.setOnClickListener(v -> finish());
        top.addView(disc, Ui.lp(Ui.WRAP, Ui.dp(this, 36)));
        col.addView(top);
        pathView = Ui.single(Ui.text(this, "/", 13, Ui.MUTED));
        pathView.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, Ui.dp(this, 6));
        col.addView(pathView);
        LinearLayout bar = Ui.row(this);
        countView = Ui.text(this, "", 12, Ui.MUTED);
        bar.addView(countView, Ui.lpw(0, Ui.WRAP, 1));
        TextView sortChip = Ui.chip(this, "Sort", false);
        sortChip.setOnClickListener(v -> {
            String[] o = {Sorts.AZ, Sorts.ZA, Sorts.NEW, Sorts.OLD, "Biggest", "Smallest", "Folders first"};
            new AlertDialog.Builder(this).setTitle("Sort by").setItems(o, (d, w) -> { sort = o[w]; sortFiles(); list.getAdapter().notifyDataSetChanged(); }).show();
        });
        bar.addView(sortChip, Ui.lp(Ui.WRAP, Ui.dp(this, 34)));
        TextView playAll = Ui.chip(this, "▶  Play all songs here", false);
        playAll.setOnClickListener(v -> playAllSongs());
        bar.addView(playAll, Ui.lpm(this, Ui.WRAP, Ui.dp(this, 34), 8, 0, 0, 0));
        col.addView(bar);
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new FileAdapter());
        col.addView(list, Ui.lpw(Ui.MATCH, 0, 1));
        progress = Ui.text(this, "", 13, Ui.TEXT);
        progress.setBackground(Ui.rect(this, Ui.CARD, 16, 0x4000E5FF, 1));
        progress.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        progress.setVisibility(View.GONE);
        col.addView(progress, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
        setContentView(col);
        Ui.edgeToEdge(this, col);
        if (getIntent().getBooleanExtra("askLogin", false)) askLoginThenConnect(); else connectAndList();
    }

    private void askLoginThenConnect() {
        new AlertDialog.Builder(this).setTitle("How do you log in?")
                .setItems(new String[]{"As guest (no password)", "With user name and password"}, (d, w) -> {
                    if (w == 0) connectAndList();
                    else {
                        LinearLayout box = Ui.col(this);
                        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
                        EditText u = new EditText(this); u.setText("nkt"); u.setTextColor(Ui.TEXT); u.setHint("User name");
                        EditText p = new EditText(this); p.setTextColor(Ui.TEXT); p.setHint("Password");
                        p.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                        box.addView(u); box.addView(p);
                        new AlertDialog.Builder(this).setTitle("Log in").setView(box).setPositiveButton("Connect", (x, y) -> {
                            try { dev.put("guest", false).put("user", u.getText().toString()).put("pass", p.getText().toString()); } catch (Exception ignored) {}
                            connectAndList();
                        }).setNegativeButton("Cancel", (x, y) -> finish()).show();
                    }
                }).setOnCancelListener(d -> finish()).show();
    }

    private boolean ensure() throws Exception {
        if (ftp != null && ftp.isConnected()) {
            try { if (ftp.sendNoOp()) return true; } catch (Exception ignored) {}
        }
        ftp = new FTPClient();
        ftp.setControlEncoding("UTF-8");
        ftp.setConnectTimeout(8000);
        ftp.setDefaultTimeout(15000);
        ftp.connect(dev.optString("host"), dev.optInt("port", 21));
        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) throw new Exception("refused");
        boolean ok = dev.optBoolean("guest", true) ? ftp.login("anonymous", "nktmp@") : ftp.login(dev.optString("user"), dev.optString("pass"));
        if (!ok) throw new SecurityException("login");
        ftp.enterLocalPassiveMode();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        return true;
    }

    private void connectAndList() {
        statusView.setText("Connecting…");
        io.execute(() -> {
            try {
                ensure();
                FTPFile[] l = ftp.listFiles(path);
                runOnUiThread(() -> {
                    files.clear();
                    for (FTPFile f : l) if (f != null && !f.getName().equals(".") && !f.getName().equals("..")) files.add(f);
                    sortFiles();
                    statusView.setText("● Connected");
                    statusView.setTextColor(Ui.SUCCESS);
                    pathView.setText(path);
                    int nf = 0;
                    for (FTPFile f : files) if (f.isDirectory()) nf++;
                    countView.setText(nf + " folders · " + (files.size() - nf) + " files");
                    list.getAdapter().notifyDataSetChanged();
                    list.scrollToPosition(0);
                });
            } catch (SecurityException se) {
                runOnUiThread(() -> { statusView.setText("Wrong user name or password"); statusView.setTextColor(Ui.DANGER); });
            } catch (Exception e) {
                runOnUiThread(() -> { statusView.setText("Couldn't connect. Is it on the same Wi-Fi and sharing?"); statusView.setTextColor(Ui.DANGER); });
            }
        });
    }

    private void sortFiles() {
        java.util.Comparator<FTPFile> k;
        switch (sort) {
            case Sorts.ZA: k = (a, b) -> b.getName().compareToIgnoreCase(a.getName()); break;
            case Sorts.NEW: k = (a, b) -> Long.compare(time(b), time(a)); break;
            case Sorts.OLD: k = (a, b) -> Long.compare(time(a), time(b)); break;
            case "Biggest": k = (a, b) -> Long.compare(b.getSize(), a.getSize()); break;
            case "Smallest": k = (a, b) -> Long.compare(a.getSize(), b.getSize()); break;
            default: k = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        }
        java.util.Comparator<FTPFile> ff = (a, b) -> Boolean.compare(!a.isDirectory(), !b.isDirectory());
        files.sort(ff.thenComparing(k));
    }

    static long time(FTPFile f) { return f.getTimestamp() == null ? 0 : f.getTimestamp().getTimeInMillis(); }

    @Override
    public void onBackPressed() {
        if (!path.equals("/")) {
            String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            int i = p.lastIndexOf('/');
            path = i <= 0 ? "/" : p.substring(0, i);
            connectAndList();
            return;
        }
        super.onBackPressed();
    }

    private String join(String name) { return path.endsWith("/") ? path + name : path + "/" + name; }

    /** Copy a remote file into the cache, then open it. */
    private void open(FTPFile f) {
        File dir = new File(getCacheDir(), "remote");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, f.getName());
        fetch(f, out, () -> {
            List<File> sib = new ArrayList<>();
            sib.add(out);
            Media.openFile(this, out, sib);
        });
    }

    private void playAllSongs() {
        List<FTPFile> songs = new ArrayList<>();
        for (FTPFile f : files) if (!f.isDirectory() && MediaInfo.kindOf(f.getName()) == MediaInfo.AUDIO) songs.add(f);
        if (songs.isEmpty()) { Ui.msg(this, "No songs in this folder"); return; }
        File dir = new File(getCacheDir(), "remote");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        progress.setVisibility(View.VISIBLE);
        progress.setText("Getting " + songs.size() + " songs ready…");
        io.execute(() -> {
            List<MediaInfo> l = new ArrayList<>();
            try {
                ensure();
                for (FTPFile f : songs) {
                    File out = new File(dir, f.getName());
                    if (!out.exists() || out.length() != f.getSize()) {
                        try (OutputStream o = new FileOutputStream(out)) { ftp.retrieveFile(join(f.getName()), o); }
                    }
                    MediaInfo m = MediaInfo.fromFile(out);
                    m.folder = dev.optString("name");
                    l.add(m);
                }
                runOnUiThread(() -> { progress.setVisibility(View.GONE); Media.playSongs(this, l, 0, true); });
            } catch (Exception e) {
                runOnUiThread(() -> { progress.setVisibility(View.GONE); Ui.msg(this, "Couldn't get the songs"); });
            }
        });
    }

    private void fetch(FTPFile f, File out, Runnable done) {
        progress.setVisibility(View.VISIBLE);
        progress.setText("Opening " + f.getName() + "…");
        io.execute(() -> {
            try {
                ensure();
                if (!out.exists() || out.length() != f.getSize()) {
                    try (OutputStream o = new FileOutputStream(out)) {
                        if (!ftp.retrieveFile(join(f.getName()), o)) throw new Exception("retrieve");
                    }
                }
                runOnUiThread(() -> { progress.setVisibility(View.GONE); done.run(); });
            } catch (Exception e) {
                runOnUiThread(() -> { progress.setVisibility(View.GONE); Ui.msg(this, "Couldn't open this file"); });
            }
        });
    }

    /** Copies a remote file into Download/NKT MP on this phone. */
    @SuppressWarnings("deprecation")
    private void download(FTPFile f) {
        progress.setVisibility(View.VISIBLE);
        progress.setText("Copying " + f.getName() + " to this phone…");
        io.execute(() -> {
            try {
                ensure();
                OutputStream o;
                Uri target = null;
                File outFile = null;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, f.getName());
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NKT MP");
                    v.put(MediaStore.Downloads.IS_PENDING, 1);
                    target = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (target == null) throw new Exception("insert");
                    o = getContentResolver().openOutputStream(target);
                } else {
                    File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NKT MP");
                    //noinspection ResultOfMethodCallIgnored
                    d.mkdirs();
                    outFile = new File(d, f.getName());
                    o = new FileOutputStream(outFile);
                }
                long total = Math.max(1, f.getSize());
                try (InputStream in = ftp.retrieveFileStream(join(f.getName())); OutputStream out = o) {
                    if (in == null) throw new Exception("stream");
                    byte[] buf = new byte[64 * 1024];
                    long got = 0, lastUi = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        if (System.currentTimeMillis() - lastUi > 300) {
                            lastUi = System.currentTimeMillis();
                            final int pct = (int) (got * 100 / total);
                            runOnUiThread(() -> progress.setText(String.format(Locale.US, "Copying %s · %d%%\nSaving to Download/NKT MP", f.getName(), pct)));
                        }
                    }
                }
                ftp.completePendingCommand();
                if (target != null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(target, v, null, null);
                } else if (outFile != null) {
                    android.media.MediaScannerConnection.scanFile(this, new String[]{outFile.getAbsolutePath()}, null, null);
                }
                runOnUiThread(() -> { progress.setVisibility(View.GONE); Ui.msg(this, "Saved in Download/NKT MP"); Library.refresh(this); });
            } catch (Exception e) {
                runOnUiThread(() -> { progress.setVisibility(View.GONE); Ui.msg(this, "Couldn't copy this file"); });
            }
        });
    }

    @Override
    protected void onDestroy() {
        final FTPClient c = ftp;
        io.execute(() -> { try { if (c != null) { c.logout(); c.disconnect(); } } catch (Exception ignored) {} });
        io.shutdown();
        super.onDestroy();
    }

    final class FileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return files.size(); }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout r = Ui.row(RemoteActivity.this);
            r.setLayoutParams(new RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP));
            return new RecyclerView.ViewHolder(r) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
            FTPFile f = files.get(pos);
            LinearLayout r = (LinearLayout) vh.itemView;
            r.removeAllViews();
            r.setBackground(Ui.rowBg(RemoteActivity.this, false));
            r.setPadding(Ui.dp(RemoteActivity.this, 8), Ui.dp(RemoteActivity.this, 8), Ui.dp(RemoteActivity.this, 4), Ui.dp(RemoteActivity.this, 8));
            ((RecyclerView.LayoutParams) r.getLayoutParams()).topMargin = Ui.dp(RemoteActivity.this, 6);
            ImageView ic = new ImageView(RemoteActivity.this);
            int k = MediaInfo.kindOf(f.getName());
            ic.setImageDrawable(new Icon(RemoteActivity.this, f.isDirectory() ? "folder" : k == MediaInfo.AUDIO ? "music" : k == MediaInfo.VIDEO ? "video" : k == MediaInfo.IMAGE ? "image" : "file",
                    f.isDirectory() ? Ui.ACCENT : Ui.MUTED));
            ic.setBackground(Ui.rect(RemoteActivity.this, Ui.FILL, 8, 0, 0));
            int pd = Ui.dp(RemoteActivity.this, 12);
            ic.setPadding(pd, pd, pd, pd);
            r.addView(ic, Ui.lp(Ui.dp(RemoteActivity.this, 48), Ui.dp(RemoteActivity.this, 48)));
            LinearLayout t = Ui.col(RemoteActivity.this);
            t.setPadding(Ui.dp(RemoteActivity.this, 12), 0, 0, 0);
            t.addView(Ui.single(Ui.text(RemoteActivity.this, f.getName(), 14, Ui.TEXT)));
            t.addView(Ui.single(Ui.text(RemoteActivity.this, f.isDirectory() ? "Folder" : Ui.size(f.getSize()) + (k >= 0 ? " · tap to play" : ""), 12, Ui.MUTED)));
            r.addView(t, Ui.lpw(0, Ui.WRAP, 1));
            if (!f.isDirectory()) {
                ImageButton dl = Ui.iconBtn(RemoteActivity.this, "download", 44, Ui.ACCENT);
                dl.setContentDescription("Copy to this phone");
                dl.setOnClickListener(v -> download(f));
                r.addView(dl);
            } else {
                TextView arrow = Ui.text(RemoteActivity.this, "›", 22, Ui.MUTED);
                arrow.setGravity(Gravity.CENTER);
                r.addView(arrow, Ui.lp(Ui.dp(RemoteActivity.this, 36), Ui.WRAP));
            }
            r.setOnClickListener(v -> {
                if (f.isDirectory()) { path = join(f.getName()); connectAndList(); }
                else open(f);
            });
        }
    }

}
