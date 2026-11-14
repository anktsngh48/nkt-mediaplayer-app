package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stream → Shared with listeners: the "Allow downloads" switch, and what listeners may play on their own:
 * everything in Audio and Videos, or only the files and folders ticked while browsing the phone.
 */
public class SharedLibraryActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newFixedThreadPool(2);

    private LinearLayout browser, list;
    private TextView crumb, summaryTitle, summarySub, allCount, askAccess;
    private View radioAll, radioChoose;
    private File root, dir;
    private int gen = 0;

    private int dp(float v) { return Ui.dp(this, v); }

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = Environment.getExternalStorageDirectory();
        dir = root;

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(getColor(R.color.bg));
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), 0, dp(20), dp(24));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
        Ui.padForSystemBars(sv);

        // top row
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setBackgroundResource(R.drawable.bg_round_btn);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(getColor(R.color.accent));
        back.setContentDescription(getString(R.string.cd_back));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text(getString(R.string.stream_shared_caps), 13, R.color.accent, false);
        title.setTypeface(Ui.orbitron(this));
        title.setLetterSpacing(0.2f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(14));
        top.addView(title, tl);
        col.addView(top, lp(-1, -2, 8));

        col.addView(text(getString(R.string.stream_shared_intro), 12, R.color.muted, false), lp(-1, -2, 18));

        // allow downloads
        LinearLayout dlGroup = group();
        Switch dl = new Switch(this);
        dl.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        dl.setChecked(StreamPrefs.allowDownloads(this));
        dl.setOnCheckedChangeListener((b, on) -> StreamPrefs.setAllowDownloads(this, on));
        dlGroup.addView(row(R.drawable.ic_download, getString(R.string.stream_allow_dl), getString(R.string.stream_allow_dl_sub), dl, dl::toggle));
        col.addView(dlGroup, lp(-1, -2, 16));

        // what to share
        col.addView(label(getString(R.string.stream_what_to_share)), lp(-1, -2, 20));
        LinearLayout modes = group();
        allCount = text("", 12, R.color.muted, false);
        radioAll = new Mark(this, true);
        radioChoose = new Mark(this, true);
        modes.addView(row(0, getString(R.string.stream_everything), allCount, radioAll, () -> setMode(StreamPrefs.MODE_ALL)));
        modes.addView(row(0, getString(R.string.stream_only_choose), text(getString(R.string.stream_only_choose_sub), 12, R.color.muted, false),
                radioChoose, () -> setMode(StreamPrefs.MODE_CHOOSE)));
        col.addView(modes, lp(-1, -2, 8));

        // browser
        browser = new LinearLayout(this);
        browser.setOrientation(LinearLayout.VERTICAL);
        col.addView(browser, lp(-1, -2, 0));
        browser.addView(label(getString(R.string.stream_browse_caps)), lp(-1, -2, 20));

        askAccess = text(getString(R.string.stream_need_access), 13, R.color.accent, false);
        askAccess.setBackgroundResource(R.drawable.bg_row);
        askAccess.setPadding(dp(14), dp(14), dp(14), dp(14));
        askAccess.setOnClickListener(v -> askAccess());
        browser.addView(askAccess, lp(-1, -2, 8));

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton up = new ImageButton(this);
        up.setBackgroundResource(R.drawable.bg_round_btn);
        up.setImageResource(R.drawable.ic_arrow_up);
        up.setColorFilter(getColor(R.color.accent));
        up.setContentDescription(getString(R.string.stream_up));
        up.setOnClickListener(v -> goUp());
        nav.addView(up, new LinearLayout.LayoutParams(dp(44), dp(44)));
        crumb = text("", 13, R.color.muted, false);
        crumb.setSingleLine(true);
        crumb.setEllipsize(android.text.TextUtils.TruncateAt.START);
        crumb.setBackground(stroked(0xFF0B1222, 0x1F00E5FF, 10));
        crumb.setGravity(Gravity.CENTER_VERTICAL);
        crumb.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, dp(44), 1f);
        cl.setMarginStart(dp(8));
        nav.addView(crumb, cl);
        browser.addView(nav, lp(-1, -2, 8));

        list = group();
        browser.addView(list, lp(-1, -2, 8));

        // summary
        LinearLayout sum = new LinearLayout(this);
        sum.setGravity(Gravity.CENTER_VERTICAL);
        sum.setBackground(stroked(getColor(R.color.card), 0x4000E5FF, 16));
        sum.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.VERTICAL);
        summaryTitle = text("", 14, R.color.text, true);
        summarySub = text("", 12, R.color.muted, false);
        st.addView(summaryTitle);
        st.addView(summarySub, lp(-1, -2, 2));
        sum.addView(st, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = new TextView(this);
        done.setText(R.string.stream_done_caps);
        done.setGravity(Gravity.CENTER);
        done.setTextSize(12);
        done.setLetterSpacing(0.14f);
        done.setTypeface(null, android.graphics.Typeface.BOLD);
        done.setTextColor(getColor(R.color.bg));
        done.setBackgroundResource(R.drawable.bg_start_btn);
        done.setPadding(dp(24), 0, dp(24), 0);
        done.setOnClickListener(v -> finish());
        sum.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        col.addView(sum, lp(-1, -2, 12));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    // ── Access (browsing the phone needs "All files access") ────────────────

    private boolean canBrowse() {
        if (Build.VERSION.SDK_INT >= 30) return HiddenScanner.hasAllFilesAccess(this);
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            Dialogs.builder(this)
                    .setTitle(R.string.share_access_title)
                    .setMessage(R.string.stream_access_text)
                    .setPositiveButton(R.string.allow, (d, w) -> {
                        try { startActivity(HiddenScanner.allFilesAccessIntent(this)); } catch (Exception ignored) { }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 41);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refresh();
    }

    // ── Mode ─────────────────────────────────────────────────────────────────

    private void setMode(String mode) {
        StreamPrefs.setShareMode(this, mode);
        StreamShare.invalidate();
        refresh();
    }

    private boolean choose() { return StreamPrefs.MODE_CHOOSE.equals(StreamPrefs.shareMode(this)); }

    private void refresh() {
        boolean ch = choose();
        ((Mark) radioAll).set(ch ? 0 : 2);
        ((Mark) radioChoose).set(ch ? 2 : 0);
        allCount.setText(R.string.stream_counting);
        bg.execute(() -> {
            int n = StreamShare.countAll(this);
            ui.post(() -> allCount.setText(getResources().getQuantityString(R.plurals.stream_items, n, n)));
        });
        boolean can = canBrowse();
        askAccess.setVisibility(can ? View.GONE : View.VISIBLE);
        browser.setAlpha(ch ? 1f : 0.45f);
        draw();
        summary();
    }

    // ── The browser ──────────────────────────────────────────────────────────

    private void goUp() {
        if (!choose()) return;
        File p = dir.getParentFile();
        if (dir.equals(root) || p == null) return;
        dir = p;
        draw();
    }

    private String crumbText() {
        StringBuilder b = new StringBuilder(getString(R.string.stream_phone));
        String rel = dir.getAbsolutePath().substring(root.getAbsolutePath().length());
        if (!rel.isEmpty()) for (String part : rel.split("/")) if (!part.isEmpty()) b.append("  ›  ").append(part);
        return b.toString();
    }

    private void draw() {
        final int mine = ++gen;
        crumb.setText(crumbText());
        list.removeAllViews();
        if (!canBrowse()) return;
        boolean hidden = Prefs.showHidden(this);
        File[] kids = dir.listFiles();
        List<File> dirs = new ArrayList<>(), files = new ArrayList<>();
        if (kids != null) {
            for (File k : kids) {
                String n = k.getName();
                if (n.startsWith(".") && !hidden) continue;
                if (k.isDirectory()) { if (!StreamShare.isHiddenSystem(k)) dirs.add(k); }
                else {
                    int kind = MediaInfo.kindOf(n);
                    if (kind == MediaInfo.AUDIO || kind == MediaInfo.VIDEO) files.add(k);
                }
            }
        }
        Collections.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        Collections.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        Set<String> picks = StreamPrefs.picks(this);
        boolean on = choose();

        // select all in this folder
        int ds = StreamShare.state(picks, dir.getAbsolutePath());
        LinearLayout all = new LinearLayout(this);
        all.setGravity(Gravity.CENTER_VERTICAL);
        all.setMinimumHeight(dp(52));
        all.setPadding(dp(10), 0, dp(10), 0);
        Mark am = new Mark(this, false);
        am.set(ds);
        all.addView(am, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView at = text(getString(R.string.stream_select_all), 13, R.color.muted, false);
        LinearLayout.LayoutParams atl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        atl.setMarginStart(dp(14));
        all.addView(at, atl);
        TextView ac = text(getString(R.string.stream_n_of, countSelected(picks, dirs, files), dirs.size() + files.size()), 12, R.color.muted, false);
        all.addView(ac);
        if (on) all.setOnClickListener(v -> { StreamShare.toggle(this, dir); draw(); summary(); });
        list.addView(all);
        list.addView(divider());

        for (File d : dirs) list.addView(folderRow(d, picks, on, mine));
        if (!dirs.isEmpty() && !files.isEmpty()) list.addView(divider());
        for (File f : files) list.addView(fileRow(f, picks, on));
        if (dirs.isEmpty() && files.isEmpty()) {
            TextView none = text(getString(R.string.stream_empty_folder), 13, R.color.muted, false);
            none.setPadding(dp(12), dp(16), dp(12), dp(16));
            list.addView(none);
        }
    }

    private int countSelected(Set<String> picks, List<File> dirs, List<File> files) {
        int n = 0;
        for (File d : dirs) if (StreamShare.state(picks, d.getAbsolutePath()) == 2) n++;
        for (File f : files) if (StreamShare.state(picks, f.getAbsolutePath()) == 2) n++;
        return n;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x1F00E5FF);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        p.setMargins(dp(10), dp(2), dp(10), dp(2));
        v.setLayoutParams(p);
        return v;
    }

    private View folderRow(File d, Set<String> picks, boolean on, int mine) {
        int st = StreamShare.state(picks, d.getAbsolutePath());
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(56));
        r.setPadding(dp(10), 0, dp(4), 0);
        Mark m = new Mark(this, false);
        m.set(st);
        if (on) m.setOnClickListener(v -> { StreamShare.toggle(this, d); draw(); summary(); });
        r.addView(m, new LinearLayout.LayoutParams(dp(22), dp(22)));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_folder);
        boolean hid = d.getName().startsWith(".");
        ic.setColorFilter(getColor(hid ? R.color.accent2 : R.color.accent));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(22), dp(22));
        il.setMarginStart(dp(14));
        r.addView(ic, il);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView nm = text(d.getName(), 14, R.color.text, false);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameRow.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (hid) {
            TextView tag = text(getString(R.string.stream_hidden_tag), 9, R.color.accent2, true);
            tag.setLetterSpacing(0.12f);
            tag.setBackground(stroked(Color.TRANSPARENT, 0x997B61FF, 10));
            tag.setPadding(dp(8), dp(2), dp(8), dp(2));
            LinearLayout.LayoutParams tg = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tg.setMarginStart(dp(8));
            nameRow.addView(tag, tg);
        }
        t.addView(nameRow);
        TextView sub = text(getString(st == 2 ? R.string.stream_whole_folder : st == 1 ? R.string.stream_some_items : R.string.stream_not_shared), 12, R.color.muted, false);
        t.addView(sub, lp(-1, -2, 2));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(14));
        r.addView(t, tl);
        ImageButton open = new ImageButton(this);
        open.setBackgroundColor(Color.TRANSPARENT);
        open.setImageResource(R.drawable.ic_chevron_right);
        open.setColorFilter(getColor(R.color.muted));
        open.setContentDescription(d.getName());
        open.setOnClickListener(v -> { if (!on) return; dir = d; draw(); });
        r.addView(open, new LinearLayout.LayoutParams(dp(44), dp(44)));
        final String base = getString(st == 2 ? R.string.stream_whole_folder : st == 1 ? R.string.stream_some_items : R.string.stream_not_shared);
        bg.execute(() -> {                                   // how many songs and videos are in it
            int n = countMedia(d, 0, Prefs.showHidden(this));
            ui.post(() -> { if (mine == gen) sub.setText(base + " · " + (n >= 2000 ? "2000+" : String.valueOf(n)) + " " + getString(R.string.stream_items_word)); });
        });
        return r;
    }

    private int countMedia(File d, int depth, boolean hidden) {
        int n = 0;
        File[] kids = d.listFiles();
        if (kids == null || depth > 8) return 0;
        for (File k : kids) {
            if (n >= 2000) break;
            if (k.getName().startsWith(".") && !hidden) continue;
            if (k.isDirectory()) n += countMedia(k, depth + 1, hidden);
            else {
                int kind = MediaInfo.kindOf(k.getName());
                if (kind == MediaInfo.AUDIO || kind == MediaInfo.VIDEO) n++;
            }
        }
        return n;
    }

    private View fileRow(File f, Set<String> picks, boolean on) {
        int st = StreamShare.state(picks, f.getAbsolutePath());
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(56));
        r.setPadding(dp(10), 0, dp(10), 0);
        Mark m = new Mark(this, false);
        m.set(st);
        r.addView(m, new LinearLayout.LayoutParams(dp(22), dp(22)));
        ImageView ic = new ImageView(this);
        boolean video = MediaInfo.kindOf(f.getName()) == MediaInfo.VIDEO;
        ic.setImageResource(video ? R.drawable.ic_movie : R.drawable.ic_music);
        ic.setColorFilter(getColor(R.color.muted));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(22), dp(22));
        il.setMarginStart(dp(14));
        r.addView(ic, il);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView nm = text(MediaInfo.stripExt(f.getName()), 14, R.color.text, false);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(nm);
        t.addView(text(Formatter.formatShortFileSize(this, f.length()), 12, R.color.muted, false), lp(-1, -2, 2));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(14));
        r.addView(t, tl);
        if (on) r.setOnClickListener(v -> { StreamShare.toggle(this, f); draw(); summary(); });
        return r;
    }

    private void summary() {
        Set<String> picks = StreamPrefs.picks(this);
        int folders = 0, files = 0;
        for (String p : picks) { if (new File(p).isDirectory()) folders++; else files++; }
        if (!choose()) {
            summaryTitle.setText(R.string.stream_everything);
            summarySub.setText("");
        } else {
            summaryTitle.setText(getString(R.string.stream_selected, getResources().getQuantityString(R.plurals.stream_folders, folders, folders),
                    getResources().getQuantityString(R.plurals.stream_files, files, files)));
            summarySub.setText(R.string.stream_counting);
            bg.execute(() -> {
                int n = StreamShare.items(this).size();
                ui.post(() -> summarySub.setText(getString(R.string.stream_about_shared, n)));
            });
        }
    }

    // ── Small view helpers ───────────────────────────────────────────────────

    private LinearLayout.LayoutParams lp(int w, int h, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h > 0 ? dp(h) : h);
        p.topMargin = dp(topDp);
        return p;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 10, R.color.muted, false);
        t.setLetterSpacing(0.18f);
        return t;
    }

    private android.graphics.drawable.GradientDrawable stroked(int fill, int stroke, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout group() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackgroundResource(R.drawable.bg_options_bar);
        g.setPadding(dp(6), dp(6), dp(6), dp(6));
        return g;
    }

    private View row(int iconRes, String title, Object sub, View end, Runnable tap) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(60));
        r.setPadding(dp(10), dp(6), dp(10), dp(6));
        if (iconRes != 0) {
            ImageView ic = new ImageView(this);
            ic.setImageResource(iconRes);
            ic.setColorFilter(getColor(R.color.accent));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(22), dp(22));
            il.setMarginEnd(dp(14));
            r.addView(ic, il);
        }
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(title, 14, R.color.text, false));
        TextView s = sub instanceof TextView ? (TextView) sub : text(String.valueOf(sub), 12, R.color.muted, false);
        t.addView(s, lp(-1, -2, 2));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginEnd(dp(10));
        r.addView(t, tl);
        r.addView(end, end instanceof Mark ? new LinearLayout.LayoutParams(dp(22), dp(22)) : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.setOnClickListener(v -> tap.run());
        return r;
    }

    /** A round radio mark or a square tick box: 0 empty, 1 some (a dash), 2 ticked. */
    private static final class Mark extends View {
        private final boolean round;
        private int state = 0;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Mark(Context c, boolean round) { super(c); this.round = round; }

        void set(int s) { state = s; invalidate(); }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            float inset = 1f * d;
            RectF r = new RectF(inset, inset, w - inset, h - inset);
            int acc = 0xFF00E5FF, mut = 0xFF8A9AB2;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * d);
            if (round) {
                p.setColor(state == 2 ? acc : mut);
                cv.drawCircle(w / 2, h / 2, w / 2 - inset, p);
                if (state == 2) { p.setStyle(Paint.Style.FILL); p.setColor(acc); cv.drawCircle(w / 2, h / 2, 5f * d, p); }
                return;
            }
            float rad = 6f * d;
            if (state == 2) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(acc);
                cv.drawRoundRect(r, rad, rad, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setStrokeWidth(2.4f * d);
                p.setColor(0xFF05080F);
                path.reset();
                path.moveTo(w * 0.24f, h * 0.52f);
                path.lineTo(w * 0.43f, h * 0.70f);
                path.lineTo(w * 0.77f, h * 0.30f);
                cv.drawPath(path, p);
            } else {
                p.setColor(state == 1 ? acc : mut);
                cv.drawRoundRect(r, rad, rad, p);
                if (state == 1) {
                    p.setStyle(Paint.Style.FILL);
                    cv.drawRect(w * 0.28f, h / 2 - d, w * 0.72f, h / 2 + d, p);
                }
            }
        }
    }
}
