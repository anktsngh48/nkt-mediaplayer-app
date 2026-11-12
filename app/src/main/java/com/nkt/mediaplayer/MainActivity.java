package com.nkt.mediaplayer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The library. Pages are picked from the ☰ menu: Songs · Videos · Gallery · Folders · Playlists · History · Settings.
 * Opens on the page chosen with ⌂, shows the last library at once and refreshes it in the background.
 */
public class MainActivity extends Activity implements RowAdapter.Listener, Selection.Changed {

    static final int SONGS = 0, VIDEOS = 1, GALLERY = 2, FOLDERS = 3, PDFS = 4, PLAYLISTS = 5, HISTORY = 6, ONLINE = 7, SETTINGS = 8;
    static final String[] PAGE_KEYS = {"songs", "videos", "gallery", "folders", "pdfs", "playlists", "history", "online", "settings"};
    private static final int[] PAGE_TITLES = {R.string.page_songs, R.string.page_videos, R.string.page_gallery, R.string.page_folders,
            R.string.page_pdfs, R.string.page_playlists, R.string.page_history, R.string.page_online, R.string.page_settings};
    private static final int[] MENU_LABELS = {R.string.menu_songs, R.string.menu_videos, R.string.menu_gallery, R.string.menu_folders,
            R.string.menu_pdfs, R.string.menu_playlists, R.string.menu_history, R.string.menu_online, R.string.menu_settings};
    private static final int[] PAGE_ICONS = {R.drawable.ic_music, R.drawable.ic_movie, R.drawable.ic_image, R.drawable.ic_folder,
            R.drawable.ic_pdf, R.drawable.ic_playlist, R.drawable.ic_history, R.drawable.ic_globe, R.drawable.ic_settings};
    private static final String[] SORT_DEF = {"name", "new", "new", "name", "new", "name", "", "", ""};
    private static final int REQ_PERMS = 11, REQ_LEGACY_WRITE = 12;
    private static final int[] GALLERY_STEPS = {2, 3, 4, 5, 7};
    private static final String ALL_ALBUMS = "All";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<MediaInfo> audio = new ArrayList<>();
    private final List<MediaInfo> video = new ArrayList<>();
    private final List<MediaInfo> images = new ArrayList<>();
    private final List<MediaInfo> pdfs = new ArrayList<>();

    private RecyclerView list;
    private RowAdapter rows;
    private GalleryAdapter gallery;
    private VideoGridAdapter videoGrid;
    private SquareAdapter squares;
    private AlbumAdapter albums;
    private Selection sel;
    private FastScroller scroller;
    private FileOps.Deleter deleter;
    private TextView empty, zoomPop, gridSize, tabHint, sortBtn, segAll, segPhotos, segVideos, selCount, pageLabel, updating;
    private TextView folderPath, folderNote;
    private LinearLayout folderActions;
    private ImageButton[] viewSegs;
    private View permBox, mini, tabBar, viewSwitch, galleryBar, folderBar, collapse, selectBar, scrim;
    private ViewGroup drawer, drawerItems, storageRow;
    private ImageView miniArt;
    private TextView miniTitle, miniSub;
    private ImageButton miniPlay, btnMini;
    private EditText search;

    private int page = SONGS;
    private String openPlaylist, openVideoFolder;
    private Groups.Group openSquare;
    private File browseDir, browseRoot;
    private final List<RowAdapter.Row> browseRows = new ArrayList<>();
    private int browseHidden = 0;
    private boolean browseLoading = false;
    private final Map<String, Integer> scrollMemory = new java.util.HashMap<>();
    private String filter = "";
    private String album = ALL_ALBUMS;
    private int galleryKind = 0;              // 0 all, 1 photos, 2 videos
    private boolean loaded = false, loading = false, reloadAgain = false;
    private String pendingAccess = null;      // "hidden" / "thumbs" / "folders" waiting for All files access
    private float pinchAcc = 1f;
    private float edgeX = -1, edgeY;

    private ListenableFuture<MediaController> future;
    private MediaController controller;
    private Runnable pending;
    private ContentObserver observer;
    private OnlineMusic online;
    private View onlineBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.padForSystemBars(findViewById(R.id.frame));
        Prefs.migrateFavourites(this);
        Ui.track(getApplication());
        BinCleanupJob.schedule(this);                       // Recycle bin: 30 days, then deleted permanently
        deleter = new FileOps.Deleter(this);

        TextView title = findViewById(R.id.header_title);
        title.setTypeface(Ui.orbitronBlack(this));
        Ui.gradient(title);
        pageLabel = findViewById(R.id.page_label);
        pageLabel.setTypeface(Ui.orbitron(this));

        sel = new Selection(this);
        list = findViewById(R.id.list);
        list.setItemViewCacheSize(24);
        list.setItemAnimator(null);                       // no flicker when the library refreshes
        rows = new RowAdapter(this, sel);
        GalleryAdapter.Listener gridListener = new GalleryAdapter.Listener() {
            @Override public void onOpen(MediaInfo m, List<MediaInfo> all) { openGridItem(m, all); }
            @Override public void onLong(MediaInfo m) { if (sel.active()) sel.toggle(m); else sel.start(m); }
        };
        gallery = new GalleryAdapter(gridListener, sel);
        videoGrid = new VideoGridAdapter(gridListener, sel);
        squares = new SquareAdapter(g -> { openSquare = g; showHeader(); refresh(); });
        albums = new AlbumAdapter(a -> { album = a.name; refresh(); });
        RecyclerView albumStrip = findViewById(R.id.albums);
        albumStrip.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        albumStrip.setAdapter(albums);
        setupPinch();

        empty = findViewById(R.id.empty);
        zoomPop = findViewById(R.id.zoom_pop);
        zoomPop.setTypeface(Ui.orbitron(this));
        gridSize = findViewById(R.id.grid_size);
        collapse = findViewById(R.id.collapse);
        tabBar = findViewById(R.id.tab_bar);
        tabHint = findViewById(R.id.tab_hint);
        updating = findViewById(R.id.updating);
        sortBtn = findViewById(R.id.btn_sort);
        sortBtn.setTypeface(Ui.orbitron(this));
        sortBtn.setOnClickListener(v -> chooseSort());
        viewSwitch = findViewById(R.id.view_switch);
        viewSegs = new ImageButton[]{findViewById(R.id.view_a), findViewById(R.id.view_b), findViewById(R.id.view_c)};
        galleryBar = findViewById(R.id.gallery_bar);
        folderBar = findViewById(R.id.folder_bar);
        folderPath = findViewById(R.id.folder_path);
        folderNote = findViewById(R.id.folder_note);
        folderActions = findViewById(R.id.folder_actions);
        storageRow = findViewById(R.id.storage_row);
        segAll = findViewById(R.id.seg_all);
        segPhotos = findViewById(R.id.seg_photos);
        segVideos = findViewById(R.id.seg_videos);
        for (TextView t : new TextView[]{segAll, segPhotos, segVideos}) t.setTypeface(Ui.orbitron(this));
        segAll.setOnClickListener(v -> { galleryKind = 0; refresh(); });
        segPhotos.setOnClickListener(v -> { galleryKind = 1; refresh(); });
        segVideos.setOnClickListener(v -> { galleryKind = 2; refresh(); });

        // multi-select bar
        selectBar = findViewById(R.id.select_bar);
        selCount = findViewById(R.id.sel_count);
        findViewById(R.id.sel_close).setOnClickListener(v -> sel.clear());
        findViewById(R.id.sel_all).setOnClickListener(v -> sel.addAll(visiblePickable()));
        findViewById(R.id.sel_share).setOnClickListener(v -> Share.sendMany(this, filesOnly(sel.items())));
        findViewById(R.id.sel_info).setOnClickListener(v -> selectionDetails(sel.items()));
        findViewById(R.id.sel_more).setOnClickListener(v -> selectionMenu());
        findViewById(R.id.sel_delete).setOnClickListener(v -> deleteItems(sel.items()));

        scroller = findViewById(R.id.fast_scroller);
        scroller.attach(list, findViewById(R.id.fs_bubble), this::labelFor);

        setupCollapsingHeader();

        permBox = findViewById(R.id.perm_box);
        ((TextView) findViewById(R.id.perm_btn)).setTypeface(Ui.orbitron(this));
        findViewById(R.id.perm_btn).setOnClickListener(v -> askPermission());

