package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String[] PAGES = {"Songs", "Videos", "Gallery", "Folders", "Playlists", "History"};
    static final int SONGS = 0, VIDEOS = 1, GALLERY = 2, FOLDERS = 3, PLAYLISTS = 4, HISTORY = 5;

    private int page;
    private FrameLayout root, listArea, drawer;
    private LinearLayout block, mini, galleryBar;
    private RecyclerView list;
    private GridLayoutManager glm;
    private Adapter adapter;
    private TextView pageLabel, count, sortChip, miniTitle, miniSub;
    private ImageView miniArt;
    private ImageButton miniPlay;
    private EditText search;
    private LinearLayout viewSeg;
    private String query = "";
    private int galleryFilter; // 0 all, 1 photos, 2 videos
    private String galleryAlbum;
    private Group openGroup;
    private String openPlaylist;
    private boolean openHistory;
    private File dir; // folders page, null = storage list
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final List<Row> rows = new ArrayList<>();
    private String nowUri = "";
    private int spanCount = 1;
    private final Runnable onLibrary = this::rebuild;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        page = Store.num(this, "start_page", SONGS);
        if (page < 0 || page >= PAGES.length) page = SONGS;
        buildUi();
        setContentView(root);
        Ui.edgeToEdge(this, root);
        Library.listen(onLibrary);
        if (!Perms.media(this)) Perms.askMedia(this);
        Library.start(this);
        rebuild();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Library.loaded && Perms.media(this)) Library.refresh(this);
        Media.get(this, c -> { c.addListener(playerListener); updateMini(); });
    }

    @Override
    protected void onStop() {
        super.onStop();
        MediaController c = Media.now();
        if (c != null) c.removeListener(playerListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    @Override
    protected void onDestroy() {
        Library.unlisten(onLibrary);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        Library.refresh(this);
        rebuild();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Actions.onResult(this, req, res);
        rebuild();
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onEvents(@NonNull Player player, @NonNull Player.Events events) { updateMini(); }
    };

    // ------------------------------------------------------------------ UI
    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout column = Ui.col(this);
        root.addView(column, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        listArea = new FrameLayout(this);
        column.addView(listArea, Ui.lpw(Ui.MATCH, 0, 1));

        list = new RecyclerView(this);
        list.setClipToPadding(false);
        glm = new GridLayoutManager(this, 1);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int pos) {
                if (pos < 0 || pos >= rows.size()) return spanCount;
                Row r = rows.get(pos);
                return r.type == Row.SQUARE || r.type == Row.WIDE || r.type == Row.GROUP ? 1 : spanCount;
            }
        });
        list.setLayoutManager(glm);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 16));
        listArea.addView(list, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        // Everything on top collapses while scrolling so only files remain.
        block = Ui.col(this);
        block.setBackgroundColor(Ui.BG);
        block.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 6));
        listArea.addView(block, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));
        block.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            int h = bt - t;
            if (list.getPaddingTop() != h) list.setPadding(list.getPaddingLeft(), h, list.getPaddingRight(), list.getPaddingBottom());
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                float ty = block.getTranslationY() - dy;
                ty = Math.max(-block.getHeight(), Math.min(0, ty));
                if (!rv.canScrollVertically(-1)) ty = 0;
                block.setTranslationY(ty);
            }
        });

        LinearLayout header = Ui.row(this);
        ImageButton menu = Ui.roundBtn(this, "menu", 44, Ui.TEXT, true);
        menu.setOnClickListener(v -> openDrawer());
        header.addView(menu);
        LinearLayout titles = Ui.col(this);
        titles.setPadding(Ui.dp(this, 10), 0, 0, 0);
        TextView app = Ui.label(this, "NKT MP", 19, Ui.ACCENT, 0.06f);
        app.setTypeface(Ui.orbBlack(this));
        Ui.gradientText(app);
        titles.addView(app);
        pageLabel = Ui.label(this, "", 10, Ui.ACCENT, 0.18f);
        titles.addView(pageLabel);
        header.addView(titles, Ui.lpw(0, Ui.WRAP, 1));
        ImageButton share = Ui.roundBtn(this, "pc", 44, Ui.ACCENT, true);
        share.setOnClickListener(v -> startActivity(new Intent(this, NktShareActivity.class)));
        ImageButton link = Ui.roundBtn(this, "link", 44, Ui.ACCENT, true);
        link.setOnClickListener(v -> LinkDialog.show(this));
        header.addView(share);
        header.addView(Ui.space(this, 8, 1));
        header.addView(link);
        block.addView(header);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.MUTED);
        search.setTextSize(14);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setBackground(Ui.rect(this, Ui.CARD, 12, Ui.BORDER, 1));
        search.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(new Icon(this, "search", Ui.MUTED), null, null, null);
        search.setCompoundDrawablePadding(Ui.dp(this, 10));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { query = e.toString().trim().toLowerCase(Locale.ROOT); rebuild(); }
        });
        block.addView(search, Ui.lpm(this, Ui.MATCH, Ui.dp(this, 44), 0, 14, 0, 0));

        galleryBar = Ui.row(this);
        block.addView(galleryBar, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));

        LinearLayout bar = Ui.row(this);
        count = Ui.single(Ui.text(this, "", 12, Ui.MUTED));
        bar.addView(count, Ui.lpw(0, Ui.WRAP, 1));
        sortChip = Ui.label(this, "A–Z", 9.5f, Ui.ACCENT, 0.12f);
        sortChip.setGravity(Gravity.CENTER);
        sortChip.setCompoundDrawablesRelativeWithIntrinsicBounds(scaled("sort", Ui.ACCENT, 14), null, null, null);
        sortChip.setCompoundDrawablePadding(Ui.dp(this, 6));
        sortChip.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        sortChip.setBackground(Ui.rect(this, Ui.FILL, 16, Ui.BORDER, 1));
        sortChip.setOnClickListener(v -> sortSheet());
        bar.addView(sortChip, Ui.lp(Ui.WRAP, Ui.dp(this, 32)));
        viewSeg = Ui.row(this);
        viewSeg.setBackground(Ui.rect(this, 0, 17, Ui.BORDER, 1));
        viewSeg.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
        bar.addView(viewSeg, Ui.lpm(this, Ui.WRAP, Ui.dp(this, 32), 8, 0, 0, 0));
        block.addView(bar, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));

        // pinch to resize grids (videos and gallery)
        ScaleGestureDetector sg = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            float acc = 1f;
            @Override public boolean onScaleBegin(@NonNull ScaleGestureDetector d) { acc = 1f; return true; }
            @Override public boolean onScale(@NonNull ScaleGestureDetector d) {
                acc *= d.getScaleFactor();
                if (acc > 1.25f) { pinch(-1); acc = 1f; } else if (acc < 0.8f) { pinch(1); acc = 1f; }
                return true;
            }
        });
        list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                sg.onTouchEvent(e);
                return e.getPointerCount() > 1 && (page == GALLERY || page == VIDEOS) && view() == 1;
            }
            @Override public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) { sg.onTouchEvent(e); }
        });

        mini = buildMini();
        column.addView(mini, Ui.lpm(this, Ui.MATCH, Ui.dp(this, 68), 10, 6, 10, 10));
        mini.setVisibility(View.GONE);

        drawer = new FrameLayout(this);
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
    }

    private Icon scaled(String n, int color, int dpSize) {
        Icon i = new Icon(this, n, color);
        i.setBounds(0, 0, Ui.dp(this, dpSize), Ui.dp(this, dpSize));
        return i;
    }

    private LinearLayout buildMini() {
        LinearLayout m = Ui.row(this);
        m.setBackground(Ui.rect(this, Ui.CARD, 16, 0x4000E5FF, 1));
        m.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
        miniArt = new ImageView(this);
        miniArt.setBackground(Ui.rect(this, Ui.FILL, 8, 0, 0));
        miniArt.setClipToOutline(true);
        m.addView(miniArt, Ui.lp(Ui.dp(this, 48), Ui.dp(this, 48)));
        LinearLayout t = Ui.col(this);
        t.setPadding(Ui.dp(this, 10), 0, 0, 0);
        miniTitle = Ui.single(Ui.text(this, "", 14, Ui.TEXT));
        miniTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        miniSub = Ui.single(Ui.text(this, "", 12, Ui.MUTED));
        t.addView(miniTitle);
        t.addView(miniSub);
        m.addView(t, Ui.lpw(0, Ui.WRAP, 1));
        miniPlay = Ui.iconBtn(this, "pause", 44, Ui.ACCENT);
        miniPlay.setOnClickListener(v -> { MediaController c = Media.now(); if (c != null) { if (c.isPlaying()) c.pause(); else c.play(); } });
        ImageButton next = Ui.iconBtn(this, "next", 44, Ui.TEXT);
        next.setOnClickListener(v -> { MediaController c = Media.now(); if (c != null) c.seekToNextMediaItem(); });
        ImageButton close = Ui.iconBtn(this, "close", 44, Ui.MUTED);
        close.setOnClickListener(v -> { MediaController c = Media.now(); if (c != null) { c.stop(); c.clearMediaItems(); } updateMini(); });
        m.addView(miniPlay);
        m.addView(next);
        m.addView(close);
        m.setOnClickListener(v -> startActivity(new Intent(this, NowPlayingActivity.class)));
        return m;
    }

    private void updateMini() {
        MediaController c = Media.now();
        if (c == null || c.getMediaItemCount() == 0) { mini.setVisibility(View.GONE); setNow(""); return; }
        mini.setVisibility(View.VISIBLE);
        MediaInfo m = Media.info(c.getCurrentMediaItem());
        if (m == null) return;
        miniTitle.setText(m.title);
        miniSub.setText(m.isVideo() ? "Video · playing in background" : m.artistOrUnknown());
        miniPlay.setImageDrawable(new Icon(this, c.isPlaying() ? "pause" : "play", Ui.ACCENT));
        Thumbs.load(miniArt, m, Ui.dp(this, 96), m.isVideo() ? "video" : "music");
        setNow(m.uri);
    }

    private void setNow(String uri) {
        if (uri.equals(nowUri)) return;
        nowUri = uri;
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ drawer
    private void openDrawer() {
        drawer.removeAllViews();
        View scrim = new View(this);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setOnClickListener(v -> closeDrawer());
        drawer.addView(scrim, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        LinearLayout panel = Ui.col(this);
        GradientDrawable0 bgd = new GradientDrawable0();
        panel.setBackground(bgd.make(this));
        panel.setPadding(Ui.dp(this, 14), root.getPaddingTop() + Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 20) + root.getPaddingBottom());
        panel.setClickable(true);
        TextView t = Ui.label(this, "NKT MP", 24, Ui.ACCENT, 0.06f);
        t.setTypeface(Ui.orbBlack(this));
        Ui.gradientText(t);
        t.setPadding(Ui.dp(this, 8), 0, 0, 0);
        panel.addView(t);
        TextView hint = Ui.text(this, "Tap ⌂ to choose the page the app opens on", 12, Ui.MUTED);
        hint.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), 0, Ui.dp(this, 14));
        panel.addView(hint);
        int start = Store.num(this, "start_page", SONGS);
        int[] counts = {Library.songs.size(), Library.videos.size(), Library.images.size() + Library.videos.size(), -1, Store.playlistNames(this).size(), -1};
        String[] icons = {"music", "video", "image", "folder", "queue", "refresh"};
        for (int i = 0; i < PAGES.length; i++) {
            final int p = i;
            LinearLayout r = Ui.row(this);
            r.setBackground(p == page ? Ui.rect(this, 0x1F00E5FF, 14, 0x6600E5FF, 1) : Ui.rect(this, 0, 14, 0, 0));
            r.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 2), 0);
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(new Icon(this, icons[i], p == page ? Ui.ACCENT : Ui.TEXT));
            r.addView(ic, Ui.lp(Ui.dp(this, 20), Ui.dp(this, 20)));
            TextView name = Ui.text(this, PAGES[i], 15, p == page ? Ui.ACCENT : Ui.TEXT);
            name.setPadding(Ui.dp(this, 14), 0, 0, 0);
            r.addView(name, Ui.lpw(0, Ui.WRAP, 1));
            if (counts[i] >= 0) r.addView(Ui.text(this, String.format(Locale.US, "%,d", counts[i]), 12, Ui.MUTED));
            ImageButton home = Ui.iconBtn(this, "home", 44, p == start ? Ui.SUCCESS : Ui.MUTED);
            home.setOnClickListener(v -> {
                if (p == HISTORY) { Ui.msg(this, "Pick Songs, Videos, Gallery, Folders or Playlists"); return; }
                Store.put(this, "start_page", p);
                Ui.msg(this, "NKT MP will open on " + PAGES[p]);
                openDrawer();
            });
            r.addView(home);
            r.setOnClickListener(v -> { closeDrawer(); goPage(p); });
            panel.addView(r, Ui.lpm(this, Ui.MATCH, Ui.dp(this, 52), 0, 2, 0, 2));
        }
        View div = new View(this);
        div.setBackgroundColor(Ui.BORDER);
        panel.addView(div, Ui.lpm(this, Ui.MATCH, 1, 8, 12, 8, 12));
        addDrawerLink(panel, "eq", "Equalizer & karaoke", null, EqualizerActivity.class);
        addDrawerLink(panel, "devices", "Other devices", "Open files on phones and computers on Wi-Fi", DevicesActivity.class);
        addDrawerLink(panel, "wifi", "NKT Share", "Share files with a computer or other devices on Wi-Fi", NktShareActivity.class);
        addDrawerLink(panel, "settings", "Settings", null, SettingsActivity.class);
        View grow = new View(this);
        panel.addView(grow, Ui.lpw(1, 0, 1));
        TextView ver = Ui.text(this, "NKT MP · Version 1.0", 12, Ui.MUTED);
        ver.setPadding(Ui.dp(this, 14), 0, 0, 0);
        panel.addView(ver);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(Math.min(Ui.dp(this, 310), (int) (getResources().getDisplayMetrics().widthPixels * 0.85)), Ui.MATCH);
        drawer.addView(panel, pp);
        drawer.setVisibility(View.VISIBLE);
        panel.setTranslationX(-pp.width);
        panel.animate().translationX(0).setDuration(200).start();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(200).start();
    }

    /** Small helper for the drawer's rounded right edge. */
    private static final class GradientDrawable0 {
        android.graphics.drawable.GradientDrawable make(Activity a) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(Ui.CARD);
            float r = Ui.dp(a, 24);
            g.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
            g.setStroke(Ui.dp(a, 1), 0x4D00E5FF);
            return g;
        }
    }

    private void addDrawerLink(LinearLayout panel, String icon, String title, String sub, Class<?> cls) {
        LinearLayout r = Ui.row(this);
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        r.setMinimumHeight(Ui.dp(this, 50));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new Icon(this, icon, Ui.ACCENT));
        r.addView(ic, Ui.lp(Ui.dp(this, 20), Ui.dp(this, 20)));
        LinearLayout t = Ui.col(this);
        t.setPadding(Ui.dp(this, 14), 0, 0, 0);
        t.addView(Ui.text(this, title, 15, Ui.TEXT));
        if (sub != null) t.addView(Ui.text(this, sub, 12, Ui.MUTED));
        r.addView(t, Ui.lpw(0, Ui.WRAP, 1));
        r.setBackground(Ui.pressable(Ui.rect(this, 0, 14, 0, 0), Ui.rect(this, 0x1400E5FF, 14, 0, 0)));
        r.setOnClickListener(v -> { closeDrawer(); startActivity(new Intent(this, cls)); });
        panel.addView(r);
    }

    private void closeDrawer() {
        if (drawer.getChildCount() < 2) { drawer.setVisibility(View.GONE); return; }
        View panel = drawer.getChildAt(1);
        drawer.getChildAt(0).animate().alpha(0f).setDuration(180).start();
        panel.animate().translationX(-panel.getWidth()).setDuration(180).withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    private void goPage(int p) {
        page = p;
        openGroup = null;
        openPlaylist = null;
        openHistory = p == HISTORY;
        search.setText("");
        block.setTranslationY(0);
        rebuild();
        list.scrollToPosition(0);
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) { closeDrawer(); return; }
        if (openGroup != null) { openGroup = null; rebuild(); return; }
        if (openPlaylist != null) { openPlaylist = null; rebuild(); return; }
        if (page == FOLDERS && dir != null) { dir = isRoot(dir) ? null : dir.getParentFile(); rebuild(); return; }
        if (!query.isEmpty()) { search.setText(""); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ view switch, sort, pinch
    private int view() { return Store.num(this, "view_" + page, page == GALLERY ? 1 : 0); }

    private int[] viewChoices() {
        if (page == SONGS) return new int[]{0, 1, 2};
        if (page == HISTORY) return new int[]{0};
        return new int[]{0, 1};
    }

    private void buildViewSeg() {
        viewSeg.removeAllViews();
        int[] ch = viewChoices();
        boolean show = ch.length > 1 && openGroup == null && openPlaylist == null;
        viewSeg.setVisibility(show ? View.VISIBLE : View.GONE);
        String[] icons = {"list", "grid", "squares"};
        String[] names = {"List view", "Grid view", "Squares view"};
        for (int v : ch) {
            ImageButton b = new ImageButton(this);
            b.setImageDrawable(new Icon(this, icons[v], v == view() ? Ui.ACCENT : Ui.MUTED));
            b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            b.setPadding(Ui.dp(this, 9), Ui.dp(this, 6), Ui.dp(this, 9), Ui.dp(this, 6));
            b.setBackground(v == view() ? Ui.rect(this, 0x2400E5FF, 15, 0, 0) : Ui.rect(this, 0, 15, 0, 0));
            b.setContentDescription(names[v]);
            b.setOnClickListener(x -> { Store.put(this, "view_" + page, v); rebuild(); list.scrollToPosition(0); });
            viewSeg.addView(b, Ui.lp(Ui.dp(this, 38), Ui.MATCH));
        }
    }

    private void sortSheet() {
        LinearLayout box = Ui.col(this);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.label(this, "Sort & view", 13, Ui.TEXT, 0.2f), Ui.lpw(0, Ui.WRAP, 1));
        head.addView(Ui.text(this, PAGES[page] + " · remembered", 12, Ui.MUTED));
        box.addView(head);
        Dialog[] d = new Dialog[1];
        int[] ch = viewChoices();
        if (ch.length > 1) {
            box.addView(sheetLabel("VIEW"));
            LinearLayout vr = Ui.row(this);
            String[] names = {"List", "Grid", "Squares"};
            for (int v : ch) {
                TextView t = Ui.chip(this, names[v], v == view());
                t.setOnClickListener(x -> { Store.put(this, "view_" + page, v); rebuild(); d[0].dismiss(); });
                vr.addView(t, Ui.lpm(this, 0, Ui.dp(this, 44), 0, 0, 8, 0));
                ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1;
            }
            box.addView(vr);
        }
        box.addView(sheetLabel("SORT BY"));
        String cur = Sorts.current(this, page);
        String[] opts = Sorts.options(page);
        LinearLayout r = null;
        for (int i = 0; i < opts.length; i++) {
            if (i % 2 == 0) { r = Ui.row(this); box.addView(r, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 0, 6)); }
            String o = opts[i];
            TextView t = Ui.chip(this, (o.equals(cur) ? "●  " : "○  ") + o, o.equals(cur));
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setOnClickListener(x -> { Store.put(this, "sort_" + page, o); rebuild(); list.scrollToPosition(0); d[0].dismiss(); });
            r.addView(t, Ui.lpm(this, 0, Ui.dp(this, 46), 0, 0, i % 2 == 0 ? 6 : 0, 0));
            ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1;
        }
        if (opts.length % 2 == 1 && r != null) r.addView(new View(this), Ui.lpw(0, 1, 1));
        d[0] = Ui.sheet(this, box);
        d[0].show();
    }

    private TextView sheetLabel(String s) {
        TextView t = Ui.label(this, s, 10, Ui.MUTED, 0.18f);
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 10));
        return t;
    }

    private void pinch(int dir) {
        if (view() != 1) return;
        if (page == GALLERY) {
            int[] steps = {2, 3, 4, 5, 7};
            int cur = Store.num(this, "gallery_cols", 3), i = 0;
            for (int k = 0; k < steps.length; k++) if (steps[k] == cur) i = k;
            i = Math.max(0, Math.min(steps.length - 1, i + dir));
            if (steps[i] == cur) return;
            Store.put(this, "gallery_cols", steps[i]);
            pop(steps[i] + " per row" + (steps[i] >= 5 ? " · by month" : ""));
        } else if (page == VIDEOS) {
            int cur = Store.num(this, "video_cols", 2), n = Math.max(1, Math.min(4, cur + dir));
            if (n == cur) return;
            Store.put(this, "video_cols", n);
            pop(n + " per row");
        } else return;
        int first = glm.findFirstVisibleItemPosition();
        rebuild();
        if (first > 0) list.scrollToPosition(first);
    }

    private TextView popView;

    private void pop(String s) {
        if (popView == null) {
            popView = Ui.label(this, "", 13, Ui.ACCENT, 0.05f);
            popView.setBackground(Ui.rect(this, 0xCC05080F, 24, 0x5900E5FF, 1));
            popView.setPadding(Ui.dp(this, 18), Ui.dp(this, 11), Ui.dp(this, 18), Ui.dp(this, 11));
            listArea.addView(popView, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER));
        }
        popView.setText(s);
        popView.setVisibility(View.VISIBLE);
        popView.removeCallbacks(hidePop);
        popView.postDelayed(hidePop, 900);
    }

    private final Runnable hidePop = () -> { if (popView != null) popView.setVisibility(View.GONE); };

    // ------------------------------------------------------------------ building the list
    private void rebuild() {
        if (adapter == null) return;
        pageLabel.setText(openHistory ? "HISTORY" : PAGES[page].toUpperCase(Locale.ROOT));
        search.setHint(page == SONGS ? "Search songs…" : page == VIDEOS ? "Search videos…" : page == GALLERY ? "Search photos and videos…"
                : page == FOLDERS ? "Search this folder…" : "Search…");
        sortChip.setText(Sorts.chipLabel(Sorts.current(this, page)));
        buildViewSeg();
        buildGalleryBar();
        if (page == FOLDERS) { buildFolders(); return; }
        List<Row> out = new ArrayList<>();
        String status = Library.refreshing ? "  ·  Updating" : "";
        if (!Perms.media(this) && page != PLAYLISTS && page != HISTORY) {
            out.add(Row.perm("Allow access to your music, videos and photos so they show up here.", "ALLOW ACCESS", () -> Perms.askMedia(this)));
            count.setText("");
            show(out, 1);
            return;
        }
        switch (page) {
            case SONGS: buildSongs(out, status); break;
            case VIDEOS: buildVideos(out, status); break;
            case GALLERY: buildGallery(out, status); break;
            case PLAYLISTS: buildPlaylists(out); break;
            default: buildHistory(out);
        }
    }

    private void show(List<Row> out, int span) {
        spanCount = Math.max(1, span);
        glm.setSpanCount(spanCount);
        rows.clear();
        rows.addAll(out);
        adapter.notifyDataSetChanged();
    }

    private List<MediaInfo> filter(List<MediaInfo> src) {
        List<MediaInfo> l = new ArrayList<>();
        for (MediaInfo m : src) {
            if (query.isEmpty() || (m.title + " " + m.artist + " " + m.album + " " + m.folder + " " + m.name).toLowerCase(Locale.ROOT).contains(query)) l.add(m);
        }
        return l;
    }

    private void buildSongs(List<Row> out, String status) {
        List<MediaInfo> l = filter(Library.songs);
        Sorts.media(this, l, Sorts.current(this, SONGS));
        if (openGroup != null) {
            List<MediaInfo> g = openGroup.items;
            long total = 0;
            for (MediaInfo m : g) total += m.duration;
            out.add(Row.back("‹  " + openGroup.name, g.size() + " songs · " + Ui.time(total), () -> { openGroup = null; rebuild(); }));
            out.add(Row.action("play", "Play all", () -> Media.playSongs(this, g, 0, true)));
            out.add(Row.action("shuffle", "Shuffle", () -> { List<MediaInfo> s = new ArrayList<>(g); Collections.shuffle(s); Media.playSongs(this, s, 0, true); }));
            out.add(Row.action("playlist_add", "Save as playlist", () -> Actions.newPlaylist(this, n -> { Store.addTo(this, n, g); Ui.msg(this, "Saved as " + n); })));
            addMediaRows(out, g, false);
            count.setText(g.size() + " songs");
            show(out, 1);
            return;
        }
        count.setText(String.format(Locale.US, "%,d songs%s", l.size(), status));
        int v = view();
        if (l.isEmpty()) { out.add(Row.empty(Library.loaded ? "No songs found" : "Loading…")); show(out, 1); return; }
        if (v == 2 && query.isEmpty()) {
            for (Map.Entry<String, List<Group>> e : Group.build(this, Library.songs).entrySet()) {
                out.add(Row.header(e.getKey()));
                for (Group g : e.getValue()) out.add(Row.group(g, () -> { openGroup = g; rebuild(); list.scrollToPosition(0); }));
            }
            show(out, 2);
        } else if (v == 1) {
            for (int i = 0; i < l.size(); i++) {
                final int idx = i;
                MediaInfo m = l.get(i);
                Row r = Row.square(m, () -> Media.playSongs(this, l, idx, true), () -> Actions.menu(this, m, null, this::rebuild));
                r.title = m.title;
                out.add(r);
            }
            show(out, 3);
        } else {
            addMediaRows(out, l, true);
            show(out, 1);
        }
    }

    private void addMediaRows(List<Row> out, List<MediaInfo> l, boolean menu) {
        for (int i = 0; i < l.size(); i++) {
            final int idx = i;
            MediaInfo m = l.get(i);
            out.add(Row.media(m, () -> open(l, idx), () -> Actions.menu(this, m, openPlaylist, this::rebuild)));
        }
    }

    private void open(List<MediaInfo> l, int idx) {
        MediaInfo m = l.get(idx);
        List<MediaInfo> same = new ArrayList<>();
        int at = 0;
        for (MediaInfo x : l) {
            boolean ok = m.isAudio() ? x.isAudio() : m.isVideo() ? x.isVideo() : (x.isImage() || x.isVideo());
            if (!ok) continue;
            if (x == m) at = same.size();
            same.add(x);
        }
        if (m.isAudio()) Media.playSongs(this, same, at, true);
        else if (m.isVideo()) Media.playVideos(this, same, at);
        else Media.view(this, same, at);
    }

    private void buildVideos(List<Row> out, String status) {
        List<MediaInfo> l = filter(Library.videos);
        Sorts.media(this, l, Sorts.current(this, VIDEOS));
        count.setText(String.format(Locale.US, "%,d videos%s", l.size(), status));
        if (l.isEmpty()) { out.add(Row.empty(Library.loaded ? "No videos found" : "Loading…")); show(out, 1); return; }
        if (view() == 1) {
            int cols = Store.num(this, "video_cols", 2);
            if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) cols++;
            for (int i = 0; i < l.size(); i++) {
                final int idx = i;
                MediaInfo m = l.get(i);
                Row r = Row.wide(m, () -> Media.playVideos(this, l, idx), () -> Actions.menu(this, m, null, this::rebuild));
                r.compact = cols >= 4;
                out.add(r);
            }
            show(out, cols);
        } else {
            addMediaRows(out, l, true);
            show(out, 1);
        }
    }

    private void buildGalleryBar() {
        galleryBar.removeAllViews();
        galleryBar.setVisibility(page == GALLERY ? View.VISIBLE : View.GONE);
        if (page != GALLERY) return;
        String[] names = {"ALL", "PHOTOS", "VIDEOS"};
        for (int i = 0; i < 3; i++) {
            final int f = i;
            TextView t = Ui.chip(this, names[i], galleryFilter == i);
            t.setOnClickListener(v -> { galleryFilter = f; rebuild(); });
            galleryBar.addView(t, Ui.lpm(this, Ui.WRAP, Ui.dp(this, 34), 0, 0, 6, 0));
        }
        TextView album = Ui.chip(this, galleryAlbum == null ? "All albums ▾" : galleryAlbum + " ▾", galleryAlbum != null);
        album.setOnClickListener(v -> pickAlbum());
        galleryBar.addView(album, Ui.lpw(0, Ui.dp(this, 34), 1));
        Ui.single(album);
    }

    private void pickAlbum() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<MediaInfo> all = new ArrayList<>(Library.images);
        all.addAll(Library.videos);
        for (MediaInfo m : all) counts.put(m.folder, counts.containsKey(m.folder) ? counts.get(m.folder) + 1 : 1);
        List<Map.Entry<String, Integer>> es = new ArrayList<>(counts.entrySet());
        Collections.sort(es, (a, b) -> b.getValue() - a.getValue());
        String[] items = new String[es.size() + 1];
        items[0] = "All albums";
        for (int i = 0; i < es.size(); i++) items[i + 1] = es.get(i).getKey() + "  (" + es.get(i).getValue() + ")";
        new AlertDialog.Builder(this).setTitle("Album").setItems(items, (d, w) -> {
            galleryAlbum = w == 0 ? null : es.get(w - 1).getKey();
            rebuild();
        }).show();
    }

    private void buildGallery(List<Row> out, String status) {
        List<MediaInfo> src = new ArrayList<>();
        if (galleryFilter != 2) src.addAll(Library.images);
        if (galleryFilter != 1) src.addAll(Library.videos);
        List<MediaInfo> l = filter(src);
        if (galleryAlbum != null) l.removeIf(m -> !galleryAlbum.equals(m.folder));
        String sort = Sorts.current(this, GALLERY);
        Sorts.media(this, l, sort);
        count.setText(String.format(Locale.US, "%,d items%s", l.size(), status));
        if (l.isEmpty()) { out.add(Row.empty(Library.loaded ? "Nothing here" : "Loading…")); show(out, 1); return; }
        if (view() == 0) { addMediaRows(out, l, true); show(out, 1); return; }
        int cols = Store.num(this, "gallery_cols", 3);
        boolean dates = sort.equals(Sorts.NEW) || sort.equals(Sorts.OLD);
        String last = null;
        for (int i = 0; i < l.size(); i++) {
            final int idx = i;
            MediaInfo m = l.get(i);
            if (dates) {
                String h = cols >= 5 ? month(m.date) : day(m.date);
                if (!h.equals(last)) { out.add(Row.header(h)); last = h; }
            }
            Row r = Row.square(m, () -> Media.view(this, l, idx), () -> Actions.menu(this, m, null, this::rebuild));
            r.thumbPx = cols >= 5 ? 120 : 256;
            r.gap = true;
            out.add(r);
        }
        show(out, cols);
    }

    static String day(long t) {
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis(t);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return "TODAY";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return "YESTERDAY";
        boolean thisYear = Calendar.getInstance().get(Calendar.YEAR) == c.get(Calendar.YEAR);
        return new SimpleDateFormat(thisYear ? "EEE, d MMM" : "d MMM yyyy", Locale.getDefault()).format(new Date(t)).toUpperCase(Locale.ROOT);
    }

    static String month(long t) { return new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(new Date(t)).toUpperCase(Locale.ROOT); }

    private void buildPlaylists(List<Row> out) {
        if (openPlaylist != null) {
            List<MediaInfo> items = Store.playlist(this, openPlaylist);
            String s = Sorts.current(this, PLAYLISTS);
            if (s.equals(Sorts.NEW)) Collections.reverse(items);
            else if (s.equals(Sorts.AZ) || s.equals(Sorts.ZA)) Sorts.media(this, items, s);
            items = filter(items);
            final List<MediaInfo> fi = items;
            out.add(Row.back("‹  " + openPlaylist, items.size() + " items", () -> { openPlaylist = null; rebuild(); }));
            if (!items.isEmpty()) out.add(Row.action("play", "Play all", () -> {
                List<MediaInfo> songs = new ArrayList<>(), vids = new ArrayList<>(), pics = new ArrayList<>();
                for (MediaInfo m : fi) (m.isAudio() ? songs : m.isVideo() ? vids : pics).add(m);
                if (!songs.isEmpty()) Media.playSongs(this, songs, 0, true);
                else if (!vids.isEmpty()) Media.playVideos(this, vids, 0);
                else Media.view(this, pics, 0);
            }));
            addMediaRows(out, items, true);
            if (items.isEmpty()) out.add(Row.empty("This playlist is empty. Use “Add to playlist” on any song, video or photo."));
            count.setText(items.size() + " items");
            show(out, 1);
            return;
        }
        List<String> names = new ArrayList<>(Store.playlistNames(this));
        if (!query.isEmpty()) names.removeIf(n -> !n.toLowerCase(Locale.ROOT).contains(query));
        String s = Sorts.current(this, PLAYLISTS);
        switch (s) {
            case Sorts.ZA: Collections.sort(names, (a, b) -> b.compareToIgnoreCase(a)); break;
            case Sorts.NEW: Collections.sort(names, (a, b) -> Long.compare(Store.created(this, b), Store.created(this, a))); break;
            case Sorts.OLD: Collections.sort(names, (a, b) -> Long.compare(Store.created(this, a), Store.created(this, b))); break;
            case "Most items": Collections.sort(names, (a, b) -> Store.playlist(this, b).size() - Store.playlist(this, a).size()); break;
            case "Fewest items": Collections.sort(names, (a, b) -> Store.playlist(this, a).size() - Store.playlist(this, b).size()); break;
            default: Collections.sort(names, String::compareToIgnoreCase);
        }
        count.setText(names.size() + " playlists");
        List<Object[]> hist = Store.history(this);
        out.add(Row.icon("refresh", "History", Store.bool(this, "history_on", true) ? hist.size() + " items" : "Off",
                () -> goPage(HISTORY), null));
        out.add(Row.icon("plus", "New playlist", null, () -> Actions.newPlaylist(this, n -> rebuild()), null));
        boolean grid = view() == 1;
        for (String n : names) {
            int c = Store.playlist(this, n).size();
            Row r = Row.icon(n.startsWith("♥") ? "heart_filled" : "queue", n, c + (c == 1 ? " item" : " items"),
                    () -> { openPlaylist = n; rebuild(); list.scrollToPosition(0); },
                    () -> new AlertDialog.Builder(this).setTitle("Delete “" + n + "”?").setMessage("The songs themselves stay on your phone.")
                            .setPositiveButton("Delete playlist", (d, w) -> { Store.deletePlaylist(this, n); rebuild(); })
                            .setNegativeButton("Cancel", null).show());
            if (grid) r.type = Row.SQUARE;
            out.add(r);
        }
        show(out, grid ? 2 : 1);
    }

    private void buildHistory(List<Row> out) {
        List<Object[]> h = Store.history(this);
        out.add(Row.back("‹  Playlists", null, () -> goPage(PLAYLISTS)));
        if (!Store.bool(this, "history_on", true)) {
            out.add(Row.empty("History is off. Turn it on in Settings."));
            count.setText("");
            show(out, 1);
            return;
        }
        out.add(Row.icon("delete", "Clear history", null, () -> new AlertDialog.Builder(this).setTitle("Clear history?")
                .setMessage("Clear the list of opened files? The files themselves stay.")
                .setPositiveButton("Clear", (d, w) -> { Store.clearHistory(this); rebuild(); }).setNegativeButton("Cancel", null).show(), null));
        String s = Sorts.current(this, HISTORY);
        if (s.equals(Sorts.OLD)) Collections.reverse(h);
        else if (s.equals(Sorts.AZ)) Collections.sort(h, (a, b) -> ((MediaInfo) a[0]).title.compareToIgnoreCase(((MediaInfo) b[0]).title));
        else if (s.equals(Sorts.ZA)) Collections.sort(h, (a, b) -> ((MediaInfo) b[0]).title.compareToIgnoreCase(((MediaInfo) a[0]).title));
        List<MediaInfo> all = new ArrayList<>();
        for (Object[] o : h) all.add((MediaInfo) o[0]);
        SimpleDateFormat tf = new SimpleDateFormat("h:mm a", Locale.getDefault());
        for (int i = 0; i < h.size(); i++) {
            MediaInfo m = (MediaInfo) h.get(i)[0];
            if (!query.isEmpty() && !m.title.toLowerCase(Locale.ROOT).contains(query)) continue;
            long t = (Long) h.get(i)[1];
            String d = day(t);
            String when = d.equals("TODAY") ? "Today " + tf.format(new Date(t)) : d.equals("YESTERDAY") ? "Yesterday " + tf.format(new Date(t)) : d;
            final int idx = i;
            Row r = Row.media(m, () -> open(all, idx), () -> Actions.menu(this, m, null, this::rebuild));
            r.sub = when + " · " + (m.isAudio() ? "Song · " + m.artistOrUnknown() : m.isVideo() ? "Video" : "Photo");
            out.add(r);
        }
        if (h.isEmpty()) out.add(Row.empty("Nothing opened yet"));
        count.setText(h.size() + " items");
        show(out, 1);
    }

    // ------------------------------------------------------------------ folders (file explorer)
    private static boolean isRoot(File f) {
        for (File r : storageRoots(null)) if (r.equals(f)) return true;
        return false;
    }

    static List<File> storageRoots(Activity a) {
        List<File> out = new ArrayList<>();
        out.add(Environment.getExternalStorageDirectory());
        File[] ext = a == null ? null : a.getExternalFilesDirs(null);
        if (ext != null) for (File f : ext) {
            if (f == null) continue;
            String p = f.getAbsolutePath();
            int i = p.indexOf("/Android/data/");
            if (i > 0) {
                File r = new File(p.substring(0, i));
                if (!out.contains(r)) out.add(r);
            }
        }
        return out;
    }

    private void buildFolders() {
        if (!Perms.allFiles(this)) {
            List<Row> out = new ArrayList<>();
            out.add(Row.perm("Folders shows every file and folder on your phone. Android needs you to allow access to all files first.",
                    "ALLOW ACCESS", () -> Perms.askAllFiles(this, "Folders shows every file and folder on your phone, like a file manager.")));
            count.setText("");
            show(out, 1);
            return;
        }
        final File cur = dir;
        final String q = query, sort = Sorts.current(this, FOLDERS);
        final boolean hidden = Store.bool(this, "show_hidden", false);
        final boolean grid = view() == 1;
        final List<File> roots = storageRoots(this);
        bg.execute(() -> {
            List<Row> out = new ArrayList<>();
            String cnt;
            if (cur == null) {
                for (int i = 0; i < roots.size(); i++) {
                    File r = roots.get(i);
                    String name = i == 0 ? "Phone storage" : "SD card / USB (" + r.getName() + ")";
                    out.add(Row.icon("folder", name, r.getAbsolutePath(), () -> { dir = r; rebuild(); list.scrollToPosition(0); }, null));
                }
                cnt = roots.size() + " storage";
            } else {
                File[] kids = cur.listFiles();
                out.add(Row.back("‹  " + (isRoot(cur) ? "All storage" : cur.getParentFile() != null ? cur.getParentFile().getName() : "Up"),
                        pretty(cur), () -> { dir = isRoot(cur) ? null : cur.getParentFile(); rebuild(); }));
                if (kids == null) {
                    out.add(Row.empty(cur.getName().equals("data") || cur.getName().equals("obb")
                            ? "Android doesn't let any app open this folder on Android 11 and newer." : "This folder can't be opened."));
                    cnt = "Locked";
                } else {
                    List<File> l = new ArrayList<>();
                    int hiddenCount = 0;
                    boolean nomedia = new File(cur, ".nomedia").exists() && !hidden;
                    for (File f : kids) {
                        boolean isHidden = f.getName().startsWith(".") || (f.isDirectory() && new File(f, ".nomedia").exists());
                        if (isHidden && !hidden) { hiddenCount++; continue; }
                        if (!q.isEmpty() && !f.getName().toLowerCase(Locale.ROOT).contains(q)) continue;
                        l.add(f);
                    }
                    Sorts.files(l, sort);
                    int nf = 0, nx = 0;
                    if (hiddenCount > 0) out.add(Row.note(hiddenCount + (hiddenCount == 1 ? " hidden folder or file" : " hidden folders and files")
                            + " not shown. Show them in Settings."));
                    for (File f : l) {
                        boolean d = f.isDirectory();
                        if (d) nf++; else nx++;
                        String sub;
                        if (d) {
                            String[] in = f.list();
                            sub = in == null ? "Locked by Android" : in.length == 0 ? "Empty" : in.length + (in.length == 1 ? " item" : " items");
                        } else {
                            sub = Ui.size(f.length()) + " · " + new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(f.lastModified()));
                        }
                        boolean h = f.getName().startsWith(".");
                        Row r = Row.file(f, (h ? "Hidden · " : "") + sub, () -> {
                            if (f.isDirectory()) { dir = f; rebuild(); list.scrollToPosition(0); }
                            else Media.openFile(this, f, l);
                        }, () -> fileMenu(f));
                        r.hiddenMark = h;
                        if (grid) r.type = Row.SQUARE;
                        out.add(r);
                    }
                    if (l.isEmpty()) out.add(Row.empty(q.isEmpty() ? "This folder is empty" : "Nothing matches"));
                    cnt = nf + (nf == 1 ? " folder · " : " folders · ") + nx + (nx == 1 ? " file" : " files");
                }
            }
            final String fc = cnt;
            runOnUiThread(() -> {
                if (page != FOLDERS || dir != cur) return;
                count.setText(fc);
                show(out, grid && cur != null ? 3 : 1);
            });
        });
    }

    private String pretty(File f) {
        String p = f.getAbsolutePath(), r = Environment.getExternalStorageDirectory().getAbsolutePath();
        return p.startsWith(r) ? "Phone storage" + p.substring(r.length()) : p;
    }

    private void fileMenu(File f) {
        String[] opts = f.isDirectory() ? new String[]{"Open", "Rename", "Delete", "Details"} : new String[]{"Open", "Open with another app", "Share", "Rename", "Delete", "Details"};
        new AlertDialog.Builder(this).setTitle(f.getName()).setItems(opts, (d, w) -> {
            switch (opts[w]) {
                case "Open": if (f.isDirectory()) { dir = f; rebuild(); } else Media.openFile(this, f, Collections.singletonList(f)); break;
                case "Open with another app": Media.openExternal(this, f, MediaInfo.mimeOf(f.getName())); break;
                case "Share": Media.shareFile(this, f); break;
                case "Rename": rename(f); break;
                case "Delete":
                    new AlertDialog.Builder(this).setTitle("Delete “" + f.getName() + "”?").setMessage("This can't be undone.")
                            .setPositiveButton("Delete", (x, y) -> { Ui.msg(this, Actions.deleteFile(this, f) ? "Deleted" : "Couldn't delete"); rebuild(); })
                            .setNegativeButton("Cancel", null).show();
                    break;
                default:
                    MediaInfo m = MediaInfo.fromFile(f);
                    Actions.details(this, m, this::rebuild);
            }
        }).show();
    }

    private void rename(File f) {
        EditText e = new EditText(this);
        e.setText(f.getName());
        e.setTextColor(Ui.TEXT);
        e.setSingleLine(true);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(e);
        new AlertDialog.Builder(this).setTitle("Rename").setView(box).setPositiveButton("Rename", (d, w) -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty() || n.contains("/")) return;
            File to = new File(f.getParentFile(), n);
            Ui.msg(this, f.renameTo(to) ? "Renamed" : "Couldn't rename");
            android.media.MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath(), to.getAbsolutePath()}, null, null);
            rebuild();
        }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ rows + adapter
    static final class Row {
        static final int ROW = 0, HEADER = 1, SQUARE = 2, WIDE = 3, GROUP = 4, BACK = 5, ACTION = 6, EMPTY = 7, PERM = 8, NOTE = 9;
        int type;
        MediaInfo m;
        File file;
        Group group;
        String title, sub, icon, button;
        Runnable click, longClick;
        boolean compact, hiddenMark, gap;
        int thumbPx = 256;

        static Row media(MediaInfo m, Runnable c, Runnable l) {
            Row r = new Row(); r.type = ROW; r.m = m; r.title = m.title; r.sub = m.subtitle(); r.click = c; r.longClick = l; r.hiddenMark = m.hidden; return r;
        }
        static Row square(MediaInfo m, Runnable c, Runnable l) { Row r = media(m, c, l); r.type = SQUARE; r.title = null; return r; }
        static Row wide(MediaInfo m, Runnable c, Runnable l) { Row r = media(m, c, l); r.type = WIDE; return r; }
        static Row header(String s) { Row r = new Row(); r.type = HEADER; r.title = s; return r; }
        static Row group(Group g, Runnable c) { Row r = new Row(); r.type = GROUP; r.group = g; r.click = c; return r; }
        static Row back(String t, String s, Runnable c) { Row r = new Row(); r.type = BACK; r.title = t; r.sub = s; r.click = c; return r; }
        static Row action(String icon, String t, Runnable c) { Row r = new Row(); r.type = ACTION; r.icon = icon; r.title = t; r.click = c; return r; }
        static Row empty(String t) { Row r = new Row(); r.type = EMPTY; r.title = t; return r; }
        static Row note(String t) { Row r = new Row(); r.type = NOTE; r.title = t; return r; }
        static Row perm(String t, String b, Runnable c) { Row r = new Row(); r.type = PERM; r.title = t; r.button = b; r.click = c; return r; }
        static Row icon(String icon, String t, String s, Runnable c, Runnable l) {
            Row r = new Row(); r.type = ROW; r.icon = icon; r.title = t; r.sub = s; r.click = c; r.longClick = l; return r;
        }
        static Row file(File f, String sub, Runnable c, Runnable l) {
            Row r = new Row(); r.type = ROW; r.file = f; r.title = f.getName(); r.sub = sub; r.click = c; r.longClick = l; return r;
        }
    }

    final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return rows.size(); }
        @Override public int getItemViewType(int p) { return rows.get(p).type; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v;
            switch (type) {
                case Row.HEADER: {
                    TextView t = Ui.label(MainActivity.this, "", 10, Ui.TEXT, 0.18f);
                    t.setPadding(Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 14), 0, Ui.dp(MainActivity.this, 8));
                    v = t;
                    break;
                }
                case Row.SQUARE: v = new Tile(MainActivity.this, false); break;
                case Row.WIDE: v = new Tile(MainActivity.this, true); break;
                case Row.GROUP: v = new GroupTile(MainActivity.this); break;
                case Row.EMPTY: case Row.NOTE: {
                    TextView t = Ui.text(MainActivity.this, "", type == Row.EMPTY ? 14 : 12, Ui.MUTED);
                    t.setGravity(type == Row.EMPTY ? Gravity.CENTER : Gravity.START);
                    t.setPadding(Ui.dp(MainActivity.this, 16), Ui.dp(MainActivity.this, type == Row.EMPTY ? 48 : 10), Ui.dp(MainActivity.this, 16), Ui.dp(MainActivity.this, 4));
                    v = t;
                    break;
                }
                case Row.PERM: v = new PermBox(MainActivity.this); break;
                default: v = new ListRow(MainActivity.this);
            }
            v.setLayoutParams(new RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP));
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            Row r = rows.get(pos);
            View v = h.itemView;
            v.setOnClickListener(r.click == null ? null : x -> r.click.run());
            v.setOnLongClickListener(r.longClick == null ? null : x -> { r.longClick.run(); return true; });
            if (r.click == null) v.setClickable(false);
            switch (r.type) {
                case Row.HEADER: case Row.EMPTY: case Row.NOTE: ((TextView) v).setText(r.title); break;
                case Row.SQUARE: case Row.WIDE: ((Tile) v).bind(r, spanCount); break;
                case Row.GROUP: ((GroupTile) v).bind(r.group); break;
                case Row.PERM: ((PermBox) v).bind(r); break;
                default: ((ListRow) v).bind(r, r.m != null && r.m.uri.equals(nowUri));
            }
        }
    }

    /** Standard list row: 48dp thumbnail, title, subtitle, ⋮. */
    final class ListRow extends LinearLayout {
        final ImageView thumb = new ImageView(getContext());
        final TextView title, sub;
        final ImageButton more;

        ListRow(Activity a) {
            super(a);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 2), Ui.dp(a, 8));
            setMinimumHeight(Ui.dp(a, 64));
            thumb.setBackground(Ui.rect(a, Ui.FILL, 8, 0, 0));
            thumb.setClipToOutline(true);
            addView(thumb, Ui.lp(Ui.dp(a, 48), Ui.dp(a, 48)));
            LinearLayout t = Ui.col(a);
            t.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 4), 0);
            title = Ui.single(Ui.text(a, "", 14, Ui.TEXT));
            sub = Ui.single(Ui.text(a, "", 12, Ui.MUTED));
            sub.setPadding(0, Ui.dp(a, 3), 0, 0);
            t.addView(title);
            t.addView(sub);
            addView(t, Ui.lpw(0, Ui.WRAP, 1));
            more = Ui.iconBtn(a, "more", 44, Ui.MUTED);
            addView(more);
        }

        void bind(Row r, boolean now) {
            Activity a = MainActivity.this;
            boolean plain = r.type == Row.BACK || r.type == Row.ACTION;
            setBackground(r.type == Row.ACTION ? Ui.pressable(Ui.rect(a, 0x0F00E5FF, 12, Ui.BORDER, 1), Ui.rect(a, 0x2400E5FF, 12, 0x5900E5FF, 1))
                    : Ui.rowBg(a, now));
            LayoutParams0.top(this, Ui.dp(a, 6));
            title.setText(r.title);
            title.setTextColor(now || r.type == Row.BACK || r.type == Row.ACTION ? Ui.ACCENT : Ui.TEXT);
            sub.setText(r.sub == null ? "" : r.sub);
            sub.setVisibility(r.sub == null ? GONE : VISIBLE);
            thumb.setVisibility(r.type == Row.BACK ? GONE : VISIBLE);
            more.setVisibility(r.longClick != null && !plain ? VISIBLE : GONE);
            more.setOnClickListener(x -> { if (r.longClick != null) r.longClick.run(); });
            if (r.m != null) {
                thumb.setBackground(Ui.rect(a, Ui.FILL, 8, 0, 0));
                Thumbs.load(thumb, r.m, Ui.dp(a, 96), r.m.isAudio() ? "music" : r.m.isVideo() ? "video" : "image");
            } else {
                thumb.setTag(null);
                thumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                String ic = r.icon;
                int color = Ui.ACCENT;
                if (r.file != null) {
                    if (r.file.isDirectory()) ic = "folder";
                    else {
                        int k = MediaInfo.kindOf(r.file.getName());
                        ic = k == MediaInfo.AUDIO ? "music" : k == MediaInfo.VIDEO ? "video" : k == MediaInfo.IMAGE ? "image" : "file";
                        color = Ui.MUTED;
                    }
                    if (r.hiddenMark) color = Ui.ACCENT2;
                    if (!r.file.isDirectory() && (MediaInfo.kindOf(r.file.getName()) == MediaInfo.IMAGE || MediaInfo.kindOf(r.file.getName()) == MediaInfo.VIDEO)) {
                        Thumbs.load(thumb, MediaInfo.fromFile(r.file), Ui.dp(a, 96), ic);
                        return;
                    }
                }
                thumb.setImageDrawable(new Icon(a, ic == null ? "file" : ic, color));
                thumb.setBackground(Ui.rect(a, r.type == Row.ACTION ? 0 : Ui.FILL, 8, 0, 0));
                int p = Ui.dp(a, 12);
                thumb.setPadding(p, p, p, p);
                return;
            }
            thumb.setPadding(0, 0, 0, 0);
        }
    }

    static final class LayoutParams0 {
        static void top(View v, int px) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) lp).topMargin = px;
        }
    }

    /** Square (gallery, song grid, folder grid) and 16:9 (videos) tiles. */
    final class Tile extends LinearLayout {
        final FrameLayout frame;
        final ImageView img = new ImageView(getContext());
        final TextView badge, title, sub;
        final View dot, label;
        final boolean wide;

        Tile(Activity a, boolean wide) {
            super(a);
            this.wide = wide;
            setOrientation(VERTICAL);
            frame = wide ? new WideFrame(a) : new SquareFrame(a);
            frame.setBackground(Ui.rect(a, Ui.CELL, wide ? 12 : 4, 0, 0));
            frame.setClipToOutline(true);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(img, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
            badge = Ui.text(a, "", 10, Ui.TEXT);
            badge.setBackground(Ui.rect(a, 0xBF05080F, 10, 0, 0));
            badge.setPadding(Ui.dp(a, 6), Ui.dp(a, 1), Ui.dp(a, 6), Ui.dp(a, 1));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.START);
            bp.setMargins(Ui.dp(a, 5), 0, 0, Ui.dp(a, 5));
            frame.addView(badge, bp);
            dot = new View(a);
            dot.setBackground(Ui.oval(a, Ui.ACCENT2, Ui.BG, 1));
            FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(Ui.dp(a, 9), Ui.dp(a, 9), Gravity.TOP | Gravity.END);
            dp.setMargins(0, Ui.dp(a, 6), Ui.dp(a, 6), 0);
            frame.addView(dot, dp);
            addView(frame, new LayoutParams(Ui.MATCH, Ui.WRAP));
            LinearLayout lab = Ui.col(a);
            title = Ui.text(a, "", 13, Ui.TEXT);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub = Ui.single(Ui.text(a, "", 11, Ui.MUTED));
            lab.addView(title);
            lab.addView(sub);
            lab.setPadding(Ui.dp(a, 2), Ui.dp(a, 6), Ui.dp(a, 2), Ui.dp(a, 8));
            label = lab;
            addView(lab);
        }

        void bind(Row r, int cols) {
            Activity a = MainActivity.this;
            int pad = r.gap ? Ui.dp(a, 0.75f) : Ui.dp(a, 4);
            setPadding(pad, pad, pad, pad);
            MediaInfo m = r.m;
            if (m != null) {
                Thumbs.load(img, m, Ui.dp(a, wide ? 220 : r.thumbPx / 2f), m.isAudio() ? "music" : m.isVideo() ? "video" : "image");
                badge.setVisibility(m.isVideo() ? VISIBLE : GONE);
                badge.setText("▶ " + Ui.time(m.duration));
                dot.setVisibility(m.hidden ? VISIBLE : GONE);
                boolean showText = wide ? !r.compact : r.title != null;
                label.setVisibility(showText ? VISIBLE : GONE);
                title.setText(m.title);
                title.setTextSize(wide && cols == 1 ? 15 : cols >= 3 ? 11 : 13);
                sub.setText(wide ? m.folder : m.artistOrUnknown());
            } else {
                img.setTag(null);
                img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                boolean folder = r.file != null && r.file.isDirectory();
                String ic = r.file != null ? (folder ? "folder" : "file") : r.icon;
                img.setImageDrawable(new Icon(a, ic == null ? "file" : ic, r.hiddenMark ? Ui.ACCENT2 : folder || r.file == null ? Ui.ACCENT : Ui.MUTED));
                int p = Ui.dp(a, 30);
                img.setPadding(p, p, p, p);
                badge.setVisibility(GONE);
                dot.setVisibility(GONE);
                label.setVisibility(VISIBLE);
                title.setText(r.title);
                sub.setText(r.sub == null ? "" : r.sub);
                return;
            }
            img.setPadding(0, 0, 0, 0);
        }
    }

    final class GroupTile extends FrameLayout {
        final ImageView img = new ImageView(getContext());
        final TextView name, cnt;

        GroupTile(Activity a) {
            super(a);
            setPadding(Ui.dp(a, 5), Ui.dp(a, 5), Ui.dp(a, 5), Ui.dp(a, 5));
            SquareFrame f = new SquareFrame(a);
            f.setBackground(Ui.rect(a, Ui.FILL, 16, Ui.BORDER, 1));
            f.setClipToOutline(true);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setAlpha(0.55f);
            f.addView(img, new LayoutParams(Ui.MATCH, Ui.MATCH));
            LinearLayout t = Ui.col(a);
            t.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
            name = Ui.text(a, "", 15, Ui.TEXT);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setMaxLines(2);
            cnt = Ui.text(a, "", 12, 0xFFB6C3D6);
            t.addView(name);
            t.addView(cnt);
            f.addView(t, new LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));
            addView(f, new LayoutParams(Ui.MATCH, Ui.WRAP));
        }

        void bind(Group g) {
            name.setText(g.name);
            cnt.setText(g.items.size() + " songs");
            Thumbs.load(img, g.items.get(0), Ui.dp(getContext(), 160), "music");
        }
    }

    final class PermBox extends LinearLayout {
        final TextView msg, btn;

        PermBox(Activity a) {
            super(a);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(Ui.dp(a, 24), Ui.dp(a, 60), Ui.dp(a, 24), Ui.dp(a, 24));
            ImageView ic = new ImageView(a);
            ic.setImageDrawable(new Icon(a, "folder", Ui.ACCENT));
            addView(ic, Ui.lp(Ui.dp(a, 48), Ui.dp(a, 48)));
            msg = Ui.text(a, "", 15, Ui.TEXT);
            msg.setGravity(Gravity.CENTER);
            msg.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 18));
            addView(msg);
            btn = Ui.pill(a, "", true, Ui.ACCENT);
            addView(btn, Ui.lp(Ui.WRAP, Ui.dp(a, 50)));
        }

        void bind(Row r) {
            msg.setText(r.title);
            btn.setText(r.button);
            btn.setOnClickListener(v -> r.click.run());
        }
    }

    /** A frame that is always square. */
    static class SquareFrame extends FrameLayout {
        SquareFrame(Activity a) { super(a); }
        @Override protected void onMeasure(int w, int h) { super.onMeasure(w, w); }
    }

    /** A 16:9 frame. */
    static class WideFrame extends FrameLayout {
        WideFrame(Activity a) { super(a); }
        @Override protected void onMeasure(int w, int h) {
            int width = View.MeasureSpec.getSize(w);
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(width * 9 / 16, View.MeasureSpec.EXACTLY));
        }
    }

    // ------------------------------------------------------------------ music squares
    static final class Group {
        final String name;
        final List<MediaInfo> items;
        Group(String n, List<MediaInfo> l) { name = n; items = l; }

        static Map<String, List<Group>> build(Activity a, List<MediaInfo> songs) {
            Map<String, List<Group>> out = new LinkedHashMap<>();
            List<Group> forYou = new ArrayList<>();
            List<MediaInfo> recent = new ArrayList<>(songs);
            Collections.sort(recent, (x, y) -> Long.compare(y.date, x.date));
            if (recent.size() >= 3) forYou.add(new Group("Recently added", new ArrayList<>(recent.subList(0, Math.min(50, recent.size())))));
            List<MediaInfo> played = new ArrayList<>();
            for (MediaInfo m : songs) if (Store.plays(a, m.uri) > 0) played.add(m);
            Collections.sort(played, (x, y) -> Store.plays(a, y.uri) - Store.plays(a, x.uri));
            if (played.size() >= 3) forYou.add(new Group("Most played", new ArrayList<>(played.subList(0, Math.min(50, played.size())))));
            if (!forYou.isEmpty()) out.put("FOR YOU", forYou);
            put(out, "SAME ARTIST", songs, m -> {
                String ar = m.artistOrUnknown();
                if (ar.equals("Unknown artist")) return null;
                return ar.split("\\s*(,|&| feat\\.? | ft\\.? | x |/)\\s*")[0].trim() + " mix";
            });
            put(out, "SAME GENRE", songs, m -> m.genre == null || m.genre.isEmpty() ? null : m.genre);
            put(out, "SAME ALBUM", songs, m -> m.album.isEmpty() || m.album.equalsIgnoreCase("<unknown>") ? null : m.album);
            put(out, "BY DECADE", songs, m -> m.year > 1900 ? (m.year / 10 * 10) + "s" : null);
            put(out, "BY FOLDER", songs, m -> m.folder.isEmpty() ? null : m.folder);
            return out;
        }

        interface Key { String of(MediaInfo m); }

        static void put(Map<String, List<Group>> out, String section, List<MediaInfo> songs, Key k) {
            Map<String, List<MediaInfo>> g = new LinkedHashMap<>();
            for (MediaInfo m : songs) {
                String key = k.of(m);
                if (key == null) continue;
                List<MediaInfo> l = g.get(key);
                if (l == null) { l = new ArrayList<>(); g.put(key, l); }
                l.add(m);
            }
            List<Group> gs = new ArrayList<>();
            for (Map.Entry<String, List<MediaInfo>> e : g.entrySet()) if (e.getValue().size() >= 3) gs.add(new Group(e.getKey(), e.getValue()));
            Collections.sort(gs, (x, y) -> y.items.size() - x.items.size());
            if (gs.size() > 12) gs = new ArrayList<>(gs.subList(0, 12));
            if (!gs.isEmpty()) out.put(section, gs);
        }
    }

}
