package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Listen: the host's shared library. Tapping a song, video or photo plays it here, on this phone only (not in step with the
 * host); BACK TO LIVE on the Listen screen goes back to following the host. Every item is listed (a scrolling list that
 * only builds the rows on screen), as one list or by folder.
 */
public class ListenLibraryActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String[]> items = new ArrayList<>();     // id, title, artist, seconds, kind, size, folder
    private TextView message;
    private EditText search;
    private final TextView[] kindPill = new TextView[3];
    private final TextView[] modePill = new TextView[2];
    private int kind = 0;          // 0 songs, 1 videos, 2 photos
    private boolean folders = false;
    private String openFolder = null;
    private final List<String[]> shown = new ArrayList<>();      // the items on screen, in order (what previous / next move through)
    private final List<Object[]> rows = new ArrayList<>();       // {"back", name} · {"folder", name, count} · {"item", it, index}
    private boolean loaded = false;
    private RecyclerView list;
    private LinearLayoutManager lm;
    private Adapter adapter;
    // thumbnails, picking several, and what to do with them
    private static final android.util.LruCache<String, android.graphics.Bitmap> thumbs = new android.util.LruCache<>(300);
    private static final java.util.concurrent.ExecutorService thumbPool = java.util.concurrent.Executors.newFixedThreadPool(3);
    private final java.util.Set<String> asking = new java.util.HashSet<>();
    private final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
    private boolean selecting = false, lastDl = false;
    private TextView selBtn, shareBtn, saveBtn;
    private LinearLayout selBar;
    // where you were, kept for the next time the library is opened during this listening
    private static long lastStamp = 0;
    private static int lastKind = 0;
    private static boolean lastFolders = false;
    private static String lastSearch = "";
    private static final String[] lastFolder = new String[3];
    private static final int[] lastScroll = new int[3];

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (lastStamp != ListenService.startedAt) {        // a new connection: start from the top
            lastStamp = ListenService.startedAt;
            lastKind = 0; lastSearch = ""; lastFolders = false;
            java.util.Arrays.fill(lastScroll, 0);
            java.util.Arrays.fill(lastFolder, null);
        }
        kind = lastKind;
        folders = lastFolders;
        openFolder = folders ? lastFolder[kind] : null;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setPadding(dp(20), 0, dp(20), 0);
        setContentView(root);
        Ui.padForSystemBars(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setBackgroundResource(R.drawable.bg_round_btn);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(getColor(R.color.accent));
        back.setContentDescription(getString(R.string.cd_back));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text(getString(R.string.listen_library_title), 16, R.color.text, true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tl.setMarginStart(dp(14));
        top.addView(title, tl);
        selBtn = text(getString(R.string.listen_select_caps), 12, R.color.accent, true);
        selBtn.setLetterSpacing(0.12f);
        selBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        selBtn.setVisibility(View.GONE);
        selBtn.setOnClickListener(v -> { if (selecting) endSelect(); else startSelect(null); });
        top.addView(selBtn);
        root.addView(top, new LinearLayout.LayoutParams(MATCH, dp(64)));

        search = new EditText(this);
        search.setHint(R.string.listen_library_search);
        search.setHintTextColor(getColor(R.color.muted));
        search.setTextColor(getColor(R.color.text));
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(stroked(0xFF111827, 0x1F00E5FF, 14));
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setText(lastSearch);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { draw(); if (lm != null) lm.scrollToPosition(0); }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, dp(48));
        sl.topMargin = dp(4);
        root.addView(search, sl);

        LinearLayout pills = new LinearLayout(this);
        String[] names = {getString(R.string.listen_library_songs), getString(R.string.listen_library_videos), getString(R.string.listen_library_photos)};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView t = pillView(names[i]);
            t.setOnClickListener(v -> {
                rememberPlace();
                kind = k;
                openFolder = folders ? lastFolder[kind] : null;
                paintPills(); draw(); restoreScroll();
            });
            kindPill[i] = t;
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (i > 0) pl.setMarginStart(dp(6));
            pills.addView(t, pl);
        }
        LinearLayout.LayoutParams pll = new LinearLayout.LayoutParams(MATCH, WRAP);
        pll.topMargin = dp(12);
        root.addView(pills, pll);

        // every item in one list, or by folder
        LinearLayout modes = new LinearLayout(this);
        String[] mn = {getString(R.string.listen_library_all), getString(R.string.listen_library_folders)};
        for (int i = 0; i < 2; i++) {
            final boolean f = i == 1;
            TextView t = pillView(mn[i]);
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(f ? R.drawable.ic_folder : R.drawable.ic_list, 0, 0, 0);
            t.setCompoundDrawablePadding(dp(6));
            t.setPadding(dp(12), 0, dp(14), 0);
            t.setOnClickListener(v -> {
                if (folders == f) { if (f && openFolder != null) { openFolder = null; draw(); lm.scrollToPosition(0); } return; }
                rememberPlace();
                folders = f;
                openFolder = f ? lastFolder[kind] : null;
                paintPills(); draw(); lm.scrollToPosition(0);
            });
            modePill[i] = t;
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(WRAP, dp(34));
            if (i > 0) pl.setMarginStart(dp(6));
            modes.addView(t, pl);
        }
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(MATCH, WRAP);
        ml.topMargin = dp(8);
        root.addView(modes, ml);
        paintPills();

        message = text(getString(R.string.listen_library_loading), 13, R.color.muted, false);
        message.setPadding(0, dp(18), 0, 0);
        root.addView(message, new LinearLayout.LayoutParams(MATCH, WRAP));

        list = new RecyclerView(this);
        lm = new LinearLayoutManager(this);
        list.setLayoutManager(lm);
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(12));
        adapter = new Adapter();
        list.setAdapter(adapter);
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        svl.topMargin = dp(6);
        root.addView(list, svl);

        selBar = new LinearLayout(this);
        selBar.setPadding(0, dp(8), 0, dp(8));
        shareBtn = pickBtn(getString(R.string.listen_share_caps), v -> shareSelected());
        saveBtn = pickBtn(getString(R.string.listen_save_caps), v -> saveSelected());
        LinearLayout.LayoutParams sb1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        LinearLayout.LayoutParams sb2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        sb2.setMarginStart(dp(8));
        selBar.addView(shareBtn, sb1);
        selBar.addView(saveBtn, sb2);
        selBar.setVisibility(View.GONE);
        root.addView(selBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        load();
    }

    private TextView pillView(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(11);
        t.setLetterSpacing(0.08f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private void load() {
        new Thread(() -> {
            String body = ListenService.fetch("/api/library", 30000);
            final List<String[]> got = new ArrayList<>();
            boolean ok = false;
            try {
                if (body != null) {
                    JSONArray a = new JSONObject(body).optJSONArray("items");
                    for (int i = 0; a != null && i < a.length(); i++) {
                        JSONArray x = a.getJSONArray(i);
                        String folder = x.optString(6, "");
                        got.add(new String[]{x.getString(0), x.getString(1), x.optString(2), String.valueOf(x.optLong(3)), String.valueOf(x.optInt(4)),
                                String.valueOf(x.optLong(5)), folder.isEmpty() ? "Other" : folder});
                    }
                    ok = true;
                }
            } catch (Throwable ignored) { }
            final boolean fine = ok;
            ui.post(() -> {
                if (isFinishing()) return;
                loaded = true;
                items.clear();
                items.addAll(got);
                message.setText(fine ? "" : getString(R.string.listen_library_off));
                message.setVisibility(fine ? View.GONE : View.VISIBLE);
                draw();
                restoreScroll();
            });
        }, "nkt-listen-lib").start();
    }

    /** Builds the rows for the kind, the mode (all or folders) and the search; every match is listed. */
    private void draw() {
        shown.clear();
        rows.clear();
        if (!loaded) { adapter.notifyDataSetChanged(); return; }
        String term = search.getText().toString().trim().toLowerCase();
        String k = String.valueOf(kind);
        if (folders && openFolder == null) {
            Map<String, Integer> by = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String[] it : items) {
                if (!k.equals(it[4])) continue;
                Integer n = by.get(it[6]);
                by.put(it[6], n == null ? 1 : n + 1);
            }
            for (Map.Entry<String, Integer> e : by.entrySet()) {
                if (!term.isEmpty() && !e.getKey().toLowerCase().contains(term)) continue;
                rows.add(new Object[]{"folder", e.getKey(), e.getValue()});
            }
        } else {
            if (folders) rows.add(new Object[]{"back", openFolder});
            for (String[] it : items) {
                if (!k.equals(it[4])) continue;
                if (folders && !openFolder.equalsIgnoreCase(it[6])) continue;
                if (!term.isEmpty() && !(it[1] + " " + it[2]).toLowerCase().contains(term)) continue;
                shown.add(new String[]{it[0], it[1], it[4]});
                rows.add(new Object[]{"item", it, shown.size() - 1});
            }
        }
        boolean any = false;
        for (Object[] r : rows) if (!"back".equals(r[0])) { any = true; break; }
        if (!any && message.getVisibility() != View.VISIBLE) rows.add(new Object[]{"none"});
        adapter.notifyDataSetChanged();
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return rows.size(); }

        @Override public int getItemViewType(int position) {
            String t = (String) rows.get(position)[0];
            return "item".equals(t) ? 0 : "folder".equals(t) ? 1 : "back".equals(t) ? 2 : 3;
        }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View v = type == 3 ? noneView() : rowShell();
            return new RecyclerView.ViewHolder(v) { };
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            Object[] r = rows.get(position);
            String t = (String) r[0];
            if ("item".equals(t)) bindItem((LinearLayout) h.itemView, (String[]) r[1], (Integer) r[2]);
            else if ("folder".equals(t)) bindFolder((LinearLayout) h.itemView, (String) r[1], (Integer) r[2]);
            else if ("back".equals(t)) bindBack((LinearLayout) h.itemView, (String) r[1]);
        }
    }

    private View noneView() {
        TextView none = text(getString(R.string.listen_library_empty), 13, R.color.muted, false);
        none.setPadding(0, dp(18), 0, 0);
        none.setLayoutParams(new RecyclerView.LayoutParams(MATCH, WRAP));
        return none;
    }

    /** One row: picture · two lines · (tick box or download button). Filled in by the bind methods. */
    private LinearLayout rowShell() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(8), dp(6), dp(8));
        r.setMinimumHeight(dp(64));
        final android.widget.ImageView th = new android.widget.ImageView(this);
        th.setBackground(stroked(0xFF0B1222, 0x1400E5FF, 8));
        th.setClipToOutline(true);
        r.addView(th, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView a = text("", 14, R.color.text, true);
        a.setSingleLine(true);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(a);
        TextView b = text("", 12, R.color.muted, false);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(b);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        cl.setMarginStart(dp(12));
        r.addView(col, cl);
        FrameLayoutEnd end = new FrameLayoutEnd(this);
        r.addView(end, new LinearLayout.LayoutParams(WRAP, WRAP));
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(6);
        r.setLayoutParams(lp);
        return r;
    }

    /** The place at the end of a row for the tick box or the download button. */
    private static final class FrameLayoutEnd extends android.widget.FrameLayout {
        FrameLayoutEnd(android.content.Context c) { super(c); }
    }

    private android.widget.ImageView thumbOf(LinearLayout r) { return (android.widget.ImageView) r.getChildAt(0); }
    private TextView line(LinearLayout r, int i) { return (TextView) ((LinearLayout) r.getChildAt(1)).getChildAt(i); }
    private FrameLayoutEnd endOf(LinearLayout r) { return (FrameLayoutEnd) r.getChildAt(2); }

    private void bindFolder(LinearLayout r, String name, int count) {
        r.setBackground(stroked(0xFF111827, 0x1400E5FF, 14));
        android.widget.ImageView th = thumbOf(r);
        th.setTag(null);
        thumbPlaceholder(th, R.drawable.ic_folder);
        th.setColorFilter(getColor(R.color.accent));
        line(r, 0).setText(name);
        int what = kind == 2 ? R.plurals.items : kind == 1 ? R.plurals.videos : R.plurals.songs;
        line(r, 1).setText(getResources().getQuantityString(what, count, count));
        endOf(r).removeAllViews();
        r.setOnClickListener(v -> {
            lastScroll[kind] = lm.findFirstVisibleItemPosition();
            openFolder = name;
            draw();
            lm.scrollToPosition(0);
        });
        r.setOnLongClickListener(null);
        r.setLongClickable(false);
    }

    private void bindBack(LinearLayout r, String name) {
        r.setBackground(stroked(0xFF111827, 0x1F00E5FF, 14));
        android.widget.ImageView th = thumbOf(r);
        th.setTag(null);
        thumbPlaceholder(th, R.drawable.ic_back);
        th.setColorFilter(getColor(R.color.accent));
        line(r, 0).setText(name);
        line(r, 1).setText(getResources().getQuantityString(R.plurals.items, shown.size(), shown.size()));
        endOf(r).removeAllViews();
        r.setOnClickListener(v -> closeFolder());
        r.setOnLongClickListener(null);
        r.setLongClickable(false);
    }

    private void closeFolder() {
        openFolder = null;
        draw();
        final int p = lastScroll[kind];
        list.post(() -> lm.scrollToPositionWithOffset(Math.max(0, p), 0));
    }

    private void bindItem(LinearLayout r, final String[] it, final int at) {
        final String id = it[0];
        final boolean dl = ListenService.dlOn;
        boolean on = selecting && picked.contains(id);
        r.setBackground(stroked(on ? 0xFF10213A : 0xFF111827, on ? getColor(R.color.accent) : 0x1400E5FF, 14));
        final android.widget.ImageView th = thumbOf(r);
        th.setTag(id);
        int ph = "2".equals(it[4]) ? R.drawable.ic_image : "1".equals(it[4]) ? R.drawable.ic_play : R.drawable.ic_music;
        android.graphics.Bitmap have = thumbs.get(id);
        if (have != null) thumbShow(th, have);
        else { thumbPlaceholder(th, ph); fetchThumb(id, th); }
        line(r, 0).setText(it[1]);
        long sec = 0;
        try { sec = Long.parseLong(it[3]); } catch (Exception ignored) { }
        String sub = (it[2].isEmpty() ? "" : it[2] + " · ") + ("2".equals(it[4]) ? getString(R.string.listen_library_photo) : Ui.time(sec * 1000));
        if (!folders) sub = sub + " · " + it[6];
        line(r, 1).setText(sub);
        FrameLayoutEnd end = endOf(r);
        end.removeAllViews();
        if (selecting) {                                       // a tick box
            TextView box = text(on ? "✓" : "", 15, R.color.accent, true);
            box.setGravity(Gravity.CENTER);
            box.setBackground(stroked(on ? 0x2200E5FF : Color.TRANSPARENT, getColor(R.color.accent), 6));
            android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(dp(26), dp(26));
            bl.setMarginEnd(dp(10));
            end.addView(box, bl);
        } else if (dl) {                                       // save this one
            ImageButton d = new ImageButton(this);
            d.setBackgroundResource(R.drawable.bg_round_btn);
            d.setImageResource(R.drawable.ic_download);
            d.setColorFilter(getColor(R.color.accent));
            d.setContentDescription(getString(R.string.listen_download_caps));
            d.setOnClickListener(v -> ListenService.download(this, id));
            end.addView(d, new android.widget.FrameLayout.LayoutParams(dp(44), dp(44)));
        }
        r.setOnClickListener(v -> {
            if (selecting) { togglePick(id); return; }
            ListenService.playOwn(this, new ArrayList<>(shown), at);         // previous and next move through this list
            finish();
        });
        if (dl) r.setOnLongClickListener(v -> { if (!selecting) startSelect(id); else togglePick(id); return true; });
        else { r.setOnLongClickListener(null); r.setLongClickable(false); }
    }

    private void thumbPlaceholder(android.widget.ImageView iv, int res) {
        iv.setImageResource(res);
        iv.setColorFilter(getColor(R.color.muted));
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(dp(12), dp(12), dp(12), dp(12));
    }

    private void thumbShow(android.widget.ImageView iv, android.graphics.Bitmap b) {
        iv.setColorFilter(null);
        iv.setPadding(0, 0, 0, 0);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(b);
    }

    /** Fetches the small picture of a row that has come on screen (a few at a time). */
    private void fetchThumb(final String id, final android.widget.ImageView th) {
        if (!asking.add(id)) return;
        thumbPool.execute(() -> {
            byte[] b = ListenService.fetchBytes("/th/" + id, 8000);
            final android.graphics.Bitmap bmp = b == null ? null : android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length);
            ui.post(() -> {
                asking.remove(id);
                if (bmp == null || isFinishing()) return;
                thumbs.put(id, bmp);
                if (id.equals(th.getTag())) thumbShow(th, bmp);
            });
        });
    }

    // ── Picking several ──

    private TextView pickBtn(String s, View.OnClickListener l) {
        TextView t = text(s, 12, R.color.accent, true);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.12f);
        t.setBackground(stroked(Color.TRANSPARENT, getColor(R.color.accent), 23));
        t.setOnClickListener(l);
        return t;
    }

    private void startSelect(String first) {
        if (!ListenService.dlOn) return;
        selecting = true;
        picked.clear();
        if (first != null) picked.add(first);
        refreshSelect();
    }

    private void endSelect() {
        selecting = false;
        picked.clear();
        refreshSelect();
    }

    private void togglePick(String id) {
        if (!picked.remove(id)) picked.add(id);
        refreshSelect();
    }

    private void refreshSelect() {
        selBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selBtn.setText(getString(selecting ? R.string.listen_cancel_caps : R.string.listen_select_caps));
        selBtn.setVisibility(ListenService.dlOn ? View.VISIBLE : View.GONE);
        boolean any = !picked.isEmpty();
        shareBtn.setText(getString(R.string.listen_share_caps) + (any ? " (" + picked.size() + ")" : ""));
        saveBtn.setText(getString(R.string.listen_save_caps) + (any ? " (" + picked.size() + ")" : ""));
        shareBtn.setAlpha(any ? 1f : 0.35f);
        saveBtn.setAlpha(any ? 1f : 0.35f);
        adapter.notifyDataSetChanged();
    }

    private void saveSelected() {
        if (picked.isEmpty()) return;
        for (String id : new ArrayList<>(picked)) ListenService.download(this, id);
        endSelect();
    }

    private void shareSelected() {
        if (picked.isEmpty()) return;
        final List<String> ids = new ArrayList<>(picked);
        android.widget.Toast.makeText(this, getString(R.string.listen_preparing), android.widget.Toast.LENGTH_SHORT).show();
        final java.io.File dir = new java.io.File(getCacheDir(), "stream_share");
        new Thread(() -> {
            java.io.File[] old = dir.listFiles();
            if (old != null) for (java.io.File f : old) f.delete();
            final ArrayList<android.net.Uri> uris = new ArrayList<>();
            String type = null;
            final ArrayList<android.net.Uri> photoUris = new ArrayList<>();   // photos + videos only (Google Photos takes nothing else)
            String photoType = null;
            for (String id : ids) {
                java.io.File f = ListenService.fetchToCache(this, id, dir);
                if (f == null) continue;
                uris.add(androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", f));
                String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(f.getName());
                String mt = ext == null ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                if (mt == null) mt = "application/octet-stream";
                if (mt.startsWith("image/") || mt.startsWith("video/")) {
                    photoUris.add(uris.get(uris.size() - 1));
                    if (photoType == null) photoType = mt;
                    else if (!photoType.equals(mt)) photoType = photoType.split("/")[0].equals(mt.split("/")[0]) ? photoType.split("/")[0] + "/*" : "*/*";
                }
                if (type == null) type = mt;
                else if (!type.equals(mt)) type = type.split("/")[0].equals(mt.split("/")[0]) ? type.split("/")[0] + "/*" : "*/*";
            }
            final String fin = type == null ? "*/*" : type;
            final String photoFin = photoType == null ? "*/*" : photoType;
            ui.post(() -> {
                if (isFinishing()) return;
                if (uris.isEmpty()) { android.widget.Toast.makeText(this, R.string.listen_dl_failed, android.widget.Toast.LENGTH_LONG).show(); return; }
                Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE).setType(fin);
                if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                android.content.ClipData cd = android.content.ClipData.newRawUri("", uris.get(0));
                for (int i = 1; i < uris.size(); i++) cd.addItem(new android.content.ClipData.Item(uris.get(i)));
                send.setClipData(cd);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, getString(R.string.listen_share_caps));
                // A mixed pick (songs + photos/videos) hides Google Photos from the list, so offer it with just the photos/videos
                boolean pure = fin.startsWith("image/") || fin.startsWith("video/");
                if (!pure && !photoUris.isEmpty()) {
                    try {
                        Intent gp = new Intent(photoUris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE).setType(photoFin);
                        gp.setPackage("com.google.android.apps.photos");
                        if (photoUris.size() == 1) gp.putExtra(Intent.EXTRA_STREAM, photoUris.get(0));
                        else gp.putParcelableArrayListExtra(Intent.EXTRA_STREAM, photoUris);
                        android.content.ClipData pcd = android.content.ClipData.newRawUri("", photoUris.get(0));
                        for (int i = 1; i < photoUris.size(); i++) pcd.addItem(new android.content.ClipData.Item(photoUris.get(i)));
                        gp.setClipData(pcd);
                        gp.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{new android.content.pm.LabeledIntent(gp, "com.google.android.apps.photos", "Google Photos", 0)});
                    } catch (Throwable ignored) { }
                }
                try { startActivity(chooser); } catch (Throwable t) { }
                endSelect();
            });
        }, "nkt-listen-share").start();
    }

    private void paintPills() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == kind;
            kindPill[i].setTextColor(on ? getColor(R.color.accent) : getColor(R.color.muted));
            kindPill[i].setBackground(on ? stroked(0x1A00E5FF, getColor(R.color.accent), 20) : stroked(0xFF111827, 0x1F00E5FF, 20));
        }
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 1) == folders;
            int c = on ? getColor(R.color.accent) : getColor(R.color.muted);
            modePill[i].setTextColor(c);
            modePill[i].setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(c));
            modePill[i].setBackground(on ? stroked(0x1A00E5FF, getColor(R.color.accent), 17) : stroked(0xFF111827, 0x1F00E5FF, 17));
        }
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private android.graphics.drawable.GradientDrawable stroked(int fill, int stroke, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ListenService.running) finish();          // listening ended meanwhile
        lastDl = ListenService.dlOn;
        refreshSelectBar();
        ui.post(watch);
    }

    private void refreshSelectBar() { selBtn.setVisibility(ListenService.dlOn ? View.VISIBLE : View.GONE); }

    private void rememberPlace() {
        if (!loaded) return;
        if (!(folders && openFolder != null)) lastScroll[kind] = lm.findFirstVisibleItemPosition();
        lastFolder[kind] = folders ? openFolder : lastFolder[kind];
    }

    private void restoreScroll() {
        final int p = folders && openFolder != null ? 0 : lastScroll[kind];
        list.post(() -> lm.scrollToPositionWithOffset(Math.max(0, p), 0));
    }

    @Override
    public void onBackPressed() {
        if (folders && openFolder != null) { closeFolder(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        rememberPlace();
        lastKind = kind;
        lastFolders = folders;
        lastSearch = search.getText().toString();
        ui.removeCallbacks(watch);
        super.onPause();
    }

    /** The host switched the library off while this list was open: it closes at once. */
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (!ListenService.running || !ListenService.libOn) { finish(); return; }
            if (ListenService.dlOn != lastDl) { lastDl = ListenService.dlOn; if (!lastDl) selecting = false; picked.clear(); if (loaded) refreshSelect(); else refreshSelectBar(); }
            ui.postDelayed(this, 800);
        }
    };
}
