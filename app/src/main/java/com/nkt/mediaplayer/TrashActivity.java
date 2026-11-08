package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recycle bin: everything deleted in the app, kept 30 days. Restore puts it back where it was; Delete forever
 * removes it for good. Also lists files in Android's own trash (from phones without "All files access").
 */
public class TrashActivity extends Activity implements RowAdapter.Listener, Selection.Changed {

    private static final int REQ_SYS_RESTORE = 4401, REQ_SYS_DELETE = 4402;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RowAdapter rows;
    private Selection sel;
    private TextView sub, empty, emptyBtn, selCount;
    private View selectBar;
    private final Map<String, Trash.Entry> binByKey = new HashMap<>();
    private final Map<String, Uri> systemByKey = new HashMap<>();
    private List<MediaInfo> pendingSystem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_list);
        Ui.padForSystemBars(findViewById(R.id.root));
        TextView title = findViewById(R.id.ls_title);
        title.setText(R.string.bin_caps);
        title.setTypeface(Ui.orbitron(this));
        sub = findViewById(R.id.ls_sub);
        empty = findViewById(R.id.ls_empty);
        emptyBtn = findViewById(R.id.ls_action);
        emptyBtn.setText(getString(R.string.empty_bin).toUpperCase(java.util.Locale.ROOT));
        emptyBtn.setTypeface(Ui.orbitron(this));
        emptyBtn.setOnClickListener(v -> confirmForever(allItems()));
        findViewById(R.id.ls_back).setOnClickListener(v -> onBackPressed());

        selectBar = findViewById(R.id.ls_select_bar);
        selCount = findViewById(R.id.ls_sel_count);
        sel = new Selection(this);
        findViewById(R.id.ls_sel_close).setOnClickListener(v -> sel.clear());
        findViewById(R.id.ls_sel_all).setOnClickListener(v -> sel.addAll(allItems()));
        findViewById(R.id.ls_sel_info).setVisibility(View.GONE);
        ImageButton restore = findViewById(R.id.ls_sel_a), forever = findViewById(R.id.ls_sel_b);
        restore.setContentDescription(getString(R.string.restore));
        forever.setContentDescription(getString(R.string.delete_forever));
        restore.setOnClickListener(v -> { List<MediaInfo> l = sel.items(); sel.clear(); restore(l); });
        forever.setOnClickListener(v -> confirmForever(sel.items()));

        RecyclerView list = findViewById(R.id.ls_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);
        rows = new RowAdapter(this, sel);
        list.setAdapter(rows);
        load();
    }

    private void load() {
        new Thread(() -> {
            Trash.purgeOld(this);
            List<Trash.Entry> bin = Trash.list(this);
            List<Object[]> system = systemTrash();
            ui.post(() -> show(bin, system));
        }).start();
    }

    private void show(List<Trash.Entry> bin, List<Object[]> system) {
        binByKey.clear();
        systemByKey.clear();
        List<RowAdapter.Row> out = new ArrayList<>();
        long total = 0;
        for (Trash.Entry e : bin) {
            MediaInfo m = e.media();
            binByKey.put(m.key(), e);
            total += e.size;
            String when = DateUtils.getRelativeTimeSpanString(e.deletedAt).toString();
            String line = getString(R.string.bin_line, Formatter.formatShortFileSize(this, e.size), when, (int) e.daysLeft())
                    + " · " + new File(e.original).getParent();
            RowAdapter.Row r = RowAdapter.Row.media(m, line);
            out.add(r);
        }
        for (Object[] s : system) {
            MediaInfo m = (MediaInfo) s[0];
            systemByKey.put(m.key(), m.uri);
            total += m.size;
            out.add(RowAdapter.Row.media(m, getString(R.string.bin_system_line, Formatter.formatShortFileSize(this, m.size), (Integer) s[1])));
        }
        rows.setRows(out);
        int n = out.size();
        sub.setText(n == 0 ? getString(R.string.bin_hint)
                : getString(R.string.bin_total, getResources().getQuantityString(R.plurals.items, n, n), Formatter.formatShortFileSize(this, total))
                + "\n" + getString(R.string.bin_hint));
        empty.setText(R.string.bin_empty);
        empty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        emptyBtn.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
    }

    /** Android's own trash (Android 11+): [MediaInfo, days left]. */
    private List<Object[]> systemTrash() {
        List<Object[]> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 30) return out;
        Uri coll = MediaStore.Files.getContentUri("external");
        Bundle q = new Bundle();
        q.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY);
        String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_EXPIRES, MediaStore.Files.FileColumns.MEDIA_TYPE};
        try (Cursor c = getContentResolver().query(coll, proj, q, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                if (name == null) continue;
                // shown under its real name (Android keeps it as .trashed-…-name)
                if (name.startsWith(".trashed-")) { int i = name.indexOf('-', 9); if (i > 0) name = name.substring(i + 1); }
                int type = c.getInt(4);
                Uri base = type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : type == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : coll;
                MediaInfo m = new MediaInfo(id, ContentUris.withAppendedId(base, id), MediaInfo.stripExt(name), "", "", -1, "", 0,
                        type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
                m.image = type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
                m.other = type == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE;
                m.fileName = name;
                m.size = c.getLong(2);
                long expires = c.getLong(3) * 1000L;
                int days = (int) Math.max(0, (expires - System.currentTimeMillis() + 86_399_999L) / 86_400_000L);
                out.add(new Object[]{m, days});
            }
        } catch (Exception ignored) { }
        return out;
    }

    private List<MediaInfo> allItems() {
        List<MediaInfo> out = new ArrayList<>();
        for (RowAdapter.Row r : rows.rows()) if (r.type == RowAdapter.MEDIA) out.add(r.media);
        return out;
    }

    // ── Restore & delete forever ─────────────────────────────────────────────

    private void restore(List<MediaInfo> items) {
        List<Trash.Entry> bin = new ArrayList<>();
        List<Uri> system = new ArrayList<>();
        for (MediaInfo m : items) {
            Trash.Entry e = binByKey.get(m.key());
            if (e != null) bin.add(e);
            Uri u = systemByKey.get(m.key());
            if (u != null) system.add(u);
        }
        if (!system.isEmpty() && Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), system, false);
                startIntentSenderForResult(pi.getIntentSender(), REQ_SYS_RESTORE, null, 0, 0, 0);
            } catch (Exception e) { Ui.toast(this, R.string.restore_failed); }
        }
        if (bin.isEmpty()) return;
        new Thread(() -> {
            int ok = 0;
            String where = null;
            for (Trash.Entry e : bin) {
                File back = Trash.restore(this, e);
                if (back != null) { ok++; where = back.getParent(); }
            }
            final int n = ok;
            final String w = where;
            ui.post(() -> {
                if (n == 0) Ui.toast(this, R.string.restore_failed);
                else if (n == 1 && w != null) Ui.toast(this, getString(R.string.restored_to, FolderPicker.pretty(this, FolderPicker.roots(this), new File(w))));
                else Ui.toast(this, getResources().getQuantityString(R.plurals.restored_n, n, n));
                load();
            });
        }).start();
    }

    private void confirmForever(List<MediaInfo> items) {
        if (items.isEmpty()) return;
        Dialogs.builder(this)
                .setMessage(items.size() == allItems().size() ? R.string.empty_bin_confirm : R.string.delete_forever_confirm)
                .setPositiveButton(R.string.delete_forever, (d, w) -> { sel.clear(); forever(items); })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void forever(List<MediaInfo> items) {
        List<Trash.Entry> bin = new ArrayList<>();
        List<Uri> system = new ArrayList<>();
        for (MediaInfo m : items) {
            Trash.Entry e = binByKey.get(m.key());
            if (e != null) bin.add(e);
            Uri u = systemByKey.get(m.key());
            if (u != null) system.add(u);
        }
        if (!system.isEmpty() && Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), system);
                startIntentSenderForResult(pi.getIntentSender(), REQ_SYS_DELETE, null, 0, 0, 0);
            } catch (Exception ignored) { }
        }
        new Thread(() -> {
            for (Trash.Entry e : bin) Trash.deleteForever(this, e);
            ui.post(this::load);
        }).start();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == REQ_SYS_RESTORE || requestCode == REQ_SYS_DELETE) && resultCode == RESULT_OK) load();
    }

    // ── Clicks ───────────────────────────────────────────────────────────────

    @Override
    public void onClick(RowAdapter.Row r, int position) {
        if (r.type != RowAdapter.MEDIA) return;
        if (sel.active()) { sel.toggle(r.media); return; }
        onMore(r, position);
    }

    @Override
    public void onLongClick(RowAdapter.Row r, int position) {
        if (r.type == RowAdapter.MEDIA) { if (sel.active()) sel.toggle(r.media); else sel.start(r.media); }
    }

    @Override
    public void onMore(RowAdapter.Row r, int position) {
        if (r.type != RowAdapter.MEDIA) return;
        MediaInfo m = r.media;
        boolean inBin = binByKey.containsKey(m.key());
        List<String> opts = new ArrayList<>();
        if (inBin && (m.image || m.video)) opts.add(getString(R.string.open));
        opts.add(getString(R.string.restore));
        opts.add(getString(R.string.delete_forever));
        Dialogs.builder(this).setTitle(m.fileName).setItems(opts.toArray(new String[0]), (d, w) -> {
            String pick = opts.get(w);
            List<MediaInfo> one = new ArrayList<>();
            one.add(m);
            if (pick.equals(getString(R.string.open))) {
                if (m.video) PlayerActivity.open(this, one, 0); else ViewerActivity.open(this, one, 0);
            } else if (pick.equals(getString(R.string.restore))) restore(one);
            else confirmForever(one);
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