        search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String f = s.toString().trim().toLowerCase(Locale.ROOT);
                if (f.equals(filter)) return;
                filter = f;
                ui.removeCallbacks(searchRefresh);
                ui.postDelayed(searchRefresh, page == FOLDERS ? 350 : 120);   // typing stays smooth on big libraries
            }
        });

        findViewById(R.id.btn_menu).setOnClickListener(v -> openDrawer());
        findViewById(R.id.btn_link).setOnClickListener(v -> openLinkDialog());
        btnMini = findViewById(R.id.btn_mini);
        btnMini.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.isPlaying()) controller.pause(); else Media.play(controller);
        });
        btnMini.setOnLongClickListener(v -> { startActivity(new Intent(this, NowPlayingActivity.class)); return true; });

        onlineBox = findViewById(R.id.online_box);
        online = new OnlineMusic(this, findViewById(R.id.online_box), playing -> {
            // a song from Online Music is playing: the app's own player pauses (its mini player then shows on Audio only)
            if (playing && controller != null && controller.isPlaying()) controller.pause();
            updateMini();
        });
        scrim = findViewById(R.id.scrim);
        drawer = findViewById(R.id.drawer);
        drawerItems = findViewById(R.id.drawer_items);
        scrim.setOnClickListener(v -> closeDrawer());

        mini = findViewById(R.id.mini);
        miniArt = findViewById(R.id.mini_art);
        miniArt.setClipToOutline(true);
        miniTitle = findViewById(R.id.mini_title);
        miniSub = findViewById(R.id.mini_sub);
        miniPlay = findViewById(R.id.mini_play);
        miniPlay.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.isPlaying()) controller.pause(); else Media.play(controller);
        });
        findViewById(R.id.mini_next).setOnClickListener(v -> { if (controller != null) controller.seekToNextMediaItem(); });
        findViewById(R.id.mini_close).setOnClickListener(v -> {
            if (controller == null) return;
            if (page == SONGS) {
                // ✕ on the Audio page: really stop and clear
                controller.stop();
                controller.clearMediaItems();
                mini.setVisibility(View.GONE);
            } else {
                // ✕ anywhere else: pause and hide here; it stays on the Audio page to carry on later
                controller.pause();
                updateMini();
            }
        });
        mini.setOnClickListener(v -> startActivity(new Intent(this, NowPlayingActivity.class)));

        int start = Arrays.asList(PAGE_KEYS).indexOf(Prefs.startPage(this));
        if (savedInstanceState != null) {
            start = savedInstanceState.getInt("page", start);
            String dir = savedInstanceState.getString("dir");
            if (dir != null) { browseDir = new File(dir); browseRoot = rootOf(browseDir); browse(browseDir); }
        }
        setPage(start < 0 || (start == SETTINGS && savedInstanceState == null) ? SONGS : start);
        if (hasPermission()) startLoading(); else showPermissionBox();
        revealFrom(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        revealFrom(intent);
    }

    private String revealPath = null;

    /** Back from a song, video or PDF opened from another app: the explorer, in its folder, with the file marked. */
    private void revealFrom(Intent in) {
        if (in == null || !in.hasExtra(ExternalOpen.REVEAL)) return;
        String path = in.getStringExtra(ExternalOpen.REVEAL);
        in.removeExtra(ExternalOpen.REVEAL);
        if (path == null || path.isEmpty() || !hasPermission()) return;
        File f = new File(path), dir = f.getParentFile();
        if (dir == null || !dir.isDirectory()) return;
        revealPath = f.getAbsolutePath();
        rows.setHighlight(revealPath);
        browseRoot = rootOf(dir);
        if (page != FOLDERS) setPage(FOLDERS);
        browse(dir);
    }

    private final Runnable searchRefresh = () -> { if (page == FOLDERS) deepSearch(filter); else refresh(); };

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
        if (browseDir != null) out.putString("dir", browseDir.getAbsolutePath());
    }

    private File rootOf(File f) {
        for (File r : FolderPicker.roots(this)) if (f.getAbsolutePath().startsWith(r.getAbsolutePath())) return r;
        return FolderPicker.roots(this).get(0);
    }

    // ── ☰ menu ───────────────────────────────────────────────────────────────

    private void buildDrawer() {
        drawerItems.removeAllViews();
        int dp = Ui.dp(this, 1);
        TextView head = new TextView(this);
        head.setText(R.string.header);
        head.setTypeface(Ui.orbitronBlack(this));
        head.setTextSize(15);
        head.setLetterSpacing(0.08f);
        head.setTextColor(getColor(R.color.accent));
        head.setPadding(10 * dp, 4 * dp, 10 * dp, 2 * dp);
        drawerItems.addView(head);
        Ui.gradient(head);
        TextView hint = new TextView(this);
        hint.setText(R.string.menu_hint);
        hint.setTextColor(getColor(R.color.muted));
        hint.setTextSize(11);
        hint.setPadding(10 * dp, 2 * dp, 10 * dp, 12 * dp);
        drawerItems.addView(hint);

        String startKey = Prefs.startPage(this);
        for (int p = SONGS; p <= ONLINE; p++) {
            final int pg = p;
            String count = countFor(p);
            boolean home = PAGE_KEYS[p].equals(startKey);
            drawerItems.addView(menuRow(PAGE_ICONS[p], getString(MENU_LABELS[p]), count, page == p, home, () -> {
                closeDrawer();
                setPage(pg);
            }, () -> {
                Prefs.setStartPage(this, PAGE_KEYS[pg]);
                Ui.toast(this, getString(R.string.start_page_set, getString(MENU_LABELS[pg])));
                buildDrawer();
            }));
        }
        View div = new View(this);
        div.setBackgroundColor(getColor(R.color.border));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp);
        dl.setMargins(10 * dp, 10 * dp, 10 * dp, 10 * dp);
        drawerItems.addView(div, dl);
        drawerItems.addView(menuRow(R.drawable.ic_eq, getString(R.string.eq_and_karaoke), null, false, false, () -> {
            closeDrawer();
            startActivity(new Intent(this, EqualizerActivity.class));
        }, null));
        drawerItems.addView(menuRow(R.drawable.ic_status, getString(R.string.menu_status), null, false, false, () -> {
            closeDrawer();
            startActivity(new Intent(this, StatusActivity.class));
        }, null));
        drawerItems.addView(menuRow(R.drawable.ic_delete, getString(R.string.menu_bin), null, false, false, () -> {
            closeDrawer();
            startActivity(new Intent(this, TrashActivity.class));
        }, null));
        drawerItems.addView(menuRow(R.drawable.ic_devices, getString(R.string.other_devices), null, false, false, () -> {
            closeDrawer();
            startActivity(new Intent(this, RemoteActivity.class));
        }, null));
        drawerItems.addView(menuRow(R.drawable.ic_pc, getString(R.string.nkt_share), getString(R.string.nkt_share_long), false, false, () -> {
            closeDrawer();
            startActivity(new Intent(this, NktShareActivity.class));
        }, null));
        drawerItems.addView(menuRow(R.drawable.ic_settings, getString(R.string.menu_settings), null, page == SETTINGS, false, () -> {
            closeDrawer();
            setPage(SETTINGS);
        }, null));
        TextView foot = new TextView(this);
        foot.setText(R.string.menu_footer);
        foot.setTextColor(getColor(R.color.muted));
        foot.setTextSize(10.5f);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(10 * dp, 18 * dp, 10 * dp, 6 * dp);
        drawerItems.addView(foot);
    }

    private View menuRow(int icon, String label, String sub, boolean current, boolean home, Runnable open, Runnable setHome) {
        int dp = Ui.dp(this, 1);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_segment);
        row.setSelected(current);
        row.setPadding(12 * dp, 6 * dp, 4 * dp, 6 * dp);
        row.setMinimumHeight(52 * dp);
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(ColorStateList.valueOf(getColor(current ? R.color.accent : R.color.text)));
        row.addView(ic, new LinearLayout.LayoutParams(22 * dp, 22 * dp));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(14 * dp, 0, 8 * dp, 0);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(getColor(current ? R.color.accent : R.color.text));
        texts.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(11.5f);
            s.setTextColor(getColor(R.color.muted));
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (setHome != null) {
            ImageButton h = new ImageButton(this);
            h.setImageResource(home ? R.drawable.ic_home_filled : R.drawable.ic_home);
            h.setImageTintList(ColorStateList.valueOf(getColor(home ? R.color.accent : R.color.muted)));
            h.setBackgroundResource(android.R.color.transparent);
            h.setContentDescription(getString(R.string.cd_start_page, label));
            h.setOnClickListener(v -> setHome.run());
            row.addView(h, new LinearLayout.LayoutParams(44 * dp, 44 * dp));
        }
        row.setOnClickListener(v -> open.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 2 * dp;
        row.setLayoutParams(lp);
        return row;
    }

    private String countFor(int p) {
        if (!loaded) return null;
        switch (p) {
            case SONGS: return getResources().getQuantityString(R.plurals.songs, audio.size(), audio.size());
            case VIDEOS: return getResources().getQuantityString(R.plurals.videos, video.size(), video.size());
            case GALLERY: { int n = images.size() + video.size(); return getResources().getQuantityString(R.plurals.items, n, n); }
            case PDFS: return getResources().getQuantityString(R.plurals.pdfs, pdfs.size(), pdfs.size());
            case PLAYLISTS: { int n = Playlists.names(this).size(); return getResources().getQuantityString(R.plurals.items, n, n); }
            case HISTORY: return History.enabled(this) ? getResources().getQuantityString(R.plurals.items, History.count(this), History.count(this)) : getString(R.string.history_off_short);
        }
        return null;
    }

    private boolean drawerOpen() { return drawer.getVisibility() == View.VISIBLE; }

    private void openDrawer() {
        if (drawerOpen()) return;
        hideKeyboard();
        buildDrawer();
        drawer.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        drawer.post(() -> {
            drawer.setTranslationX(-drawer.getWidth());
            drawer.animate().translationX(0).setDuration(200).start();
            scrim.animate().alpha(1f).setDuration(200).start();
        });
    }

    private void closeDrawer() {
        if (!drawerOpen()) return;
        drawer.animate().translationX(-drawer.getWidth()).setDuration(170).withEndAction(() -> drawer.setVisibility(View.GONE)).start();
        scrim.animate().alpha(0f).setDuration(170).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    }

    /** Swipe in from the left edge to open the menu. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (!drawerOpen() && !sel.active()) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    edgeX = e.getX() < Ui.dp(this, 22) ? e.getX() : -1;
                    edgeY = e.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (edgeX >= 0 && e.getX() - edgeX > Ui.dp(this, 48) && Math.abs(e.getY() - edgeY) < Ui.dp(this, 40)) {
                        edgeX = -1;
                        MotionEvent cancel = MotionEvent.obtain(e);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        openDrawer();
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: edgeX = -1; break;
            }
        }
        return super.dispatchTouchEvent(e);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    // ── Everything above the files slides away while scrolling ───────────────

    private void setupCollapsingHeader() {
        collapse.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = collapse.getHeight();
            if (list.getPaddingTop() != h) {
                list.setPadding(list.getPaddingLeft(), h, list.getPaddingRight(), list.getPaddingBottom());
            }
            if (onlineBox.getPaddingTop() != h) onlineBox.setPadding(0, h, 0, 0);
            scroller.setTopInset(h + collapse.getTranslationY());
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                float h = collapse.getHeight();
                float t = collapse.getTranslationY() - dy;
                if (!rv.canScrollVertically(-1)) t = 0;              // at the very top: always show
                t = Math.max(-h, Math.min(0, t));
                collapse.setTranslationY(t);
                scroller.setTopInset(h + t);
            }

            @Override public void onScrollStateChanged(RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                // settle half-shown: snap open or closed
                float h = collapse.getHeight(), t = collapse.getTranslationY();
                if (t == 0 || t == -h) return;
                float to = (t > -h / 2f || !rv.canScrollVertically(-1)) ? 0 : -h;
                collapse.animate().translationY(to).setDuration(140)
                        .setUpdateListener(a -> scroller.setTopInset(h + collapse.getTranslationY())).start();
            }
        });
    }

    private void showHeader() {
        collapse.animate().cancel();
        collapse.setTranslationY(0);
        scroller.setTopInset(collapse.getHeight());
        list.scrollToPosition(0);
    }

    // ── Permission & loading ─────────────────────────────────────────────────

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 33)
            return new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS};
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasPermission() {
        for (String p : neededPermissions())
            if (!p.equals(Manifest.permission.POST_NOTIFICATIONS) && checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return true;
        return false;
    }

    private void askPermission() { requestPermissions(neededPermissions(), REQ_PERMS); }

    private void showPermissionBox() {
        permBox.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (deleter.onRequestPermissionsResult(requestCode, results)) return;
        if (online != null && online.onRequestPermissionsResult(requestCode, results)) return;
        if (requestCode == REQ_PERMS && hasPermission()) startLoading();
        if (requestCode == REQ_LEGACY_WRITE && HiddenScanner.hasAllFilesAccess(this)) accessGranted();
    }

    /** Settings that change what the library holds; a saved copy made with other settings is not used. */
    private String librarySettings() {
        boolean access = HiddenScanner.hasAllFilesAccess(this);
        return (Prefs.showHidden(this) && access) + "/" + (Prefs.showThumbnails(this) && access);
    }

    /** Shows the saved library at once, then checks the phone in the background. */
    private void startLoading() {
        permBox.setVisibility(View.GONE);
        if (loaded) { load(true); return; }
        empty.setText(R.string.loading);
        empty.setVisibility(View.VISIBLE);
        final String settings = librarySettings();
        new Thread(() -> {
            LibraryCache.Snapshot snap = LibraryCache.load(this);
            try { Trash.purgeOld(this); } catch (Throwable ignored) { }      // 30 days in the bin: gone for good
            long gen = LibraryCache.generation(this);
            ui.post(() -> {
                boolean fresh = false;
                if (snap != null && !snap.empty() && settings.equals(snap.settings) && !loaded) {
                    audio.clear(); audio.addAll(snap.audio);
                    video.clear(); video.addAll(snap.video);
                    images.clear(); images.addAll(snap.images);
                    loaded = true;
                    refresh();
                    fresh = gen >= 0 && gen == snap.generation;
                }
                if (!fresh) load(loaded);
                else new Thread(() -> {                     // PDFs aren't in the saved copy: a quick look
                    List<MediaInfo> pd = MediaLibrary.pdfs(this);
                    ui.post(() -> { pdfs.clear(); pdfs.addAll(pd); if (page == PDFS) refresh(); });
                }).start();
                watchLibrary();
            });
        }).start();
    }

    /** Reads the library. quiet = rows already showing; only the small "⟳ Updating" appears. */
    private void load(boolean quiet) {
        if (loading) { reloadAgain = true; return; }
        loading = true;
        permBox.setVisibility(View.GONE);
        if (!loaded) { empty.setText(R.string.loading); empty.setVisibility(View.VISIBLE); }
        if (quiet || loaded) updating.setVisibility(View.VISIBLE);
        final boolean access = HiddenScanner.hasAllFilesAccess(this);
        final boolean withHidden = Prefs.showHidden(this) && access;
        final boolean withThumbs = Prefs.showThumbnails(this) && access;
        final String settings = librarySettings();
        final boolean hideDotNow = Prefs.hideDot(this), hideNomediaNow = Prefs.hideNomedia(this);
        new Thread(() -> {
            long gen = LibraryCache.generation(this);
            // 1) Android's own library: quick. Shown straight away.
            List<MediaInfo> a = MediaLibrary.audio(this);
            List<MediaInfo> v = MediaLibrary.video(this);
            List<MediaInfo> im = MediaLibrary.images(this);
            List<MediaInfo> pd = MediaLibrary.pdfs(this);
            if (!withThumbs) { dropThumbs(a); dropThumbs(v); dropThumbs(im); }
            boolean more = withHidden || withThumbs;
            publish(a, v, im, pd, !more);
            // 2) hidden folders: a walk through the storage, which takes longer. Added when done.
            if (more) {
                final boolean fHid = withHidden, fTh = withThumbs;
                HiddenScanner.Result h = HiddenScanner.scan(this, fHid, fTh, hideDotNow, hideNomediaNow, first -> {
                    // names are known already; details are still being read. Show them now, the last step follows.
                    publishWithHidden(a, v, im, pd, first, hideDotNow, hideNomediaNow, false);
                });
                List<MediaInfo>[] done = publishWithHidden(a, v, im, pd, h, hideDotNow, hideNomediaNow, true);
                List<MediaInfo> a2 = done[0], v2 = done[1], im2 = done[2];
                LibraryCache.save(this, a2, v2, im2, gen, settings);
            } else LibraryCache.save(this, a, v, im, gen, settings);
        }).start();
    }

    /** Android's own library plus what the walk found, with hidden kinds the settings keep hidden left out. */
    @SuppressWarnings("unchecked")
    private List<MediaInfo>[] publishWithHidden(List<MediaInfo> a, List<MediaInfo> v, List<MediaInfo> im, List<MediaInfo> pd,
                                                HiddenScanner.Result h, boolean hideDot, boolean hideNomedia, boolean done) {
        List<MediaInfo> a2 = new ArrayList<>(a), v2 = new ArrayList<>(v), im2 = new ArrayList<>(im);
        addMissing(a2, h.audio);
        addMissing(v2, h.video);
        addMissing(im2, h.images);
        // Settings → ALL FILES: one kind of hidden folder can stay hidden while the other shows
        if (hideDot || hideNomedia) {
            java.util.Map<String, Boolean> nomedia = new java.util.HashMap<>();
            dropHidden(a2, hideDot, hideNomedia, nomedia);
            dropHidden(v2, hideDot, hideNomedia, nomedia);
            dropHidden(im2, hideDot, hideNomedia, nomedia);
        }
        publish(a2, v2, im2, pd, done);
        return new List[]{a2, v2, im2};
    }

    /** Shows a freshly read library without emptying the screen; done = the last step of this reading. */
    private void publish(List<MediaInfo> a, List<MediaInfo> v, List<MediaInfo> im, List<MediaInfo> pd, boolean done) {
        ui.post(() -> {
            boolean changed = !same(audio, a) || !same(video, v) || !same(images, im) || !same(pdfs, pd);
            if (changed) {
                audio.clear(); audio.addAll(a);
                video.clear(); video.addAll(v);
                images.clear(); images.addAll(im);
                pdfs.clear(); pdfs.addAll(pd);
            }
            loaded = true;
            if (changed || empty.getVisibility() == View.VISIBLE) refresh();
            if (!done) return;
            loading = false;
            if (!browseLoading) updating.setVisibility(View.GONE);
            if (reloadAgain) { reloadAgain = false; load(true); }
        });
    }

    private static boolean same(List<MediaInfo> x, List<MediaInfo> y) {
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) {
            MediaInfo p = x.get(i), q = y.get(i);
            if (!p.key().equals(q.key()) || !p.title.equals(q.title) || p.size != q.size || p.dateMs != q.dateMs
                    || !p.artist.equals(q.artist) || !p.folder.equals(q.folder)) return false;
        }
        return true;
    }

    /** New photos, songs or videos appear without pulling to refresh. */
    private void watchLibrary() {
        if (observer != null) return;
        observer = new ContentObserver(ui) {
            @Override public void onChange(boolean selfChange) {
                ui.removeCallbacks(libraryChanged);
                ui.postDelayed(libraryChanged, 1500);
            }
        };
        try {
            getContentResolver().registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer);
            getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer);
            getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        } catch (Exception ignored) { }
    }

    private final Runnable libraryChanged = () -> { if (hasPermission()) load(true); };

    @Override
    protected void onPause() {
        super.onPause();
        online.onActivityPause();                    // YouTube songs can't play in the background
    }

    @Override
    protected void onDestroy() {
        online.destroy();
        if (observer != null) try { getContentResolver().unregisterContentObserver(observer); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /** Drops media in dot-folders and/or folders with a .nomedia file (only ones found by the hidden scan). */
    private static void dropHidden(List<MediaInfo> l, boolean dot, boolean nomedia, java.util.Map<String, Boolean> cache) {
        for (int i = l.size() - 1; i >= 0; i--) {
            MediaInfo m = l.get(i);
            if (!m.hidden || m.path.isEmpty()) continue;
            if (dot && m.path.contains("/.")) { l.remove(i); continue; }
            if (nomedia) {
                File dir = new File(m.path).getParentFile();
                boolean hit = false;
                while (dir != null && !hit) {
                    String k = dir.getAbsolutePath();
                    Boolean has = cache.get(k);
                    if (has == null) { has = new File(dir, ".nomedia").exists(); cache.put(k, has); }
                    hit = has;
                    dir = dir.getParentFile();
                }
                if (hit) l.remove(i);
            }
        }
    }

        private static void dropThumbs(List<MediaInfo> l) {
        for (int i = l.size() - 1; i >= 0; i--) if (HiddenScanner.isThumbnailPath(l.get(i).path)) l.remove(i);
    }

    private static void addMissing(List<MediaInfo> into, List<MediaInfo> extra) {
        Set<String> paths = new HashSet<>();
        for (MediaInfo m : into) if (!m.path.isEmpty()) paths.add(m.path);
        for (MediaInfo m : extra) if (!paths.contains(m.path)) into.add(m);
    }

    private boolean resumedOnce = false;

    @Override
    protected void onResume() {
        super.onResume();
        online.onActivityResume(page == ONLINE);
        if (pendingAccess != null && HiddenScanner.hasAllFilesAccess(this)) { accessGranted(); return; }
        pendingAccess = null;
        if (!resumedOnce) { resumedOnce = true; return; }
        if (!loaded && hasPermission()) startLoading();
        else if (page == PLAYLISTS || page == HISTORY || page == SETTINGS || page == FOLDERS) refresh();
    }

    // ── Settings switches that need "All files access" ───────────────────────

    private void needAccessFor(String what) {
        Dialogs.builder(this)
                .setTitle(R.string.hidden_title)
                .setMessage("folders".equals(what) ? R.string.folders_need_access : R.string.hidden_explain)
                .setPositiveButton(R.string.allow, (d, w) -> {
                    pendingAccess = what;
                    if (Build.VERSION.SDK_INT >= 30) {
                        try { startActivity(HiddenScanner.allFilesAccessIntent(this)); } catch (Exception ignored) { }
                    } else {
                        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY_WRITE);
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> refresh())
                .setOnCancelListener(d -> refresh())
                .show();
    }

    private void accessGranted() {
        if ("hidden".equals(pendingAccess)) Prefs.setShowHidden(this, true);
        if ("thumbs".equals(pendingAccess)) Prefs.setShowThumbnails(this, true);
        boolean library = !"folders".equals(pendingAccess);
        pendingAccess = null;
        if (library) Ui.toast(this, R.string.hidden_scanning);
        load(true);                                  // PDFs and hidden files appear with access
        if (page == FOLDERS) browse(browseDir);
        refresh();
    }

    @Override
    public void onToggle(RowAdapter.Row r, boolean on) {
        switch (r.key) {
            case "hidden":
                if (!on) { Prefs.setShowHidden(this, false); load(true); }
                else if (HiddenScanner.hasAllFilesAccess(this)) { Prefs.setShowHidden(this, true); Ui.toast(this, R.string.hidden_scanning); load(true); }
                else needAccessFor("hidden");
                break;
            case "thumbs":
                if (!on) { Prefs.setShowThumbnails(this, false); load(true); }
                else if (HiddenScanner.hasAllFilesAccess(this)) { Prefs.setShowThumbnails(this, true); Ui.toast(this, R.string.hidden_scanning); load(true); }
                else needAccessFor("thumbs");
                break;
            case "hide_dot": case "hide_nomedia": case "hide_thumbdirs":
                Prefs.setBool(this, r.key, on);
                browseRows.clear();          // All Files lists again with the new rule
                if (browseDir != null) browse(browseDir);
                if (!on && !HiddenScanner.hasAllFilesAccess(this)) needAccessFor("hidden");   // showing hidden media needs access
                else { load(true); refresh(); }
                break;
            case "history":
                History.setEnabled(this, on);
                refresh();
                break;
            case "auto_art":
                Prefs.setAutoArt(this, on);
                break;
            case "no_internet":
                Prefs.setNoInternet(this, on);
                if (online != null) { online.reset(); if (page == ONLINE) online.show(); }      // the page shows its message, or loads again
                break;
        }
    }

    // ── Pages & views ────────────────────────────────────────────────────────

    private void setPage(int p) {
        if (sel.active()) sel.clear();
        rememberScroll();
        page = p;
        openPlaylist = null;
        openSquare = null;
        openVideoFolder = null;
        searchGen++;
        searchHits = null;
        searching = false;
        if (filter.length() > 0) { search.setText(""); filter = ""; }
        pageLabel.setText(PAGE_TITLES[p]);
        updateMini();
        search.setHint(p == GALLERY ? R.string.search_gallery_hint : p == SETTINGS ? R.string.search_settings_hint : R.string.search_hint);
        if (p == FOLDERS && browseDir == null) {
            List<File> roots = FolderPicker.roots(this);
            browseRoot = roots.get(0);
            browse(browseRoot);
        }
        refresh();
        showHeader();
        restoreScroll();
    }

    private String scrollKey() {
        return page + "|" + openPlaylist + "|" + openVideoFolder + "|" + (openSquare == null ? "" : openSquare.name)
                + "|" + (page == FOLDERS && browseDir != null ? browseDir.getAbsolutePath() : "");
    }

    private void rememberScroll() {
        RecyclerView.LayoutManager lm = list.getLayoutManager();
        if (lm instanceof LinearLayoutManager) scrollMemory.put(scrollKey(), ((LinearLayoutManager) lm).findFirstVisibleItemPosition());
    }

    private void restoreScroll() {
        Integer pos = scrollMemory.get(scrollKey());
        if (pos != null && pos > 0) list.scrollToPosition(pos);
    }

    private String pageKey() {
        if (page == VIDEOS && "folders".equals(view()) && openVideoFolder == null) return "videofolders";
        if (page == PLAYLISTS && openPlaylist != null) return "playlist";
        return PAGE_KEYS[page];
    }

    /** list / grid / squares / folders for pages that have a choice. */
    private String view() {
        switch (page) {
            case SONGS: return Prefs.view(this, "songs", "list");
            case VIDEOS: return Prefs.view(this, "videos", "list");
            case FOLDERS: return Prefs.view(this, "folders", "list");
            case PLAYLISTS: return Prefs.view(this, "playlists", "list");
            case PDFS: return Prefs.view(this, "pdfs", "list");
        }
        return "list";
    }

    private void setView(String v) {
        if (page == SONGS) openSquare = null;
        if (page == VIDEOS) openVideoFolder = null;
        Prefs.setView(this, PAGE_KEYS[page], v);
        refresh();
        showHeader();
    }

    private boolean matches(MediaInfo m) {
        if (filter.isEmpty()) return true;
        return m.title.toLowerCase(Locale.ROOT).contains(filter)
                || m.artist.toLowerCase(Locale.ROOT).contains(filter)
                || m.album.toLowerCase(Locale.ROOT).contains(filter)
                || m.folder.toLowerCase(Locale.ROOT).contains(filter)
                || m.fileName.toLowerCase(Locale.ROOT).contains(filter);
    }

    private boolean matchesText(String s) { return filter.isEmpty() || s.toLowerCase(Locale.ROOT).contains(filter); }

    /** Rows as a list or as tiles. */
    private void useRows(boolean grid, String colsKey, int defCols) {
        rows.setGrid(grid);
        if (grid) {
            int cols = gridCols(Prefs.cols(this, colsKey, defCols));
            RecyclerView.LayoutManager lm = list.getLayoutManager();
            if (list.getAdapter() != rows || !(lm instanceof GridLayoutManager) || ((GridLayoutManager) lm).getSpanCount() != cols) {
                GridLayoutManager glm = new GridLayoutManager(this, cols);
                glm.setSpanSizeLookup(rows.spans(cols));
                list.setLayoutManager(glm);
                list.setAdapter(rows);
            }
        } else if (list.getAdapter() != rows || list.getLayoutManager() instanceof GridLayoutManager) {
            list.setLayoutManager(new LinearLayoutManager(this));
            list.setAdapter(rows);
        }
    }

    private int gridCols(int portrait) {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        return land ? portrait + 1 : portrait;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        refresh();
    }

    private void setSeg(TextView t, boolean on) {
        t.setSelected(on);
        t.setTextColor(getColor(on ? R.color.accent : R.color.muted));
        t.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(on ? R.color.accent : R.color.muted)));
    }

    private void updateBars() {
        tabHint.setOnClickListener(null);
        boolean inside = openPlaylist != null || openSquare != null || openVideoFolder != null;
        boolean sortable = !SORT_DEF[page].isEmpty() && !(page == SONGS && "squares".equals(view()) && openSquare == null);
        tabBar.setVisibility(page == SETTINGS || page == ONLINE ? View.GONE : View.VISIBLE);
        search.setVisibility(page == ONLINE ? View.GONE : View.VISIBLE);
        sortBtn.setVisibility(sortable ? View.VISIBLE : View.GONE);
        if (sortable) sortBtn.setText(SortSheet.chip(this, currentSort()));
        galleryBar.setVisibility(page == GALLERY ? View.VISIBLE : View.GONE);
        folderBar.setVisibility(page == FOLDERS ? View.VISIBLE : View.GONE);
        String[] views = SortSheet.views(PAGE_KEYS[page]);
        boolean showViews = views.length > 1 && !(inside && page != PLAYLISTS);
        viewSwitch.setVisibility(showViews ? View.VISIBLE : View.GONE);
        if (showViews) {
            String cur = view();
            for (int i = 0; i < viewSegs.length; i++) {
                ImageButton b = viewSegs[i];
                if (i >= views.length) { b.setVisibility(View.GONE); continue; }
                final String v = views[i];
                b.setVisibility(View.VISIBLE);
                b.setImageResource(SortSheet.viewIcon(v));
                b.setContentDescription(getString(SortSheet.viewLabel(v)));
                boolean on = v.equals(cur);
                b.setSelected(on);
                b.setImageTintList(ColorStateList.valueOf(getColor(on ? R.color.accent : R.color.muted)));
                b.setOnClickListener(x -> setView(v));
            }
        }
    }

    private void refresh() {
        if (isFinishing()) return;
        updateBars();
        if (page == ONLINE) {
            list.setVisibility(View.GONE);
            empty.setVisibility(View.GONE);
            scroller.setVisibility(View.GONE);
            online.show();
            return;
        }
        if (onlineBox.getVisibility() == View.VISIBLE) online.hide();
        list.setVisibility(View.VISIBLE);
        scroller.setVisibility(View.VISIBLE);
        if (page == GALLERY) { refreshGallery(); return; }
        String view = view();
        if (page == SONGS && "squares".equals(view) && openSquare == null) { refreshSquares(); return; }
        if (page == VIDEOS && "grid".equals(view)) { refreshVideoGrid(video, null); return; }
        if (page == VIDEOS && "folders".equals(view) && openVideoFolder != null && Prefs.view(this, "videofolder", "grid").equals("grid")) {
            List<MediaInfo> in = new ArrayList<>();
            for (MediaInfo m : video) if (m.folder.equals(openVideoFolder)) in.add(m);
            refreshVideoGrid(in, openVideoFolder);
            return;
        }

        List<RowAdapter.Row> out = new ArrayList<>();
        int emptyText = R.string.nothing_here;
        boolean grid = false;
        String colsKey = PAGE_KEYS[page];
        int defCols = 3;

        if (page == SONGS) {
            if (openSquare != null) {
                out.add(new RowAdapter.Row(RowAdapter.BACK, openSquare.name,
                        getResources().getQuantityString(R.plurals.songs, openSquare.songs.size(), openSquare.songs.size())
                                + " · " + Ui.time(openSquare.totalMs()) + " · " + getString(R.string.all_squares), null, null, R.drawable.ic_back));
                out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.play_all), null, null, "sq_play", R.drawable.ic_play));
                out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.shuffle_all), null, null, "sq_shuffle", R.drawable.ic_shuffle));
                out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.save_as_playlist), null, null, "sq_save", R.drawable.ic_playlist_add));
                for (MediaInfo m : openSquare.songs) if (matches(m)) out.add(RowAdapter.Row.media(m));
            } else {
                for (MediaInfo m : sorted(audio, currentSort())) if (matches(m)) out.add(RowAdapter.Row.media(m));
                tabHint.setText(getResources().getQuantityString(R.plurals.songs, audio.size(), audio.size()));
                grid = "grid".equals(view);
            }
            emptyText = R.string.no_songs;
        } else if (page == VIDEOS) {
            if ("folders".equals(view)) {
                buildVideoFolders(out);
            } else {
                for (MediaInfo m : sorted(video, currentSort())) if (matches(m)) out.add(RowAdapter.Row.media(m));
                tabHint.setText(getResources().getQuantityString(R.plurals.videos, video.size(), video.size()));
            }
            emptyText = R.string.no_videos;
        } else if (page == FOLDERS) {
            buildBrowser(out);
            grid = "grid".equals(view);
            emptyText = browseLoading ? R.string.loading : R.string.folder_empty;
        } else if (page == PLAYLISTS) {
            emptyText = buildPlaylists(out);
            grid = "grid".equals(view);
        } else if (page == PDFS) {
            if (Build.VERSION.SDK_INT >= 30 && !HiddenScanner.hasAllFilesAccess(this))
                out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.allow), getString(R.string.folders_need_access), null, "folders_access", R.drawable.ic_lock));
            for (MediaInfo m : sorted(pdfs, currentSort())) if (matches(m))
                out.add(RowAdapter.Row.media(m, getString(R.string.file_line, Formatter.formatShortFileSize(this, m.size), shortDate(m.dateMs)) + " · " + m.folder));
            tabHint.setText(getResources().getQuantityString(R.plurals.pdfs, pdfs.size(), pdfs.size()));
            grid = "grid".equals(view);
            emptyText = R.string.no_pdfs;
        } else if (page == HISTORY) {
            emptyText = buildHistory(out);
        } else {
            buildSettings(out);
        }

        useRows(grid, colsKey, defCols);
        rows.setRows(out);
        if (permBox.getVisibility() == View.VISIBLE) return;
        boolean noMedia = true;
        for (RowAdapter.Row r : out)
            if (r.type == RowAdapter.MEDIA || r.type == RowAdapter.FOLDER || r.type == RowAdapter.PLAYLIST) { noMedia = false; break; }
        boolean show = (loaded || page == FOLDERS) && noMedia && page != SETTINGS && !(page == PLAYLISTS && openPlaylist == null);
        empty.setText(filter.isEmpty() ? emptyText : R.string.no_matches);
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ── Videos: folder view ──────────────────────────────────────────────────

    private void buildVideoFolders(List<RowAdapter.Row> out) {
        if (openVideoFolder != null) {
            List<MediaInfo> in = new ArrayList<>();
            for (MediaInfo m : video) if (m.folder.equals(openVideoFolder) && matches(m)) in.add(m);
            out.add(new RowAdapter.Row(RowAdapter.BACK, openVideoFolder,
                    getResources().getQuantityString(R.plurals.videos, in.size(), in.size()), null, null, R.drawable.ic_back));
            for (MediaInfo m : sorted(in, currentSort())) out.add(RowAdapter.Row.media(m));
            tabHint.setText(openVideoFolder);
            return;
        }
        Map<String, List<MediaInfo>> by = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (MediaInfo m : video) {
            List<MediaInfo> l = by.get(m.folder);
            if (l == null) { l = new ArrayList<>(); by.put(m.folder, l); }
            l.add(m);
        }
        List<Map.Entry<String, List<MediaInfo>>> entries = new ArrayList<>(by.entrySet());
        String s = Prefs.sort(this, "videofolders", "name");
        switch (s) {
            case "name_desc": Collections.reverse(entries); break;
            case "most": Collections.sort(entries, (a, b) -> Integer.compare(b.getValue().size(), a.getValue().size())); break;
            case "new": Collections.sort(entries, (a, b) -> Long.compare(newest(b.getValue()), newest(a.getValue()))); break;
            case "old": Collections.sort(entries, (a, b) -> Long.compare(newest(a.getValue()), newest(b.getValue()))); break;
            case "large": Collections.sort(entries, (a, b) -> Long.compare(total(b.getValue()), total(a.getValue()))); break;
        }
        for (Map.Entry<String, List<MediaInfo>> e : entries) {
            if (!matchesText(e.getKey())) continue;
            List<MediaInfo> l = sorted(e.getValue(), "new");
            long ms = 0;
            for (MediaInfo m : l) ms += m.durationMs;
            boolean hid = l.get(0).hidden;
            RowAdapter.Row fr = new RowAdapter.Row(RowAdapter.FOLDER, e.getKey(),
                    (hid ? getString(R.string.hidden_prefix) : "") + getResources().getQuantityString(R.plurals.videos, l.size(), l.size())
                            + (ms > 0 ? " · " + Ui.time(ms) : ""), l.get(0), "vf:" + e.getKey(), R.drawable.ic_folder);
            fr.pick = l;                                   // long-press picks the whole folder
            out.add(fr);
        }
        tabHint.setText(getResources().getQuantityString(R.plurals.folders, entries.size(), entries.size()));
    }

    private static long newest(List<MediaInfo> l) { long t = 0; for (MediaInfo m : l) t = Math.max(t, m.dateMs); return t; }

    private static long total(List<MediaInfo> l) { long t = 0; for (MediaInfo m : l) t += m.size; return t; }

    // ── Folders: every folder on the phone, from the top ─────────────────────

    private void browse(File dir) {
        if (dir == null) return;
        if (searchHits != null) {                      // leaving a search: back to plain listing
            searchGen++;
            searchHits = null;
            searching = false;
            if (search.getText().length() > 0) { filter = ""; search.setText(""); }
        }
        browseDir = dir;
        browseLoading = true;
        browseRows.clear();
        final boolean hideDot = Prefs.hideDot(this), hideNomedia = Prefs.hideNomedia(this), hideThumbs = Prefs.hideThumbDirs(this);
        final String sort = Prefs.sort(this, "folders", "name");
        final boolean foldersFirst = Prefs.foldersFirst(this, "folders");
        updating.setVisibility(View.VISIBLE);
        new Thread(() -> {
            File[] kids = dir.listFiles();
            List<File> dirs = new ArrayList<>(), files = new ArrayList<>();
            int hidden = 0;
            if (kids != null) for (File f : kids) {
                if (f.isDirectory()) {
                    String n = f.getName();
                    boolean hide = (hideDot && n.startsWith("."))
                            || (hideThumbs && n.equalsIgnoreCase(".thumbnails"))
                            || (hideNomedia && new File(f, ".nomedia").exists());
                    if (hide) hidden++; else dirs.add(f);
                } else {
                    if (hideDot && f.getName().startsWith(".")) { hidden++; continue; }
                    files.add(f);
                }
            }
            Comparator<File> cmp = fileOrder(sort);
            Collections.sort(dirs, cmp);
            Collections.sort(files, cmp);
            List<RowAdapter.Row> out = new ArrayList<>();
            List<RowAdapter.Row> dirRows = new ArrayList<>(), fileRows = new ArrayList<>();
            for (File d : dirs) {
                String[] in = d.list();
                int n = in == null ? 0 : in.length;
                String sub = getResources().getQuantityString(R.plurals.items, n, n) + " · " + shortDate(d.lastModified());
                boolean hid = d.getName().startsWith(".") || new File(d, ".nomedia").exists();
                dirRows.add(folderRow(d, (hid ? getString(R.string.hidden_prefix) : "") + sub, hid));
            }
            for (File f : files) {
                MediaInfo m = MediaInfo.fromFile(f);
                fileRows.add(RowAdapter.Row.media(m, getString(R.string.file_line,
                        Formatter.formatShortFileSize(this, f.length()), shortDate(f.lastModified()))));
            }
            out.addAll(dirRows);
            out.addAll(fileRows);
            if (!foldersFirst) mergeByFileOrder(out, cmp);
            final int hiddenCount = hidden;
            ui.post(() -> {
                if (!dir.equals(browseDir)) return;
                browseRows.clear();
                browseRows.addAll(out);
                browseHidden = hiddenCount;
                browseLoading = false;
                if (!loading) updating.setVisibility(View.GONE);
                if (page == FOLDERS) refresh();
                if (revealPath != null && page == FOLDERS) {                   // opened from another app: bring its file into view
                    int at = rows.indexOfPath(revealPath);
                    if (at >= 0) list.scrollToPosition(at);
                    revealPath = null;
                }
            });
        }).start();
    }

    private static void mergeByFileOrder(List<RowAdapter.Row> l, Comparator<File> cmp) {
        Collections.sort(l, (a, b) -> cmp.compare(new File(a.media != null ? a.media.path : a.key), new File(b.media != null ? b.media.path : b.key)));
    }

    private static Comparator<File> fileOrder(String sort) {
        Comparator<File> byName = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        switch (sort) {
            case "name_desc": return (a, b) -> b.getName().compareToIgnoreCase(a.getName());
            case "new": return (a, b) -> Long.compare(b.lastModified(), a.lastModified());
            case "old": return (a, b) -> Long.compare(a.lastModified(), b.lastModified());
            case "large": return (a, b) -> Long.compare(b.length(), a.length());
            case "small": return (a, b) -> Long.compare(a.length(), b.length());
        }
        return byName;
    }

    private String shortDate(long t) {
        return t <= 0 ? "" : DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_YEAR);
    }

    private boolean atRoot() {
        if (browseDir == null) return true;
        for (File r : FolderPicker.roots(this)) if (r.getAbsolutePath().equals(browseDir.getAbsolutePath())) return true;
        return false;
    }

    private void buildBrowser(List<RowAdapter.Row> out) {
        // storage switch
        storageRow.removeAllViews();
        List<File> roots = FolderPicker.roots(this);
        int usb = 0, dp = Ui.dp(this, 1);
        for (int i = 0; i < roots.size(); i++) {
            File r = roots.get(i);
            String label = i == 0 ? getString(R.string.phone_storage_caps)
                    : (i == 1 && !r.getName().isEmpty() ? getString(R.string.sd_card_caps) : getString(R.string.usb_caps, ++usb));
            TextView chip = new TextView(this);
            chip.setText(label);
            chip.setTypeface(Ui.orbitron(this));
            chip.setTextSize(9.5f);
            chip.setLetterSpacing(0.1f);
            chip.setGravity(Gravity.CENTER);
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(i == 0 ? R.drawable.ic_phone : R.drawable.ic_storage, 0, 0, 0);
            chip.setCompoundDrawablePadding(6 * dp);
            boolean on = browseRoot != null && r.getAbsolutePath().equals(browseRoot.getAbsolutePath());
            int color = getColor(on ? R.color.accent : R.color.muted);
            chip.setTextColor(color);
            chip.setCompoundDrawableTintList(ColorStateList.valueOf(color));
            chip.setBackgroundResource(R.drawable.bg_segment_box);
            chip.setSelected(on);
            chip.setPadding(12 * dp, 0, 14 * dp, 0);
            chip.setOnClickListener(v -> { browseRoot = r; browse(r); refresh(); showHeader(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 32 * dp);
            lp.setMarginEnd(8 * dp);
            storageRow.addView(chip, lp);
        }
        folderPath.setText(browseDir == null ? "" : FolderPicker.pretty(this, roots, browseDir));
        // The note only appears when hidden items are actually on screen (a hide option is off and nothing in
        // this folder is held back). Hidden folders are never counted or mentioned otherwise.
        boolean showingHidden = !(Prefs.hideDot(this) && Prefs.hideNomedia(this) && Prefs.hideThumbDirs(this));
        folderNote.setVisibility(showingHidden && browseHidden == 0 ? View.VISIBLE : View.GONE);
        folderNote.setText(R.string.folders_all_shown);

        // UP · NEW · PASTE: buttons, not rows, so they never look like the files below
        folderActions.removeAllViews();
        if (!atRoot()) folderAction(R.drawable.ic_arrow_up, getString(R.string.up_caps), this::goBack);
        folderAction(R.drawable.ic_add, getString(R.string.new_caps), this::newHere);
        if (FileJobs.hasClip()) {
            folderAction(R.drawable.ic_paste, FileJobs.pasteLabel(this), this::pasteHere);
            folderAction(R.drawable.ic_close, null, () -> { FileJobs.clear(); refresh(); });
        }

        if (Build.VERSION.SDK_INT >= 30 && !HiddenScanner.hasAllFilesAccess(this))
            out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.allow), getString(R.string.folders_need_access), null, "folders_access", R.drawable.ic_lock));
        if (searchHits != null) {                                   // searching: this folder and every folder inside
            out.addAll(searchHits);
            tabHint.setText(searching ? getString(R.string.searching_inside)
                    : getResources().getQuantityString(R.plurals.found_inside, searchHits.size(), searchHits.size()));
            return;
        }
        int songs = 0;
        for (RowAdapter.Row r : browseRows) if (r.media != null && r.media.kind() == MediaInfo.AUDIO) songs++;
        if (songs > 1 && filter.isEmpty())
            out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.play_all_here), null, null, "play_here", R.drawable.ic_play));
        int n = 0;
        for (RowAdapter.Row r : browseRows) if (matchesText(r.title)) { out.add(r); n++; }
        tabHint.setText(getResources().getQuantityString(R.plurals.items, n, n));
    }

    private void folderAction(int icon, String label, Runnable r) {
        int dp = Ui.dp(this, 1);
        TextView b = new TextView(this);
        if (label != null) b.setText(label);
        b.setTypeface(Ui.orbitron(this));
        b.setTextSize(9.5f);
        b.setLetterSpacing(0.1f);
        b.setTextColor(getColor(R.color.accent));
        b.setGravity(Gravity.CENTER);
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        b.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        b.setCompoundDrawablePadding(label == null ? 0 : 6 * dp);
        b.setBackgroundResource(R.drawable.bg_tile_btn);
        b.setPadding(10 * dp, 0, label == null ? 10 * dp : 14 * dp, 0);
        if (label == null) b.setContentDescription(getString(R.string.clear_clip));
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 34 * dp);
        lp.setMarginEnd(8 * dp);
        folderActions.addView(b, lp);
    }

    /** A folder row that can be picked (long-press) like a file. */
    private RowAdapter.Row folderRow(File d, String sub, boolean hid) {
        RowAdapter.Row r = new RowAdapter.Row(RowAdapter.FOLDER, d.getName(), sub, null, d.getAbsolutePath(),
                hid ? R.drawable.ic_eye_off : R.drawable.ic_folder);
        List<MediaInfo> one = new ArrayList<>();
        one.add(folderInfo(d));
        r.pick = one;
        return r;
    }

    // ── Paste into the folder that's open ────────────────────────────────────

    private void pasteHere() {
        if (browseDir == null || !FileJobs.hasClip()) return;
        if (!FileOps.canUseBin(this)) { needAccessFor("folders"); return; }       // writing needs All files access
        final File dir = browseDir;
        FileJobs.pasteLocal(this, dir, (ok, bad) -> {
            if (page == FOLDERS && dir.equals(browseDir)) browse(dir);
            load(true);
        });
        refresh();
    }

    // ── Search inside every folder below the open one ────────────────────────

    private List<RowAdapter.Row> searchHits;
    private boolean searching = false;
    private int searchGen = 0;

    private void deepSearch(String q) {
        final int gen = ++searchGen;
        if (q.isEmpty() || browseDir == null) { searchHits = null; searching = false; refresh(); return; }
        searching = true;
        searchHits = new ArrayList<>();
        refresh();
        final File start = browseDir;
        final boolean hideDot = Prefs.hideDot(this), hideNomedia = Prefs.hideNomedia(this), hideThumbs = Prefs.hideThumbDirs(this);
        final List<File> roots = FolderPicker.roots(this);
        new Thread(() -> {
            List<RowAdapter.Row> found = new ArrayList<>();
            java.util.ArrayDeque<File> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            long lastPost = System.currentTimeMillis();
            while (!queue.isEmpty() && gen == searchGen && found.size() < 1000) {
                File dir = queue.poll();
                File[] kids = dir.listFiles();
                if (kids == null) continue;
                for (File f : kids) {
                    String n = f.getName();
                    boolean isDir = f.isDirectory();
                    if (hideDot && n.startsWith(".")) continue;
                    if (isDir && hideThumbs && n.equalsIgnoreCase(".thumbnails")) continue;
                    if (isDir && hideNomedia && new File(f, ".nomedia").exists()) continue;
                    if (n.toLowerCase(Locale.ROOT).contains(q)) {
                        String where = FolderPicker.pretty(this, roots, dir);
                        if (isDir) found.add(folderRow(f, where, n.startsWith(".")));
                        else found.add(RowAdapter.Row.media(MediaInfo.fromFile(f),
                                Formatter.formatShortFileSize(this, f.length()) + " · " + where));
                    }
                    if (isDir) queue.add(f);
                }
                if (System.currentTimeMillis() - lastPost > 400) {     // show what's found so far
                    lastPost = System.currentTimeMillis();
                    final List<RowAdapter.Row> part = new ArrayList<>(found);
                    ui.post(() -> { if (gen == searchGen) { searchHits = part; refresh(); } });
                }
            }
            ui.post(() -> {
                if (gen != searchGen) return;
                searchHits = found;
                searching = false;
                refresh();
            });
        }).start();
    }

    private void newHere() {
        if (browseDir == null) return;
        Dialogs.builder(this)
                .setItems(new String[]{getString(R.string.new_folder), getString(R.string.new_text_file)}, (d, w) -> {
                    EditText input = new EditText(this);
                    input.setHint(R.string.name_hint);
                    input.setSingleLine(true);
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    if (w == 1) { input.setText(".txt"); input.setSelection(0); }
                    FrameLayout box = new FrameLayout(this);
                    int pad = Ui.dp(this, 20);
                    box.setPadding(pad, pad / 2, pad, 0);
                    box.addView(input);
                    Dialogs.builder(this)
                            .setTitle(w == 0 ? R.string.new_folder : R.string.new_text_file)
                            .setView(box)
                            .setPositiveButton(R.string.create, (d2, w2) -> {
                                String name = input.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
                                if (name.isEmpty() || name.equals(".txt")) return;
                                File f = new File(browseDir, name);
                                if (f.exists()) { Ui.toast(this, R.string.name_taken); return; }
                                boolean ok;
                                try { ok = w == 0 ? f.mkdirs() : f.createNewFile(); } catch (Exception e) { ok = false; }
                                if (!ok) { Ui.toast(this, R.string.couldnt_create); return; }
                                if (w == 0) { browse(f); refresh(); showHeader(); }
                                else { browse(browseDir); TextEditorActivity.open(this, Uri.fromFile(f)); }
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                })
                .show();
    }

    /** Opens any file by its kind: plays, shows, edits, reads, or asks which app. */
    private void openFile(MediaInfo m, List<MediaInfo> siblings) {
        int k = m.kind();
        List<MediaInfo> same = new ArrayList<>();
        if (k == MediaInfo.AUDIO) {
            for (MediaInfo x : siblings) if (x.kind() == MediaInfo.AUDIO) same.add(x);
            playAudio(same, Math.max(0, same.indexOf(m)), false);
            startActivity(new Intent(this, NowPlayingActivity.class));
        } else if (k == MediaInfo.VIDEO || k == MediaInfo.IMAGE) {
            for (MediaInfo x : siblings) if (x.kind() == MediaInfo.VIDEO || x.kind() == MediaInfo.IMAGE) same.add(x);
            if (k == MediaInfo.VIDEO) {
                List<MediaInfo> vids = new ArrayList<>();
                for (MediaInfo x : same) if (x.video) vids.add(x);
                PlayerActivity.open(this, vids, Math.max(0, vids.indexOf(m)));
            } else ViewerActivity.open(this, same, Math.max(0, same.indexOf(m)));
        } else if (k == MediaInfo.TEXT) {
            TextEditorActivity.open(this, m.uri);
        } else if (k == MediaInfo.PDF) {
            PdfActivity.open(this, m.uri);
        } else {
            FileOps.openWith(this, m);
        }
    }

    // ── Playlists & history ──────────────────────────────────────────────────

    private int buildHistory(List<RowAdapter.Row> out) {
        List<History.Entry> h = History.all(this);
        if (!h.isEmpty())
            out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.clear_history), null, null, "clear_history", R.drawable.ic_delete));
        for (History.Entry e : h)
            if (matches(e.media)) out.add(RowAdapter.Row.media(e.media, when(e.when) + " · " + kind(e.media) + subOf(e.media)));
        tabHint.setText(History.enabled(this) ? getResources().getQuantityString(R.plurals.items, h.size(), h.size()) : getString(R.string.history_off_short));
        return History.enabled(this) ? R.string.history_empty : R.string.history_off;
    }

    private int buildPlaylists(List<RowAdapter.Row> out) {
        if (openPlaylist == null) {
            out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.new_playlist), null, null, "new", R.drawable.ic_add));
            List<String> names = Playlists.names(this);
            List<String> ordered = new ArrayList<>();
            for (String f : new String[]{Prefs.FAV_SONGS, Prefs.FAV_VIDEOS, Prefs.FAV_PHOTOS}) if (names.contains(f)) ordered.add(f);
            List<String> rest = new ArrayList<>();
            for (String n : names) if (!ordered.contains(n)) rest.add(n);
            String s = currentSort();
            switch (s) {
                case "name_desc": Collections.sort(rest, String.CASE_INSENSITIVE_ORDER); Collections.reverse(rest); break;
                case "most": Collections.sort(rest, (a, b) -> Integer.compare(Playlists.count(this, b), Playlists.count(this, a))); break;
                case "new": Collections.reverse(rest); break;                 // names() keeps the order they were made
                case "old": break;
                default: Collections.sort(rest, String.CASE_INSENSITIVE_ORDER);
            }
            ordered.addAll(rest);
            for (String name : ordered) {
                if (!matchesText(name)) continue;
                int n = Playlists.count(this, name);
                List<MediaInfo> items = n > 0 ? Playlists.items(this, name) : null;
                out.add(new RowAdapter.Row(RowAdapter.PLAYLIST, name,
                        getResources().getQuantityString(R.plurals.items, n, n), null, name,
                        Prefs.isFavouriteList(name) ? R.drawable.ic_heart_filled : R.drawable.ic_playlist));
                if (items != null && !items.isEmpty() && "grid".equals(view())) {
                    RowAdapter.Row last = out.remove(out.size() - 1);
                    out.add(new RowAdapter.Row(RowAdapter.PLAYLIST, last.title, last.sub, items.get(0), last.key, last.icon));
                }
            }
            tabHint.setText(getResources().getQuantityString(R.plurals.items, names.size(), names.size()));
            return R.string.nothing_here;
        }
        List<MediaInfo> items = Playlists.items(this, openPlaylist);
        out.add(new RowAdapter.Row(RowAdapter.BACK, openPlaylist, getString(R.string.all_playlists), null, null, R.drawable.ic_back));
        if (!items.isEmpty())
            out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.play_all), null, null, "playall", R.drawable.ic_play));
        String s = Prefs.sort(this, "playlist", "order");
        List<MediaInfo> shown = "order".equals(s) ? items : sorted(items, s);
        for (MediaInfo m : shown) if (matches(m)) out.add(RowAdapter.Row.media(m));
        tabHint.setText(getResources().getQuantityString(R.plurals.items, items.size(), items.size()));
        return R.string.empty_playlist;
    }

    // ── Settings (start page: ⌂ in the ☰ menu; sharing: ☰ menu) ─────────────

    private void buildSettings(List<RowAdapter.Row> out) {
        List<RowAdapter.Row> all = new ArrayList<>();
        boolean access = HiddenScanner.hasAllFilesAccess(this);
        all.add(RowAdapter.Row.header(getString(R.string.set_playback)));
        all.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.video_rotation),
                getResources().getStringArray(R.array.orientations)[Prefs.videoOrientation(this)], null, "orient", R.drawable.ic_screen_rotation));
        all.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.video_end),
                getResources().getStringArray(R.array.video_end_choices)[Prefs.videoEnd(this)], null, "video_end", R.drawable.ic_next));
        all.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.np_opens_with),
                DefaultViewActivity.summary(this), null, "np_view", R.drawable.ic_eq));
        all.add(RowAdapter.Row.header(getString(R.string.set_album_art)));
        all.add(RowAdapter.Row.toggle(getString(R.string.dl_art), getString(R.string.dl_art_sub), "auto_art",
                R.drawable.ic_download, Prefs.autoArt(this)));
        all.add(RowAdapter.Row.header(getString(R.string.set_history)));
        all.add(RowAdapter.Row.toggle(getString(R.string.keep_history), getString(R.string.keep_history_sub), "history",
                R.drawable.ic_history, History.enabled(this)));
        all.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.clear_history),
                getResources().getQuantityString(R.plurals.items, History.count(this), History.count(this)), null, "clear_history", R.drawable.ic_delete));
        // ALL FILES: what's hidden here is hidden everywhere (All Files, Audio, Videos, Gallery)
        all.add(RowAdapter.Row.header(getString(R.string.set_folders)));
        all.add(RowAdapter.Row.toggle(getString(R.string.hide_dot), getString(R.string.hide_everywhere_sub), "hide_dot",
                R.drawable.ic_folder, Prefs.hideDot(this)));
        all.add(RowAdapter.Row.toggle(getString(R.string.hide_nomedia), getString(R.string.hide_everywhere_sub), "hide_nomedia",
                R.drawable.ic_folder, Prefs.hideNomedia(this)));
        all.add(RowAdapter.Row.toggle(getString(R.string.hide_thumbdirs), getString(R.string.hide_everywhere_sub), "hide_thumbdirs",
                R.drawable.ic_folder, Prefs.hideThumbDirs(this)));
        if (!access && Build.VERSION.SDK_INT >= 30)
            all.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.allow), getString(R.string.hidden_explain), null, "folders_access", R.drawable.ic_lock));
        all.add(RowAdapter.Row.header(getString(R.string.set_net_perms)));
        all.add(RowAdapter.Row.toggle(getString(R.string.no_internet), getString(R.string.no_internet_sub), "no_internet",
                R.drawable.ic_globe, Prefs.noInternet(this)));
        all.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.reset_perms), getString(R.string.reset_perms_sub), null, "reset_perms", R.drawable.ic_lock));
        all.add(RowAdapter.Row.header(getString(R.string.set_about)));
        all.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.app_name), getString(R.string.version_line), null, "about", R.drawable.ic_info));
        for (RowAdapter.Row r : all) {
            if (r.type == RowAdapter.HEADER || matchesText(r.title) || (r.sub != null && matchesText(r.sub))) out.add(r);
        }
        tabHint.setText("");
    }

    private String when(long t) {
        if (DateUtils.isToday(t)) return getString(R.string.today_at, DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_TIME));
        if (DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS)) return getString(R.string.yesterday_at, DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_TIME));
        return DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
    }

    private String kind(MediaInfo m) { return getString(m.image ? R.string.kind_photo : m.video ? R.string.kind_video : R.string.kind_song); }

    private String subOf(MediaInfo m) { return m.artist.isEmpty() ? "" : " · " + m.artist; }

    // ── Sorting ──────────────────────────────────────────────────────────────

    private String sortPrefKey() {
        String k = pageKey();
        return "videos".equals(k) && openVideoFolder != null ? "videos" : k;
    }

    private String currentSort() {
        if (SORT_DEF[page].isEmpty()) return "";
        String k = sortPrefKey();
        String def = "playlist".equals(k) ? "order" : SORT_DEF[page];
        return Prefs.sort(this, k, def);
    }

    private void chooseSort() {
        final String key = sortPrefKey();
        Boolean ff = page == FOLDERS ? Prefs.foldersFirst(this, "folders") : null;
        String cur = page == VIDEOS || page == SONGS || page == FOLDERS || page == PLAYLISTS || page == PDFS ? view() : null;
        SortSheet.show(this, key, currentSort(), cur, ff, new SortSheet.Listener() {
            @Override public void onSort(String k) {
                Prefs.setSort(MainActivity.this, key, k);
                if (page == FOLDERS) browse(browseDir);
                refresh();
                showHeader();
            }
            @Override public void onView(String v) { setView(v); }
            @Override public void onFoldersFirst(boolean on) {
                Prefs.setFoldersFirst(MainActivity.this, "folders", on);
                browse(browseDir);
            }
        });
    }

    private List<MediaInfo> sorted(List<MediaInfo> src, String key) {
        List<MediaInfo> l = new ArrayList<>(src);
        Comparator<MediaInfo> byName = (a, b) -> a.title.compareToIgnoreCase(b.title);
        switch (key) {
            case "name_desc": Collections.sort(l, (a, b) -> b.title.compareToIgnoreCase(a.title)); break;
            case "artist": Collections.sort(l, (a, b) -> {
                int c = sortText(a.artist).compareToIgnoreCase(sortText(b.artist));
                return c != 0 ? c : byName.compare(a, b);
            }); break;
            case "album": Collections.sort(l, (a, b) -> {
                int c = sortText(a.album).compareToIgnoreCase(sortText(b.album));
                return c != 0 ? c : byName.compare(a, b);
            }); break;
            case "new": Collections.sort(l, (a, b) -> Long.compare(b.dateMs, a.dateMs)); break;
            case "old": Collections.sort(l, (a, b) -> Long.compare(a.dateMs, b.dateMs)); break;
            case "long": Collections.sort(l, (a, b) -> Long.compare(b.durationMs, a.durationMs)); break;
            case "short": Collections.sort(l, (a, b) -> Long.compare(a.durationMs, b.durationMs)); break;
            case "large": Collections.sort(l, (a, b) -> Long.compare(b.size, a.size)); break;
            case "small": Collections.sort(l, (a, b) -> Long.compare(a.size, b.size)); break;
            case "plays": {
                Map<String, Integer> plays = Prefs.playCounts(this);
                Collections.sort(l, (a, b) -> {
                    Integer pa = plays.get(a.key()), pb = plays.get(b.key());
                    int c = Integer.compare(pb == null ? 0 : pb, pa == null ? 0 : pa);
                    return c != 0 ? c : byName.compare(a, b);
                });
                break;
            }
            default: Collections.sort(l, byName);
        }
        return l;
    }

    private static String sortText(String s) { return s == null || s.isEmpty() ? "￿" : s; }   // unknown goes last

    // ── Music squares ────────────────────────────────────────────────────────

    private void refreshSquares() {
        List<MediaInfo> source = new ArrayList<>();
        for (MediaInfo m : audio) if (matches(m)) source.add(m);
        squares.set(Groups.build(this, source));
        if (list.getAdapter() != squares) {
            GridLayoutManager glm = new GridLayoutManager(this, 2);
            glm.setSpanSizeLookup(squares.spans());
            list.setLayoutManager(glm);
            list.setAdapter(squares);
        }
        tabHint.setText(R.string.squares_hint);
        empty.setText(R.string.no_songs);
        empty.setVisibility(loaded && squares.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── Videos grid (pinch for 1–4 per row) ──────────────────────────────────

    private void refreshVideoGrid(List<MediaInfo> src, String folder) {
        List<MediaInfo> shown = new ArrayList<>();
        for (MediaInfo m : sorted(src, currentSort())) if (matches(m)) shown.add(m);
        videoGrid.set(shown);
        int cols = gridCols(Prefs.cols(this, "videos", 2));
        RecyclerView.LayoutManager lm = list.getLayoutManager();
        if (list.getAdapter() != videoGrid || !(lm instanceof GridLayoutManager) || ((GridLayoutManager) lm).getSpanCount() != cols) {
            list.setLayoutManager(new GridLayoutManager(this, cols));
            list.setAdapter(videoGrid);
        }
        videoGrid.setCompact(cols >= 4);
        tabHint.setText(folder != null ? getString(R.string.back_to, folder) + " · " + getResources().getQuantityString(R.plurals.videos, src.size(), src.size())
                : getResources().getQuantityString(R.plurals.videos, video.size(), video.size()));
        tabHint.setOnClickListener(folder != null ? v -> goBack() : null);
        empty.setText(filter.isEmpty() ? R.string.no_videos : R.string.no_matches);
        empty.setVisibility(loaded && shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── Gallery ──────────────────────────────────────────────────────────────

    private void refreshGallery() {
        setSeg(segAll, galleryKind == 0);
        setSeg(segPhotos, galleryKind == 1);
        setSeg(segVideos, galleryKind == 2);

        List<MediaInfo> all = new ArrayList<>();
        if (galleryKind != 2) all.addAll(images);
        if (galleryKind != 1) all.addAll(video);
        String sort = currentSort();
        all = sorted(all, sort);

        Map<String, List<MediaInfo>> byAlbum = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (MediaInfo m : all) {
            List<MediaInfo> l = byAlbum.get(m.folder);
            if (l == null) { l = new ArrayList<>(); byAlbum.put(m.folder, l); }
            l.add(m);
        }
        List<AlbumAdapter.Album> strip = new ArrayList<>();
        strip.add(new AlbumAdapter.Album(ALL_ALBUMS, all.size(), all.isEmpty() ? null : all.get(0), false));
        List<Map.Entry<String, List<MediaInfo>>> entries = new ArrayList<>(byAlbum.entrySet());
        Collections.sort(entries, (a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        for (Map.Entry<String, List<MediaInfo>> e : entries)
            strip.add(new AlbumAdapter.Album(e.getKey(), e.getValue().size(), e.getValue().get(0), e.getValue().get(0).hidden));
        if (!album.equals(ALL_ALBUMS) && !byAlbum.containsKey(album)) album = ALL_ALBUMS;
        albums.set(strip, album);

        List<MediaInfo> shown = new ArrayList<>();
        for (MediaInfo m : all) if ((album.equals(ALL_ALBUMS) || m.folder.equalsIgnoreCase(album)) && matches(m)) shown.add(m);

        int cols = Prefs.galleryCols(this);
        gallery.set(shown, cols, "new".equals(sort) || "old".equals(sort));
        RecyclerView.LayoutManager lm = list.getLayoutManager();
        if (list.getAdapter() != gallery || !(lm instanceof GridLayoutManager) || ((GridLayoutManager) lm).getSpanCount() != cols) {
            GridLayoutManager glm = new GridLayoutManager(this, cols);
            glm.setSpanSizeLookup(gallery.spans());
            list.setLayoutManager(glm);
            list.setAdapter(gallery);
        }
        gridSize.setText(getString(R.string.per_row, cols));
        tabHint.setText(getResources().getQuantityString(R.plurals.items, shown.size(), shown.size()));
        empty.setText(filter.isEmpty() ? R.string.no_gallery : R.string.no_matches);
        empty.setVisibility(loaded && shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── Pinch any grid for more or fewer per row ─────────────────────────────

    private int[] pinchSteps() {
        RecyclerView.Adapter<?> a = list.getAdapter();
        if (a == gallery) return GALLERY_STEPS;
        if (a == videoGrid) return new int[]{1, 2, 3, 4};
        if (a == rows && rows.grid()) return new int[]{2, 3, 4, 5};
        return null;
    }

    private String pinchKey() {
        RecyclerView.Adapter<?> a = list.getAdapter();
        if (a == videoGrid) return "videos";
        return PAGE_KEYS[page];
    }

    private int pinchCols() {
        RecyclerView.Adapter<?> a = list.getAdapter();
        if (a == gallery) return Prefs.galleryCols(this);
        if (a == videoGrid) return Prefs.cols(this, "videos", 2);
        return Prefs.cols(this, PAGE_KEYS[page], 3);
    }

    private void setupPinch() {
        ScaleGestureDetector pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { pinchAcc = 1f; return pinchSteps() != null; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                pinchAcc *= d.getScaleFactor();
                if (pinchAcc > 1.25f) { stepGrid(-1); pinchAcc = 1f; }       // fingers apart: bigger
                else if (pinchAcc < 0.8f) { stepGrid(+1); pinchAcc = 1f; }   // fingers together: more per row
                return true;
            }
        });
        list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                if (pinchSteps() == null) return false;
                pinch.onTouchEvent(e);
                return e.getPointerCount() > 1 || pinch.isInProgress();
            }
            @Override public void onTouchEvent(RecyclerView rv, MotionEvent e) { pinch.onTouchEvent(e); }
        });
    }

    private void stepGrid(int dir) {
        int[] steps = pinchSteps();
        if (steps == null) return;
        int cur = pinchCols(), idx = 0;
        for (int i = 0; i < steps.length; i++) if (steps[i] <= cur) idx = i;
        int next = Math.max(0, Math.min(steps.length - 1, idx + dir));
        if (steps[next] == cur) return;
        RecyclerView.LayoutManager lm = list.getLayoutManager();
        int first = lm instanceof LinearLayoutManager ? ((LinearLayoutManager) lm).findFirstVisibleItemPosition() : 0;
        if (list.getAdapter() == gallery) Prefs.setGalleryCols(this, steps[next]);
        else Prefs.setCols(this, pinchKey(), steps[next]);
        refresh();
        list.scrollToPosition(Math.max(0, first));
        boolean month = list.getAdapter() == gallery && steps[next] >= 5;
        zoomPop.setText(getString(month ? R.string.per_row_month : R.string.per_row, steps[next]));
        zoomPop.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideZoomPop);
        ui.postDelayed(hideZoomPop, 900);
    }

    private final Runnable hideZoomPop = () -> zoomPop.setVisibility(View.GONE);

    /** Gallery opens the viewer with photos and videos together; the Videos grid opens the player. */
    private void openGridItem(MediaInfo m, List<MediaInfo> all) {
        if (page == GALLERY) {
            ViewerActivity.open(this, all, Math.max(0, all.indexOf(m)));
            return;
        }
        List<MediaInfo> vids = new ArrayList<>();
        for (MediaInfo x : all) if (x.video) vids.add(x);
        PlayerActivity.open(this, vids, Math.max(0, vids.indexOf(m)));
    }

    // ── Side scroll bar label ────────────────────────────────────────────────

    private String labelFor(int pos) {
        RecyclerView.Adapter<?> a = list.getAdapter();
        String sort = currentSort();
        boolean byDate = "new".equals(sort) || "old".equals(sort);
        if (a == gallery) return gallery.label(pos, true);
        MediaInfo m = null;
        String text = null;
        if (a == videoGrid) m = videoGrid.at(pos);
        else if (a == rows && pos < rows.rows().size()) {
            RowAdapter.Row r = rows.rows().get(pos);
            if (r.type == RowAdapter.MEDIA) m = r.media;
            else if (r.type == RowAdapter.FOLDER || r.type == RowAdapter.PLAYLIST) text = r.title;
        }
        if (m != null) {
            if (byDate || page == HISTORY) return m.dateMs > 0 ? new SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(new Date(m.dateMs)) : "";
            if ("long".equals(sort) || "short".equals(sort)) return Ui.time(m.durationMs);
            if ("large".equals(sort) || "small".equals(sort)) return Formatter.formatShortFileSize(this, m.size);
            if ("artist".equals(sort)) text = m.artist;
            else if ("album".equals(sort)) text = m.album;
            else text = m.title;
        }
        if (text == null || text.isEmpty()) return "";
        return text.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    // ── Multi-select & delete ────────────────────────────────────────────────

    @Override
    public void onSelectionChanged() {
        selectBar.setVisibility(sel.active() ? View.VISIBLE : View.GONE);
        selCount.setText(getString(R.string.n_selected, sel.count()));
        RecyclerView.Adapter<?> a = list.getAdapter();
        if (a != null) a.notifyDataSetChanged();
    }

    private List<MediaInfo> visibleMedia() {
        RecyclerView.Adapter<?> a = list.getAdapter();
        if (a == gallery) return new ArrayList<>(gallery.media());
        if (a == videoGrid) return new ArrayList<>(videoGrid.items());
        List<MediaInfo> out = new ArrayList<>();
        for (RowAdapter.Row r : rows.rows()) if (r.type == RowAdapter.MEDIA) out.add(r.media);
        return out;
    }

    private void deleteItems(List<MediaInfo> items) {
        deleter.delete(items, new FileOps.Deleted() {
            @Override public void done(Set<String> gone) { takeAway(gone); sel.clear(); }
            @Override public void restored() { load(true); if (page == FOLDERS) browse(browseDir); }
        });
    }

    /** Out of sight at once, everywhere in the library. */
    private void takeAway(Set<String> gone) {
        removeGone(audio, gone); removeGone(video, gone); removeGone(images, gone); removeGone(pdfs, gone);
        for (int i = browseRows.size() - 1; i >= 0; i--) {
            RowAdapter.Row r = browseRows.get(i);
            MediaInfo m = r.media != null ? r.media : r.pick != null && !r.pick.isEmpty() ? r.pick.get(0) : null;
            if (m != null && gone.contains(m.key())) browseRows.remove(i);
        }
        if (searchHits != null) for (int i = searchHits.size() - 1; i >= 0; i--) {
            RowAdapter.Row r = searchHits.get(i);
            MediaInfo m = r.media != null ? r.media : r.pick != null && !r.pick.isEmpty() ? r.pick.get(0) : null;
            if (m != null && gone.contains(m.key())) searchHits.remove(i);
        }
        refresh();
    }

    /** Media and folders on screen that can be picked. */
    private List<MediaInfo> visiblePickable() {
        RecyclerView.Adapter<?> a = list.getAdapter();
        if (a != rows) return visibleMedia();
        List<MediaInfo> out = new ArrayList<>();
        for (RowAdapter.Row r : rows.rows()) {
            if (r.type == RowAdapter.MEDIA) out.add(r.media);
            else if (r.type == RowAdapter.FOLDER && r.pick != null) out.addAll(r.pick);
        }
        return out;
    }

    private static boolean isFolder(MediaInfo m) {
        File f = FileJobs.localFile(m);
        return m.other && f != null && f.isDirectory();
    }

    private static List<MediaInfo> filesOnly(List<MediaInfo> l) {
        List<MediaInfo> out = new ArrayList<>();
        for (MediaInfo m : l) if (!isFolder(m)) out.add(m);
        return out;
    }

    private void selectionMenu() {
        final List<MediaInfo> items = sel.items();
        String[] opts = {getString(R.string.copy_files), getString(R.string.cut), getString(R.string.add_to_playlist)};
        Dialogs.builder(this).setItems(opts, (d, w) -> {
            if (w == 0 || w == 1) { FileJobs.put(this, items, w == 1, null); sel.clear(); refresh(); }
            else {
                List<MediaInfo> media = new ArrayList<>();
                for (MediaInfo m : filesOnly(items)) if (!m.other) media.add(m);
                Dialogs.addToPlaylist(this, media, sel::clear);
            }
        }).show();
    }

    /** ⓘ for everything picked: how many, total size, what kinds, total length, dates. */
    private void selectionDetails(List<MediaInfo> items) {
        if (items.isEmpty()) return;
        if (items.size() == 1) { properties(items.get(0)); return; }
        final android.app.AlertDialog dlg = Dialogs.builder(this)
                .setTitle(getResources().getQuantityString(R.plurals.info_title, items.size(), items.size()))
                .setMessage(R.string.loading)
                .setPositiveButton(R.string.close_caps, null)
                .show();
        new Thread(() -> {
            long size = 0, ms = 0, first = Long.MAX_VALUE, last = 0;
            int au = 0, vi = 0, ph = 0, ot = 0, dirs = 0, inside = 0;
            for (MediaInfo m : items) {
                File f = FileJobs.localFile(m);
                if (m.other && f != null && f.isDirectory()) {
                    dirs++;
                    size += FileJobs.sizeOf(f);
                    inside += countFiles(f);
                    continue;
                }
                long sz = m.size > 0 ? m.size : f != null ? f.length() : 0;
                size += sz;
                ms += m.durationMs;
                int k = m.kind();
                if (k == MediaInfo.AUDIO) au++; else if (k == MediaInfo.VIDEO) vi++; else if (k == MediaInfo.IMAGE) ph++; else ot++;
                long t = m.dateMs > 0 ? m.dateMs : f != null ? f.lastModified() : 0;
                if (t > 0) { first = Math.min(first, t); last = Math.max(last, t); }
            }
            StringBuilder b = new StringBuilder();
            b.append(getString(R.string.info_total)).append(": ").append(Formatter.formatFileSize(this, size)).append('\n');
            b.append(getString(R.string.info_contains)).append(": ").append(getString(R.string.info_kinds, au, vi, ph, ot));
            if (dirs > 0) b.append('\n').append(getString(R.string.info_folders, dirs, inside));
            if (ms > 0) b.append('\n').append(getString(R.string.info_length)).append(": ").append(Ui.time(ms));
            if (last > 0) b.append('\n').append(getString(R.string.info_dates)).append(": ").append(shortDate(first))
                    .append(first == last ? "" : " – " + shortDate(last));
            final String text = b.toString();
            ui.post(() -> { if (dlg.isShowing()) dlg.setMessage(text); });
        }).start();
    }

    private static int countFiles(File d) {
        File[] k = d.listFiles();
        if (k == null) return 0;
        int n = 0;
        for (File f : k) n += f.isDirectory() ? countFiles(f) : 1;
        return n;
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        deleter.onActivityResult(requestCode, resultCode);
    }

    private static void removeGone(List<MediaInfo> l, Set<String> gone) {
        for (int i = l.size() - 1; i >= 0; i--) if (gone.contains(l.get(i).key())) l.remove(i);
    }

    private void properties(MediaInfo m) {
        FileOps.properties(this, m, deleter, new FileOps.Deleted() {
            @Override public void done(Set<String> gone) { takeAway(gone); }
            @Override public void restored() { load(true); if (page == FOLDERS) browse(browseDir); }
        }, () -> {
            if (page == FOLDERS) browse(browseDir); else load(true);
        });
    }

    // ── Clicks ───────────────────────────────────────────────────────────────

    /** 0 songs, 1 videos, 2 photos — the visible rows of that kind. */
    private List<MediaInfo> visible(int kind) {
        List<MediaInfo> out = new ArrayList<>();
        for (RowAdapter.Row r : rows.rows()) {
            if (r.type != RowAdapter.MEDIA || r.media.other) continue;
            int k = r.media.image ? 2 : r.media.video ? 1 : 0;
            if (k == kind) out.add(r.media);
        }
        return out;
    }

    private void openMedia(MediaInfo m) {
        if (page == FOLDERS || m.other || searchHits != null) { openFile(m, visibleMedia()); return; }
        int kind = m.image ? 2 : m.video ? 1 : 0;
        List<MediaInfo> same = visible(kind);
        int idx = Math.max(0, same.indexOf(m));
        if (kind == 2) ViewerActivity.open(this, same, idx);
        else if (kind == 1) PlayerActivity.open(this, same, idx);
        else playAudio(same, idx, false);
    }

    @Override
    public void onClick(RowAdapter.Row r, int position) {
        switch (r.type) {
            case RowAdapter.MEDIA:
                if (sel.active()) { sel.toggle(r.media); return; }
                openMedia(r.media);
                break;
            case RowAdapter.FOLDER:
                if (sel.active() && r.pick != null) { sel.toggleAll(r.pick); return; }
                rememberScroll();
                if (r.key.startsWith("vf:")) openVideoFolder = r.key.substring(3);
                else { browse(new File(r.key)); }
                refresh();
                showHeader();
                break;
            case RowAdapter.PLAYLIST: rememberScroll(); openPlaylist = r.key; refresh(); showHeader(); break;
            case RowAdapter.BACK: goBack(); break;
            case RowAdapter.ACTION: onAction(r.key); break;
        }
    }

    private void onAction(String key) {
        switch (key) {
            case "new": Dialogs.newPlaylist(this, name -> refresh()); break;
            case "playall": playPlaylist(); break;
            case "clear_history":
                Dialogs.builder(this).setMessage(R.string.clear_history_confirm)
                        .setPositiveButton(R.string.clear, (d, w) -> { History.clear(this); refresh(); })
                        .setNegativeButton(R.string.cancel, null).show();
                break;
            case "eq": startActivity(new Intent(this, EqualizerActivity.class)); break;
            case "orient":
                Dialogs.builder(this).setTitle(R.string.video_rotation)
                        .setSingleChoiceItems(getResources().getStringArray(R.array.orientations), Prefs.videoOrientation(this), (d, w) -> {
                            Prefs.setVideoOrientation(this, w);
                            d.dismiss();
                            refresh();
                        }).setNegativeButton(R.string.cancel, null).show();
                break;
            case "video_end":
                Dialogs.builder(this).setTitle(R.string.video_end)
                        .setSingleChoiceItems(getResources().getStringArray(R.array.video_end_choices), Prefs.videoEnd(this), (d, w) -> {
                            Prefs.setVideoEnd(this, w);
                            d.dismiss();
                            refresh();
                        }).setNegativeButton(R.string.cancel, null).show();
                break;
            case "k_strength":
                Dialogs.builder(this).setTitle(R.string.karaoke_strength)
                        .setSingleChoiceItems(getResources().getStringArray(R.array.karaoke_strengths), Prefs.karaokeStrength(this), (d, w) -> {
                            Prefs.setKaraokeStrength(this, w);
                            d.dismiss();
                            refresh();
                        }).setNegativeButton(R.string.cancel, null).show();
                break;
            case "folders_access": needAccessFor("folders"); break;
            case "reset_perms": confirmResetPermissions(); break;
            case "np_view": startActivity(new Intent(this, DefaultViewActivity.class)); break;
            case "new_here": newHere(); break;
            case "play_here": {
                List<MediaInfo> songs = new ArrayList<>();
                for (MediaInfo m : visibleMedia()) if (m.kind() == MediaInfo.AUDIO) songs.add(m);
                if (!songs.isEmpty()) { playAudio(songs, 0, false); startActivity(new Intent(this, NowPlayingActivity.class)); }
                break;
            }
            case "sq_play": if (openSquare != null) playAudio(openSquare.songs, 0, false); break;
            case "sq_shuffle": if (openSquare != null) playAudio(openSquare.songs, 0, true); break;
            case "sq_save":
                if (openSquare == null) break;
                final List<MediaInfo> songs = openSquare.songs;
                Dialogs.newPlaylist(this, name -> {
                    for (MediaInfo m : songs) Playlists.add(this, name, m);
                    Ui.toast(this, getString(R.string.added_to, name));
                });
                break;
        }
    }

    /** One level up. Returns false when already at the top of the page. */
    private boolean goBack() {
        if (page == FOLDERS) {
            if (atRoot()) return false;
            File parent = browseDir.getParentFile();
            if (parent == null) return false;
            browse(parent);
            refresh();
            restoreScroll();
            return true;
        }
        if (openPlaylist == null && openSquare == null && openVideoFolder == null) return false;
        openPlaylist = null; openSquare = null; openVideoFolder = null;
        tabHint.setOnClickListener(null);
        refresh();
        collapse.setTranslationY(0);
        restoreScroll();
        return true;
    }

    private void playPlaylist() {
        List<MediaInfo> songs = visible(0), vids = visible(1), photos = visible(2);
        if (!songs.isEmpty()) playAudio(songs, 0, false);
        else if (!vids.isEmpty()) PlayerActivity.open(this, vids, 0);
        else if (!photos.isEmpty()) ViewerActivity.open(this, photos, 0);
    }

    @Override
    public void onLongClick(RowAdapter.Row r, int position) {
        if (r.type == RowAdapter.PLAYLIST) { playlistMenu(r); return; }
        if (r.type == RowAdapter.FOLDER) { if (r.pick != null) sel.toggleAll(r.pick); return; }
        if (r.type != RowAdapter.MEDIA) return;
        if (sel.active()) sel.toggle(r.media); else sel.start(r.media);
    }

    @Override
    public void onMore(RowAdapter.Row r, int position) {
        if (r.type == RowAdapter.PLAYLIST) { playlistMenu(r); return; }
        if (r.type == RowAdapter.FOLDER && r.key.startsWith("/")) { folderMenu(folderInfo(new File(r.key))); return; }
        if (r.type == RowAdapter.FOLDER && r.key.startsWith("vf:") && r.pick != null) { videoFolderMenu(r); return; }
        if (r.type == RowAdapter.MEDIA) itemMenu(r.media);
    }

    /** ⋮ on a folder in the Videos folder view: open, select, rename, delete, properties. */
    private void videoFolderMenu(RowAdapter.Row r) {
        final String name = r.key.substring(3);
        File dir = null;
        for (MediaInfo v : r.pick) if (!v.path.isEmpty()) { dir = new File(v.path).getParentFile(); break; }
        final File folder = dir;
        String[] opts = {getString(R.string.open), getString(R.string.select), getString(R.string.rename),
                getString(R.string.delete_file), getString(R.string.properties)};
        Dialogs.builder(this).setTitle(name).setItems(opts, (d, w) -> {
            switch (w) {
                case 0: openVideoFolder = name; refresh(); showHeader(); break;
                case 1: sel.toggleAll(r.pick); break;
                case 2:
                    if (folder == null) { Ui.toast(this, R.string.rename_failed); break; }
                    FileOps.rename(this, folderInfo(folder), () -> load(true));
                    break;
                case 3: deleteItems(new ArrayList<>(r.pick)); break;
                default: if (folder != null) properties(folderInfo(folder)); else selectionDetails(r.pick);
            }
        }).show();
    }

    private void folderMenu(MediaInfo m) {
        String[] opts = {getString(R.string.open), getString(R.string.select), getString(R.string.copy_files), getString(R.string.cut),
                getString(R.string.rename), getString(R.string.delete_file), getString(R.string.properties)};
        Dialogs.builder(this).setTitle(m.fileName).setItems(opts, (d, w) -> {
            List<MediaInfo> one = new ArrayList<>();
            one.add(m);
            switch (w) {
                case 0: browse(new File(m.path)); refresh(); showHeader(); break;
                case 1: sel.toggleAll(one); break;
                case 2: case 3: FileJobs.put(this, one, w == 3, null); refresh(); break;
                case 4: FileOps.rename(this, m, () -> browse(browseDir)); break;
                case 5: deleteItems(one); break;
                default: properties(m);
            }
        }).show();
    }

    private static MediaInfo folderInfo(File d) {
        MediaInfo m = MediaInfo.fromFile(d);
        m.other = true;
        return m;
    }

    private void playlistMenu(RowAdapter.Row r) {
        Dialogs.builder(this)
                .setTitle(r.title)
                .setItems(new String[]{getString(R.string.delete_playlist)}, (d, w) ->
                        Dialogs.builder(this)
                                .setMessage(getString(R.string.delete_confirm, r.title))
                                .setPositiveButton(R.string.delete, (d2, w2) -> { Playlists.delete(this, r.title); refresh(); })
                                .setNegativeButton(R.string.cancel, null)
                                .show())
                .show();
    }

    private void itemMenu(MediaInfo m) {
        final boolean inPlaylist = openPlaylist != null;
        boolean media = !m.other;
        boolean fav = media && Prefs.isFavourite(this, m);
        List<String> opts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        opts.add(getString(m.image || m.other ? R.string.open : R.string.play)); ids.add("play");
        if (media) {
            opts.add(getString(fav ? R.string.unfavourite : R.string.favourite)); ids.add("fav");
            opts.add(getString(R.string.add_to_playlist)); ids.add("add");
        }
        if (media && !m.video && !m.image) {
            opts.add(getString(R.string.edit_info)); ids.add("edit");
            opts.add(getString(R.string.set_as_ringtone)); ids.add("ringtone");
        }
        opts.add(getString(R.string.share)); ids.add("share");
        opts.add(getString(R.string.select)); ids.add("select");
        if (inPlaylist) { opts.add(getString(R.string.remove_from_playlist)); ids.add("remove"); }
        opts.add(getString(R.string.copy_files)); ids.add("copy");
        opts.add(getString(R.string.cut)); ids.add("cut");
        opts.add(getString(R.string.rename)); ids.add("rename");
        opts.add(getString(R.string.delete_file)); ids.add("delete");
        if (m.image) { opts.add(getString(R.string.set_wallpaper)); ids.add("wallpaper"); }
        opts.add(getString(R.string.properties)); ids.add("props");
        Dialogs.builder(this)
                .setTitle(m.other ? m.fileName : m.title)
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    switch (ids.get(which)) {
                        case "play": openMedia(m); break;
                        case "fav":
                            Ui.toast(this, Prefs.toggleFavourite(this, m)
                                    ? getString(R.string.fav_added_to, Prefs.favouritesFor(m)) : getString(R.string.fav_removed_from, Prefs.favouritesFor(m)));
                            if (page == PLAYLISTS) refresh();
                            break;
                        case "add": Dialogs.addToPlaylist(this, m); break;
                        case "edit": MetaEditActivity.open(this, m); break;
                        case "ringtone": Ringtones.set(this, m); break;
                        case "share": Share.send(this, m); break;
                        case "select": sel.start(m); break;
                        case "props": properties(m); break;
                        case "copy": case "cut": {
                            List<MediaInfo> one = new ArrayList<>();
                            one.add(m);
                            FileJobs.put(this, one, ids.get(which).equals("cut"), null);
                            if (page == FOLDERS) refresh();
                            break;
                        }
                        case "rename": FileOps.rename(this, m, () -> { if (page == FOLDERS) browse(browseDir); else load(true); }); break;
                        case "wallpaper": Wallpapers.set(this, m); break;
                        case "remove": {
                            List<MediaInfo> items = Playlists.items(this, openPlaylist);
                            for (int i = 0; i < items.size(); i++)
                                if (items.get(i).key().equals(m.key())) { Playlists.remove(this, openPlaylist, i); break; }
                            refresh();
                            break;
                        }
                        case "delete": {
                            List<MediaInfo> one = new ArrayList<>();
                            one.add(m);
                            deleteItems(one);
                            break;
                        }
                    }
                })
                .show();
    }

    // ── Online links ─────────────────────────────────────────────────────────

    private void openLinkDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.link_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        FrameLayout box = new FrameLayout(this);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        Dialogs.builder(this)
                .setTitle(R.string.open_link)
                .setMessage(R.string.link_help)
                .setView(box)
                .setPositiveButton(R.string.play_as_audio, (d, w) -> playLink(input.getText().toString(), false))
                .setNeutralButton(R.string.play_as_video, (d, w) -> playLink(input.getText().toString(), true))
                .setNegativeButton(R.string.download_caps, (d, w) -> {
                    String s = input.getText().toString().trim();
                    if (!s.startsWith("http://") && !s.startsWith("https://")) { Ui.toast(this, R.string.link_invalid); return; }
                    if (Net.off()) { Ui.toast(this, R.string.net_off_toast); return; }
                    if (YouTube.isYouTube(s)) { Ui.toast(this, R.string.youtube_no_download); return; }
                    Downloads.start(this, Uri.parse(s), null);
                })
                .show();
    }

    /** Settings → INTERNET & PERMISSIONS → Remove all permissions: a fresh start, NKT asks for everything again. */
    private void confirmResetPermissions() {
        Dialogs.builder(this)
                .setTitle(R.string.reset_perms_title)
                .setMessage(R.string.reset_perms_msg)
                .setPositiveButton(R.string.reset_perms_go, (d, w) -> resetPermissions())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void resetPermissions() {
        boolean allFiles = Build.VERSION.SDK_INT >= 30 && HiddenScanner.hasAllFilesAccess(this);
        if (Build.VERSION.SDK_INT >= 33) {
            // Android removes these when the app closes
            List<String> granted = new ArrayList<>();
            String[] all = {Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO};
            for (String p : all) if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) granted.add(p);
            try { if (!granted.isEmpty()) revokeSelfPermissionsOnKill(granted); } catch (Throwable ignored) { }
            if (allFiles) {                                           // this one only Android's own page can switch off
                Ui.toast(this, R.string.reset_perms_files);
                try { startActivity(HiddenScanner.allFilesAccessIntent(this)); } catch (Throwable ignored) { }
            }
            ui.postDelayed(() -> { finishAndRemoveTask(); android.os.Process.killProcess(android.os.Process.myPid()); }, allFiles ? 1800 : 500);
        } else {
            // before Android 13 an app cannot take its own permissions away: the phone's page does it
            Ui.toast(this, R.string.reset_perms_manual);
            try {
                startActivity(allFiles ? HiddenScanner.allFilesAccessIntent(this)
                        : new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) { }
        }
    }

    private void playLink(String text, boolean asVideo) {
        if (Net.off()) { Ui.toast(this, R.string.net_off_toast); return; }
        String s = text.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            Ui.toast(this, R.string.link_invalid);
            return;
        }
        if (YouTube.isYouTube(s)) {
            YouTubeActivity.open(this, s);
            return;
        }
        Uri uri = Uri.parse(s);
        MediaInfo m = MediaInfo.link(uri, Ui.displayName(this, uri), asVideo);
        List<MediaInfo> one = new ArrayList<>();
        one.add(m);
        if (asVideo) PlayerActivity.open(this, one, 0);
        else {
            playAudio(one, 0, false);
            startActivity(new Intent(this, NowPlayingActivity.class));
        }
    }

    // ── Background player ────────────────────────────────────────────────────

    private void playAudio(List<MediaInfo> list, int index, boolean shuffle) {
        final List<MediaInfo> copy = new ArrayList<>(list);
        if (copy.isEmpty()) return;
        Runnable go = () -> {
            controller.setShuffleModeEnabled(shuffle);
            controller.setMediaItems(Media.items(copy), shuffle ? (int) (Math.random() * copy.size()) : index, 0);
            controller.prepare();
            controller.play();
        };
        if (controller != null) go.run(); else pending = go;
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) {
            // your own music started: the online song pauses (only one thing plays at a time)
            if (player.isPlaying() && online != null && online.isPlaying()) online.pause();
            updateMini();
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        future = Media.connect(this);
        future.addListener(() -> {
            try {
                controller = future.get();
                controller.addListener(playerListener);
                updateMini();
                if (pending != null) { Runnable p = pending; pending = null; p.run(); }
            } catch (Exception ignored) { }
        }, ui::post);
    }

    @Override
    protected void onStop() {
        if (controller != null) controller.removeListener(playerListener);
        controller = null;
        MediaController.releaseFuture(future);
        super.onStop();
    }

    private void updateMini() {
        boolean queued = controller != null && controller.getMediaItemCount() > 0 && controller.getCurrentMediaItem() != null;
        btnMini.setVisibility(queued ? View.VISIBLE : View.GONE);
        if (queued) {
            boolean on = controller.isPlaying();
            btnMini.setImageResource(on ? R.drawable.ic_pause : R.drawable.ic_play);
            btnMini.setContentDescription(getString(on ? R.string.cd_pause : R.string.cd_play));
        }
        if (controller == null || controller.getMediaItemCount() == 0 || controller.getCurrentMediaItem() == null) {
            mini.setVisibility(View.GONE);
            rows.setPlaying(null);
            return;
        }
        // playing: on every page. Paused or closed: only on the Audio page, ready to carry on
        boolean show = controller.isPlaying() || controller.getPlayWhenReady() || page == SONGS;
        mini.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) { rows.setPlaying(controller.getCurrentMediaItem().mediaId); return; }
        MediaMetadata md = controller.getMediaMetadata();
        miniTitle.setText(md.title == null ? getString(R.string.unknown) : md.title);
        miniSub.setText(Media.isVideo(md) ? getString(R.string.video_in_background) : md.artist == null ? "" : md.artist);
        miniPlay.setImageResource(controller.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        Bitmap art = null;
        if (md.artworkData != null) {
            try { art = BitmapFactory.decodeByteArray(md.artworkData, 0, md.artworkData.length); } catch (Exception ignored) { }
        }
        if (art != null) Thumbs.showBitmap(miniArt, art);
        else Thumbs.showPlaceholder(miniArt, Media.isVideo(md) ? R.drawable.ic_movie : R.drawable.ic_music);
        rows.setPlaying(controller.getCurrentMediaItem().mediaId);
    }

    /** Back: menu → selection → search → one level up → the start page → leave. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (drawerOpen()) { closeDrawer(); return; }
        if (sel.active()) { sel.clear(); return; }
        if (search.getText().length() > 0) { search.setText(""); return; }
        if (goBack()) return;
        int start = Arrays.asList(PAGE_KEYS).indexOf(Prefs.startPage(this));
        if (start >= 0 && page != start) { setPage(start); return; }
        super.onBackPressed();
    }
}
