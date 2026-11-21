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
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Listen: the host's shared library. Tapping a song, video or photo plays it here, on this phone only (not in step with the
 * host); BACK TO LIVE on the Listen screen goes back to following the host.
 */
public class ListenLibraryActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String[]> items = new ArrayList<>();     // id, title, artist, seconds, kind, path under the host's storage
    private LinearLayout list;
    private TextView message;
    private EditText search;
    private final TextView[] kindPill = new TextView[4];
    private int kind = 0;          // 0 songs, 1 videos, 2 photos, 3 all files (folders)
    // folders: each kind can show a plain list or its folders; All files is always folders
    private static final boolean[] folderView = {false, false, false, true};
    private static final String[] dirOf = {"", "", "", ""};
    private TextView viewList, viewFolders, pathText;
    private ImageButton upBtn;
    private LinearLayout folderBar, viewRow;
    private static final int PAGE = 300;
    private int drawnFiles = 0;           // the list grows as you scroll: nothing is left out
    private final List<String[]> pending = new ArrayList<>();
    private final List<String[]> shown = new ArrayList<>();      // what the list on screen holds, in order
    private boolean loaded = false;
    private ScrollView sv;
    // thumbnails, picking several, and what to do with them
    private static final android.util.LruCache<String, android.graphics.Bitmap> thumbs = new android.util.LruCache<>(300);
    private static final java.util.concurrent.ExecutorService thumbPool = java.util.concurrent.Executors.newFixedThreadPool(3);
    private final List<View> rowViews = new ArrayList<>();
    private final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
    private boolean selecting = false, lastDl = false;
    private TextView selBtn, shareBtn, saveBtn;
    private LinearLayout selBar;
    // where you were, kept for the next time the library is opened during this listening
    private static long lastStamp = 0;
    private static int lastKind = 0;
    private static String lastSearch = "";
    private static final int[] lastScroll = new int[4];

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (lastStamp != ListenService.startedAt) {        // a new connection: start from the top
            lastStamp = ListenService.startedAt;
            lastKind = 0; lastSearch = ""; java.util.Arrays.fill(lastScroll, 0); java.util.Arrays.fill(dirOf, "");
        }
        kind = lastKind;
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
            @Override public void afterTextChanged(Editable s) { draw(); if (sv != null) sv.scrollTo(0, 0); }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, dp(48));
        sl.topMargin = dp(4);
        root.addView(search, sl);

        LinearLayout pills = new LinearLayout(this);
        String[] names = {getString(R.string.listen_library_songs), getString(R.string.listen_library_videos), getString(R.string.listen_library_photos), getString(R.string.listen_library_all)};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(11);
            t.setLetterSpacing(0.08f);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setOnClickListener(v -> { lastScroll[kind] = sv.getScrollY(); kind = k; paintPills(); draw(); restoreScroll(); });
            kindPill[i] = t;
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (i > 0) pl.setMarginStart(dp(6));
            pills.addView(t, pl);
        }
        LinearLayout.LayoutParams pll = new LinearLayout.LayoutParams(MATCH, WRAP);
        pll.topMargin = dp(12);
        root.addView(pills, pll);

        // LIST / FOLDERS, and the folder you are in
        viewRow = new LinearLayout(this);
        viewList = segText(getString(R.string.listen_view_list), v -> setFolderView(false));
        viewFolders = segText(getString(R.string.listen_view_folders), v -> setFolderView(true));
        viewRow.addView(viewList, new LinearLayout.LayoutParams(WRAP, dp(36)));
        LinearLayout.LayoutParams vfl = new LinearLayout.LayoutParams(WRAP, dp(36));
        vfl.setMarginStart(dp(6));
        viewRow.addView(viewFolders, vfl);
        LinearLayout.LayoutParams vrl = new LinearLayout.LayoutParams(MATCH, WRAP);
        vrl.topMargin = dp(8);
        root.addView(viewRow, vrl);
        folderBar = new LinearLayout(this);
        folderBar.setGravity(Gravity.CENTER_VERTICAL);
        upBtn = new ImageButton(this);
        upBtn.setBackgroundResource(R.drawable.bg_round_btn);
        upBtn.setImageResource(R.drawable.ic_arrow_up);
        upBtn.setColorFilter(getColor(R.color.accent));
        upBtn.setContentDescription(getString(R.string.stream_up));
        upBtn.setOnClickListener(v -> goUp());
        folderBar.addView(upBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        pathText = text("", 13, R.color.muted, false);
        pathText.setSingleLine(true);
        pathText.setEllipsize(android.text.TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams ptl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        ptl.setMarginStart(dp(10));
        folderBar.addView(pathText, ptl);
        LinearLayout.LayoutParams fbl = new LinearLayout.LayoutParams(MATCH, WRAP);
        fbl.topMargin = dp(8);
        root.addView(folderBar, fbl);
        paintPills();

        message = text(getString(R.string.listen_library_loading), 13, R.color.muted, false);
        message.setPadding(0, dp(18), 0, 0);
        root.addView(message, new LinearLayout.LayoutParams(MATCH, WRAP));

        sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new ViewGroup.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        svl.topMargin = dp(6);
        root.addView(sv, svl);

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
        sv.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (drawnFiles < pending.size() && y + sv.getHeight() > list.getHeight() - dp(1200)) drawMore();
            loadVisible();
        });

        load();
    }

    private void load() {
        new Thread(() -> {
            String body = ListenService.fetch("/api/library", 20000);
            final List<String[]> got = new ArrayList<>();
            boolean ok = false;
            try {
                if (body != null) {
                    JSONArray a = new JSONObject(body).optJSONArray("items");
                    for (int i = 0; a != null && i < a.length(); i++) {
                        JSONArray x = a.getJSONArray(i);
                        String path = x.optString(7, "");
                        if (path.isEmpty()) path = x.optString(6, "Other") + "/" + x.getString(1);
                        got.add(new String[]{x.getString(0), x.getString(1), x.optString(2), String.valueOf(x.optLong(3)), String.valueOf(x.optInt(4)), path});
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

    private boolean inKind(String[] it) { return kind == 3 || String.valueOf(kind).equals(it[4]); }

    private static String parentOf(String path) { int k = path.lastIndexOf('/'); return k < 0 ? "" : path.substring(0, k); }

    private void draw() {
        list.removeAllViews();
        shown.clear();
        rowViews.clear();
        pending.clear();
        drawnFiles = 0;
        if (!loaded) return;
        String term = search.getText().toString().trim().toLowerCase();
        boolean fv = folderView[kind];
        int n = 0;
        if (fv) {
            String dir = dirOf[kind], pre = dir.isEmpty() ? "" : dir + "/";
            java.util.TreeMap<String, Integer> subs = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String[] it : items) {
                if (!inKind(it)) continue;
                String d = parentOf(it[5]);
                if (d.equals(dir)) {
                    if (term.isEmpty() || (it[1] + " " + it[2]).toLowerCase().contains(term)) pending.add(it);
                    continue;
                }
                if (!dir.isEmpty() && !d.startsWith(pre)) continue;
                String name = d.substring(pre.length()).split("/")[0];
                if (name.isEmpty() || (!term.isEmpty() && !name.toLowerCase().contains(term))) continue;
                Integer c = subs.get(name);
                subs.put(name, c == null ? 1 : c + 1);
            }
            for (java.util.Map.Entry<String, Integer> e : subs.entrySet()) { list.addView(folderRow(e.getKey(), e.getValue())); n++; }
        } else {
            for (String[] it : items) {
                if (!inKind(it)) continue;
                if (!term.isEmpty() && !(it[1] + " " + it[2]).toLowerCase().contains(term)) continue;
                pending.add(it);
            }
        }
        for (String[] it : pending) shown.add(new String[]{it[0], it[1], it[4]});
        n += pending.size();
        drawMore();
        if (n == 0 && message.getVisibility() != View.VISIBLE) {
            TextView none = text(getString(R.string.listen_library_empty), 13, R.color.muted, false);
            none.setPadding(0, dp(18), 0, 0);
            list.addView(none);
        }
        sv.post(this::loadVisible);
    }

    /** Adds the next rows of the list; called again as you scroll near the end, until everything is there. */
    private void drawMore() {
        int end = Math.min(pending.size(), drawnFiles + PAGE);
        for (int i = drawnFiles; i < end; i++) list.addView(row(pending.get(i), i));
        drawnFiles = end;
    }

    private View folderRow(final String name, int count) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(8), dp(6), dp(8));
        r.setBackground(stroked(0xFF111827, 0x1400E5FF, 14));
        android.widget.ImageView ic = new android.widget.ImageView(this);
        ic.setImageResource(R.drawable.ic_folder);
        ic.setColorFilter(getColor(R.color.accent));
        ic.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        ic.setPadding(dp(12), dp(12), dp(12), dp(12));
        ic.setBackground(stroked(0x1A00E5FF, 0x1400E5FF, 8));
        r.addView(ic, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(name, 14, R.color.text, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        col.addView(text(getResources().getQuantityString(R.plurals.stream_items, count, count), 12, R.color.muted, false));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        cl.setMarginStart(dp(12));
        r.addView(col, cl);
        r.setOnClickListener(v -> {
            dirOf[kind] = dirOf[kind].isEmpty() ? name : dirOf[kind] + "/" + name;
            paintPills(); draw(); sv.scrollTo(0, 0);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(6);
        r.setLayoutParams(lp);
        return r;
    }

    private void goUp() {
        String d = dirOf[kind];
        if (d.isEmpty()) return;
        dirOf[kind] = parentOf(d);
        paintPills(); draw(); sv.scrollTo(0, 0);
    }

    private void setFolderView(boolean on) {
        if (kind == 3) return;
        folderView[kind] = on;
        paintPills(); draw(); sv.scrollTo(0, 0);
    }

    private TextView segText(String s, View.OnClickListener l) {
        TextView t = text(s, 11, R.color.muted, true);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setOnClickListener(l);
        return t;
    }

    /** All files: a song asks first, then plays here and carries on through the songs of its folder. */
    private void askSong(final String[] it, final int at) {
        long sec = 0;
        try { sec = Long.parseLong(it[3]); } catch (Exception ignored) { }
        String sub = (it[2].isEmpty() ? "" : it[2] + " · ") + Ui.time(sec * 1000);
        Dialogs.builder(this)
                .setTitle(R.string.listen_play_song_title)
                .setMessage(it[1] + "\n" + sub + "\n\n" + getString(R.string.listen_play_song_msg))
                .setPositiveButton(R.string.listen_play_caps, (d, w) -> playFrom(at))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Plays item {@code at} of the list: a song with the folder's songs, a photo or video with the folder's photos and videos. */
    private void playFrom(int at) {
        String[] it = pending.get(at);
        boolean song = "0".equals(it[4]);
        List<String[]> q = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < pending.size(); i++) {
            String[] x = pending.get(i);
            boolean xs = "0".equals(x[4]);
            if (folderView[kind] && xs != song) continue;
            if (i == at) idx = q.size();
            q.add(new String[]{x[0], x[1], x[4]});
        }
        ListenService.playOwn(this, q, idx);         // previous and next move through this list
        finish();
    }

    private View row(final String[] it, final int at) {
        final String id = it[0];
        final boolean dl = ListenService.dlOn;
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(8), dp(6), dp(8));
        boolean on = selecting && picked.contains(id);
        r.setBackground(stroked(on ? 0xFF10213A : 0xFF111827, on ? getColor(R.color.accent) : 0x1400E5FF, 14));

        final android.widget.ImageView th = new android.widget.ImageView(this);
        th.setBackground(stroked(0xFF0B1222, 0x1400E5FF, 8));
        th.setClipToOutline(true);
        th.setTag(id);
        int ph = "2".equals(it[4]) ? R.drawable.ic_image : "1".equals(it[4]) ? R.drawable.ic_play : R.drawable.ic_music;
        thumbPlaceholder(th, ph);
        android.graphics.Bitmap have = thumbs.get(id);
        if (have != null) thumbShow(th, have);
        r.addView(th, new LinearLayout.LayoutParams(dp(48), dp(48)));
        rowViews.add(r);
        r.setTag(R.id.tag_row_thumb, th);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(it[1], 14, R.color.text, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        long sec = 0;
        try { sec = Long.parseLong(it[3]); } catch (Exception ignored) { }
        String sub = (it[2].isEmpty() ? "" : it[2] + " · ") + ("2".equals(it[4]) ? getString(R.string.listen_library_photo) : Ui.time(sec * 1000));
        TextView sTv = text(sub, 12, R.color.muted, false);
        sTv.setSingleLine(true);
        sTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(sTv);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        cl.setMarginStart(dp(12));
        r.addView(col, cl);

        if (selecting) {                                       // a tick box
            TextView box = text(on ? "✓" : "", 15, R.color.accent, true);
            box.setGravity(Gravity.CENTER);
            box.setBackground(stroked(on ? 0x2200E5FF : Color.TRANSPARENT, getColor(R.color.accent), 6));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(26), dp(26));
            bl.setMarginEnd(dp(10));
            r.addView(box, bl);
        } else if (dl) {                                       // save this one
            ImageButton d = new ImageButton(this);
            d.setBackgroundResource(R.drawable.bg_round_btn);
            d.setImageResource(R.drawable.ic_download);
            d.setColorFilter(getColor(R.color.accent));
            d.setContentDescription(getString(R.string.listen_download_caps));
            d.setOnClickListener(v -> ListenService.download(this, id));
            r.addView(d, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        r.setOnClickListener(v -> {
            if (selecting) { togglePick(id); return; }
            if (kind == 3 && "0".equals(it[4])) { askSong(it, at); return; }
            playFrom(at);
        });
        if (dl) r.setOnLongClickListener(v -> { if (!selecting) startSelect(id); else togglePick(id); return true; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(6);
        r.setLayoutParams(lp);
        return r;
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

    /** Fetches the small pictures of the rows that are on screen (and a screen more), a few at a time. */
    private void loadVisible() {
        if (rowViews.isEmpty() || sv == null) return;
        int top = sv.getScrollY() - sv.getHeight() / 2, bottom = sv.getScrollY() + sv.getHeight() * 3 / 2;
        for (View r : rowViews) {
            if (r.getBottom() < top || r.getTop() > bottom) continue;
            final android.widget.ImageView th = (android.widget.ImageView) r.getTag(R.id.tag_row_thumb);
            if (th == null || th.getTag(R.id.tag_thumb_done) != null) continue;
            final String id = (String) th.getTag();
            th.setTag(R.id.tag_thumb_done, Boolean.TRUE);
            if (thumbs.get(id) != null) continue;
            thumbPool.execute(() -> {
                byte[] b = ListenService.fetchBytes("/th/" + id, 8000);
                final android.graphics.Bitmap bmp = b == null ? null : android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length);
                if (bmp == null) return;
                thumbs.put(id, bmp);
                ui.post(() -> { if (id.equals(th.getTag()) && !isFinishing()) thumbShow(th, bmp); });
            });
        }
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
        int y = sv.getScrollY();
        draw();
        sv.post(() -> sv.scrollTo(0, y));
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
        boolean fv = folderView[kind];
        viewRow.setVisibility(kind == 3 ? View.GONE : View.VISIBLE);
        viewList.setTextColor(getColor(fv ? R.color.muted : R.color.accent));
        viewList.setBackground(fv ? null : stroked(0x2400E5FF, 0x00000000, 18));
        viewFolders.setTextColor(getColor(fv ? R.color.accent : R.color.muted));
        viewFolders.setBackground(fv ? stroked(0x2400E5FF, 0x00000000, 18) : null);
        folderBar.setVisibility(fv ? View.VISIBLE : View.GONE);
        String top = kind == 3 ? getString(R.string.listen_all_files) : kindPill[kind].getText().toString();
        pathText.setText(dirOf[kind].isEmpty() ? top : top + " › " + dirOf[kind].replace("/", " › "));
        upBtn.setAlpha(dirOf[kind].isEmpty() ? 0.35f : 1f);
        upBtn.setEnabled(!dirOf[kind].isEmpty());
        for (int i = 0; i < 4; i++) {
            boolean on = i == kind;
            kindPill[i].setTextColor(on ? getColor(R.color.accent) : getColor(R.color.muted));
            kindPill[i].setBackground(on ? stroked(0x1A00E5FF, getColor(R.color.accent), 20) : stroked(0xFF111827, 0x1F00E5FF, 20));
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

    private void restoreScroll() {
        final int y = lastScroll[kind];
        sv.post(() -> sv.scrollTo(0, y));
    }

    @Override
    protected void onPause() {
        lastKind = kind;
        lastSearch = search.getText().toString();
        if (loaded) lastScroll[kind] = sv.getScrollY();
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
