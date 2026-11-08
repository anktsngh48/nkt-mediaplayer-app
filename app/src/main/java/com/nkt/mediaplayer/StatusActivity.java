package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WhatsApp status saver: the photos and videos of statuses you've watched in WhatsApp (and WhatsApp Business),
 * which vanish after 24 hours. Tap to view, Save to keep them (Pictures / Movies → NKT Media Player Status).
 */
public class StatusActivity extends Activity implements RowAdapter.Listener, Selection.Changed {

    private static final String SAVE_DIR = "NKT Media Player Status";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RowAdapter rows;
    private Selection sel;
    private TextView sub, empty, actionBtn, selCount;
    private View selectBar;
    private final TextView[] tabs = new TextView[2];
    private int tab = 0;                    // 0 statuses, 1 saved
    private final List<MediaInfo> shown = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_list);
        Ui.padForSystemBars(findViewById(R.id.root));
        TextView title = findViewById(R.id.ls_title);
        title.setText(R.string.status_caps);
        title.setTypeface(Ui.orbitron(this));
        sub = findViewById(R.id.ls_sub);
        sub.setText(R.string.status_hint);
        empty = findViewById(R.id.ls_empty);
        actionBtn = findViewById(R.id.ls_action);
        actionBtn.setText(R.string.save_all_caps);
        actionBtn.setTypeface(Ui.orbitron(this));
        actionBtn.setOnClickListener(v -> save(new ArrayList<>(shown)));
        findViewById(R.id.ls_back).setOnClickListener(v -> onBackPressed());

        LinearLayout tabBox = findViewById(R.id.ls_tabs);
        tabBox.setVisibility(View.VISIBLE);
        int[] labels = {R.string.status_new_tab, R.string.status_saved_tab};
        for (int i = 0; i < 2; i++) {
            final int t = i;
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTypeface(Ui.orbitron(this));
            b.setTextSize(10);
            b.setLetterSpacing(0.12f);
            b.setGravity(Gravity.CENTER);
            b.setBackgroundResource(R.drawable.bg_segment);
            int dp = Ui.dp(this, 1);
            b.setPadding(0, 9 * dp, 0, 9 * dp);
            b.setOnClickListener(v -> { tab = t; sel.clear(); load(); });
            tabs[i] = b;
            tabBox.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }

        selectBar = findViewById(R.id.ls_select_bar);
        selCount = findViewById(R.id.ls_sel_count);
        sel = new Selection(this);
        findViewById(R.id.ls_sel_close).setOnClickListener(v -> sel.clear());
        findViewById(R.id.ls_sel_all).setOnClickListener(v -> sel.addAll(shown));
        findViewById(R.id.ls_sel_info).setVisibility(View.GONE);
        ImageButton saveBtn = findViewById(R.id.ls_sel_a), shareBtn = findViewById(R.id.ls_sel_b);
        saveBtn.setImageResource(R.drawable.ic_download);
        saveBtn.setContentDescription(getString(R.string.save_status));
        shareBtn.setImageResource(R.drawable.ic_share);
        shareBtn.setContentDescription(getString(R.string.share));
        saveBtn.setOnClickListener(v -> { List<MediaInfo> l = sel.items(); sel.clear(); save(l); });
        shareBtn.setOnClickListener(v -> { List<MediaInfo> l = sel.items(); sel.clear(); Share.sendMany(this, l); });

        RecyclerView list = findViewById(R.id.ls_list);
        rows = new RowAdapter(this, sel);
        rows.setGrid(true);
        GridLayoutManager glm = new GridLayoutManager(this, 3);
        glm.setSpanSizeLookup(rows.spans(3));
        list.setLayoutManager(glm);
        list.setItemAnimator(null);
        list.setAdapter(rows);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // ── Where WhatsApp keeps them ────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static List<File> statusFolders() {
        File root = Environment.getExternalStorageDirectory();
        String[] paths = {
                "Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
                "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses",
                "WhatsApp/Media/.Statuses",
                "WhatsApp Business/Media/.Statuses"};
        List<File> out = new ArrayList<>();
        for (String p : paths) { File f = new File(root, p); if (f.isDirectory()) out.add(f); }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static List<File> savedFolders() {
        List<File> out = new ArrayList<>();
        out.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), SAVE_DIR));
        out.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SAVE_DIR));
        return out;
    }

    private boolean canRead() {
        return Build.VERSION.SDK_INT < 30 || HiddenScanner.hasAllFilesAccess(this);
    }

    private void load() {
        for (int i = 0; i < 2; i++) {
            tabs[i].setSelected(i == tab);
            tabs[i].setTextColor(getColor(i == tab ? R.color.accent : R.color.muted));
        }
        actionBtn.setVisibility(View.GONE);
        if (!canRead()) {
            List<RowAdapter.Row> out = new ArrayList<>();
            out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.allow), getString(R.string.status_need_access), null, "access", R.drawable.ic_lock));
            shown.clear();
            rows.setRows(out);
            empty.setVisibility(View.GONE);
            return;
        }
        final int which = tab;
        new Thread(() -> {
            List<File> files = new ArrayList<>();
            for (File dir : which == 0 ? statusFolders() : savedFolders()) {
                File[] kids = dir.listFiles();
                if (kids == null) continue;
                for (File f : kids) {
                    int k = MediaInfo.kindOf(f.getName());
                    if (f.isFile() && (k == MediaInfo.IMAGE || k == MediaInfo.VIDEO)) files.add(f);
                }
            }
            Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            List<MediaInfo> media = new ArrayList<>();
            for (File f : files) media.add(MediaInfo.fromFile(f));
            ui.post(() -> {
                if (which != tab) return;
                shown.clear();
                shown.addAll(media);
                List<RowAdapter.Row> out = new ArrayList<>();
                for (MediaInfo m : media)
                    out.add(RowAdapter.Row.media(m, android.text.format.DateUtils.getRelativeTimeSpanString(m.dateMs).toString()));
                rows.setRows(out);
                empty.setText(which == 0 ? R.string.status_none : R.string.status_saved_none);
                empty.setVisibility(media.isEmpty() ? View.VISIBLE : View.GONE);
                actionBtn.setVisibility(which == 0 && !media.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    private void save(List<MediaInfo> items) {
        if (items.isEmpty()) return;
        final Context app = getApplicationContext();
        new Thread(() -> {
            int ok = 0;
            for (MediaInfo m : items) if (saveOne(app, new File(m.path), m.video)) ok++;
            final int n = ok;
            ui.post(() -> {
                Ui.toast(this, n > 0 ? getResources().getQuantityString(R.plurals.statuses_saved, n, n, SAVE_DIR) : getString(R.string.copy_failed, items.get(0).fileName));
                if (tab == 1) load();
            });
        }).start();
    }

    @SuppressWarnings("deprecation")
    private static boolean saveOne(Context c, File src, boolean video) {
        String name = src.getName();
        File dir = new File(Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), SAVE_DIR);
        if (new File(dir, name).exists()) return true;                      // saved before
        if (Build.VERSION.SDK_INT < 29 || HiddenScanner.hasAllFilesAccess(c)) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File to = new File(dir, name);
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) { return false; }
            android.media.MediaScannerConnection.scanFile(c, new String[]{to.getAbsolutePath()}, null, null);
            return true;
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + SAVE_DIR);
        Uri coll = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try {
            Uri u = c.getContentResolver().insert(coll, v);
            if (u == null) return false;
            try (InputStream in = new FileInputStream(src); OutputStream out = c.getContentResolver().openOutputStream(u)) {
                if (out == null) return false;
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) { return false; }
    }

    // ── Clicks ───────────────────────────────────────────────────────────────

    @Override
    public void onClick(RowAdapter.Row r, int position) {
        if (r.type == RowAdapter.ACTION && "access".equals(r.key)) {
            try { startActivity(HiddenScanner.allFilesAccessIntent(this)); } catch (Exception ignored) { }
            return;
        }
        if (r.type != RowAdapter.MEDIA) return;
        if (sel.active()) { sel.toggle(r.media); return; }
        ViewerActivity.open(this, new ArrayList<>(shown), Math.max(0, shown.indexOf(r.media)));
    }

    @Override
    public void onLongClick(RowAdapter.Row r, int position) {
        if (r.type == RowAdapter.MEDIA) { if (sel.active()) sel.toggle(r.media); else sel.start(r.media); }
    }

    @Override
    public void onMore(RowAdapter.Row r, int position) {
        if (r.type != RowAdapter.MEDIA) return;
        MediaInfo m = r.media;
        String[] opts = tab == 0 ? new String[]{getString(R.string.save_status), getString(R.string.share)}
                : new String[]{getString(R.string.share), getString(R.string.properties)};
        Dialogs.builder(this).setItems(opts, (d, w) -> {
            List<MediaInfo> one = new ArrayList<>();
            one.add(m);
            if (tab == 0) { if (w == 0) save(one); else Share.send(this, m); }
            else { if (w == 0) Share.send(this, m); else FileOps.properties(this, m, null, null, null); }
        }).show();
    }

    @Override
    public void onSelectionChanged() {
        selectBar.setVisibility(sel.active() ? View.VISIBLE : View.GONE);
        selCount.setText(getString(R.string.n_selected, sel.count()));
        rows.notifyDataSetChanged();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (sel.active()) { sel.clear(); return; }
        super.onBackPressed();
    }
}
