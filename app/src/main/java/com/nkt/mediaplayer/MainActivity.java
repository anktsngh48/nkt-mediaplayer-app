package com.nkt.mediaplayer;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
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
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Library: Songs (list or squares) · Videos (list or grid) · Gallery · Folders · Playlists · Settings. */
public class MainActivity extends Activity implements RowAdapter.Listener, Selection.Changed {

    private static final int TAB_SONGS = 0, TAB_VIDEOS = 1, TAB_GALLERY = 2, TAB_FOLDERS = 3, TAB_PLAYLISTS = 4, TAB_SETTINGS = 5;
    private static final int REQ_PERMS = 11, REQ_LEGACY_WRITE = 12, REQ_DELETE = 13, REQ_DELETE_PERM = 14;
    private static final int[] GRID_STEPS = {2, 3, 4, 5, 7};
    private static final String ALL_ALBUMS = "All";
    private static final String[] TAB_SORT_KEY = {"songs", "videos", "gallery", "folders", "", ""};
    private static final String[] TAB_SORT_DEF = {"name", "new", "new", "name", "", ""};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<MediaInfo> audio = new ArrayList<>();
    private final List<MediaInfo> video = new ArrayList<>();
    private final List<MediaInfo> images = new ArrayList<>();

    private RecyclerView list;
    private RowAdapter rows;
    private GalleryAdapter gallery;
    private VideoGridAdapter videoGrid;
    private SquareAdapter squares;
    private AlbumAdapter albums;
    private Selection sel;
    private FastScroller scroller;
    private TextView[] tabs;
    private TextView empty, zoomPop, gridSize, tabHint, sortBtn, viewList, viewGrid, segAll, segPhotos, segVideos, selCount;
    private View permBox, mini, tabBar, viewSwitch, galleryBar, collapse, selectBar;
    private ImageView miniArt;
    private TextView miniTitle, miniSub;
    private ImageButton miniPlay;
    private EditText search;

    private int tab = TAB_SONGS;
    private String openFolder, openPlaylist;
    private boolean openHistory = false;
    private Groups.Group openSquare;
    private String filter = "";
    private String album = ALL_ALBUMS;
    private int galleryKind = 0;              // 0 all, 1 photos, 2 videos
    private boolean loaded = false, loading = false;
    private String pendingAccess = null;      // "hidden" / "thumbs" waiting for All files access
    private float pinchAcc = 1f;
    private List<MediaInfo> pendingDelete;

    private ListenableFuture<MediaController> future;
    private MediaController controller;
    private Runnable pending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.padForSystemBars(findViewById(R.id.root));
        Prefs.migrateFavourites(this);

        TextView title = findViewById(R.id.header_title);
        title.setTypeface(Ui.orbitronBlack(this));
        Ui.gradient(title);

        tabs = new TextView[]{findViewById(R.id.tab_songs), findViewById(R.id.tab_videos), findViewById(R.id.tab_gallery),
                findViewById(R.id.tab_folders), findViewById(R.id.tab_playlists), findViewById(R.id.tab_settings)};
        for (int i = 0; i < tabs.length; i++) {
            final int t = i;
            tabs[i].setTypeface(Ui.orbitron(this));
            tabs[i].setOnClickListener(v -> setTab(t));
        }

        sel = new Selection(this);
        list = findViewById(R.id.list);
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
        sortBtn = findViewById(R.id.btn_sort);
        sortBtn.setTypeface(Ui.orbitron(this));
        sortBtn.setOnClickListener(v -> chooseSort());
        viewSwitch = findViewById(R.id.view_switch);
        galleryBar = findViewById(R.id.gallery_bar);
        viewList = findViewById(R.id.view_list);
        viewGrid = findViewById(R.id.view_grid);
        segAll = findViewById(R.id.seg_all);
        segPhotos = findViewById(R.id.seg_photos);
        segVideos = findViewById(R.id.seg_videos);
        for (TextView t : new TextView[]{viewList, viewGrid, segAll, segPhotos, segVideos}) t.setTypeface(Ui.orbitron(this));
        viewList.setOnClickListener(v -> setGridView(false));
        viewGrid.setOnClickListener(v -> setGridView(true));
        segAll.setOnClickListener(v -> { galleryKind = 0; refresh(); });
        segPhotos.setOnClickListener(v -> { galleryKind = 1; refresh(); });
        segVideos.setOnClickListener(v -> { galleryKind = 2; refresh(); });

        // multi-select bar
        selectBar = findViewById(R.id.select_bar);
        selCount = findViewById(R.id.sel_count);
        findViewById(R.id.sel_close).setOnClickListener(v -> sel.clear());
        findViewById(R.id.sel_all).setOnClickListener(v -> sel.addAll(visibleMedia()));
        findViewById(R.id.sel_share).setOnClickListener(v -> Share.sendMany(this, sel.items()));
        findViewById(R.id.sel_playlist).setOnClickListener(v -> Dialogs.addToPlaylist(this, sel.items(), sel::clear));
        findViewById(R.id.sel_delete).setOnClickListener(v -> deleteItems(sel.items()));

        // side scroll bar
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
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                refresh();
            }
        });

        findViewById(R.id.btn_link).setOnClickListener(v -> openLinkDialog());
        findViewById(R.id.btn_share_pc).setOnClickListener(v -> startActivity(new Intent(this, NktShareActivity.class)));

        mini = findViewById(R.id.mini);
        miniArt = findViewById(R.id.mini_art);
        miniArt.setClipToOutline(true);
        miniTitle = findViewById(R.id.mini_title);
        miniSub = findViewById(R.id.mini_sub);
        miniPlay = findViewById(R.id.mini_play);
        miniPlay.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.isPlaying()) controller.pause(); else controller.play();
        });
        findViewById(R.id.mini_next).setOnClickListener(v -> { if (controller != null) controller.seekToNextMediaItem(); });
        findViewById(R.id.mini_close).setOnClickListener(v -> {
            if (controller == null) return;
            controller.stop();
            controller.clearMediaItems();
            mini.setVisibility(View.GONE);
        });
        mini.setOnClickListener(v -> startActivity(new Intent(this, NowPlayingActivity.class)));

        setTab(TAB_SONGS);
        if (hasPermission()) load(); else showPermissionBox();
    }

    // ── Search bar & folder strip slide away while scrolling ─────────────────

    private void setupCollapsingHeader() {
        collapse.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = collapse.getHeight();
            if (list.getPaddingTop() != h) {
                list.setPadding(list.getPaddingLeft(), h, list.getPaddingRight(), list.getPaddingBottom());
            }
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
        if (requestCode == REQ_PERMS && hasPermission()) load();
        if (requestCode == REQ_LEGACY_WRITE && HiddenScanner.hasAllFilesAccess(this)) accessGranted();
        if (requestCode == REQ_DELETE_PERM && pendingDelete != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) confirmAndDelete(pendingDelete);
            else Ui.toast(this, R.string.delete_failed);
        }
    }

    private void load() {
        if (loading) return;
        loading = true;
        permBox.setVisibility(View.GONE);
        if (!loaded) { empty.setText(R.string.loading); empty.setVisibility(View.VISIBLE); }
        final boolean access = HiddenScanner.hasAllFilesAccess(this);
        final boolean withHidden = Prefs.showHidden(this) && access;
        final boolean withThumbs = Prefs.showThumbnails(this) && access;
        new Thread(() -> {
            List<MediaInfo> a = MediaLibrary.audio(this);
            List<MediaInfo> v = MediaLibrary.video(this);
            List<MediaInfo> im = MediaLibrary.images(this);
            // .thumbnails folders have their own switch, even if the phone's library lists them
            if (!withThumbs) {
                dropThumbs(a); dropThumbs(v); dropThumbs(im);
            }
            if (withHidden || withThumbs) {
                HiddenScanner.Result h = HiddenScanner.scan(withHidden, withThumbs);
                addMissing(a, h.audio);
                addMissing(v, h.video);
                addMissing(im, h.images);
            }
            ui.post(() -> {
                audio.clear(); audio.addAll(a);
                video.clear(); video.addAll(v);
                images.clear(); images.addAll(im);
                loaded = true;
                loading = false;
                refresh();
            });
        }).start();
    }

    private static void dropThumbs(List<MediaInfo> l) {
        for (int i = l.size() - 1; i >= 0; i--) if (HiddenScanner.isThumbnailPath(l.get(i).path)) l.remove(i);
    }

    private static void addMissing(List<MediaInfo> into, List<MediaInfo> extra) {
        Set<String> paths = new HashSet<>();
        for (MediaInfo m : into) if (!m.path.isEmpty()) paths.add(m.path);
        for (MediaInfo m : extra) if (!paths.contains(m.path)) into.add(m);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingAccess != null && HiddenScanner.hasAllFilesAccess(this)) { accessGranted(); return; }
        pendingAccess = null;
        if (!loaded && hasPermission()) load();
        else if (tab == TAB_PLAYLISTS || tab == TAB_SETTINGS || (tab == TAB_SONGS && Prefs.squares(this))) refresh();
    }

    // ── Settings switches that need "All files access" ───────────────────────

    private void needAccessFor(String what) {
        Dialogs.builder(this)
                .setTitle(R.string.hidden_title)
                .setMessage(R.string.hidden_explain)
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
        pendingAccess = null;
        Ui.toast(this, R.string.hidden_scanning);
        load();
        refresh();
    }

    @Override
    public void onToggle(RowAdapter.Row r, boolean on) {
        switch (r.key) {
            case "hidden":
                if (!on) { Prefs.setShowHidden(this, false); load(); }
                else if (HiddenScanner.hasAllFilesAccess(this)) { Prefs.setShowHidden(this, true); Ui.toast(this, R.string.hidden_scanning); load(); }
                else needAccessFor("hidden");
                break;
            case "thumbs":
                if (!on) { Prefs.setShowThumbnails(this, false); load(); }
                else if (HiddenScanner.hasAllFilesAccess(this)) { Prefs.setShowThumbnails(this, true); Ui.toast(this, R.string.hidden_scanning); load(); }
                else needAccessFor("thumbs");
                break;
            case "history":
                History.setEnabled(this, on);
                refresh();
                break;
            case "autonext":
                Prefs.sp(this).edit().putBoolean("video_autonext", on).apply();
                break;
        }
    }

    // ── Tabs & views ─────────────────────────────────────────────────────────

    private void setTab(int t) {
        if (sel.active()) sel.clear();
        tab = t;
        openFolder = null;
        openPlaylist = null;
        openSquare = null;
        openHistory = false;
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setSelected(i == t);
            tabs[i].setTextColor(getColor(i == t ? R.color.accent : R.color.muted));
        }
        search.setHint(t == TAB_GALLERY ? R.string.search_gallery_hint : t == TAB_SETTINGS ? R.string.search_settings_hint : R.string.search_hint);
        refresh();
        showHeader();
    }

    private void setGridView(boolean grid) {
        if (tab == TAB_SONGS) { Prefs.setSquares(this, grid); openSquare = null; }
        else if (tab == TAB_VIDEOS) Prefs.setVideosGrid(this, grid);
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

    private void useList() {
        if (list.getAdapter() != rows) {
            list.setLayoutManager(new LinearLayoutManager(this));
            list.setAdapter(rows);
        }
    }

    private int gridCols(int portrait) {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        return land ? portrait + 1 : portrait;
    }

    private void setSeg(TextView t, boolean on) {
        t.setSelected(on);
        t.setTextColor(getColor(on ? R.color.accent : R.color.muted));
        t.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(on ? R.color.accent : R.color.muted)));
    }

    private void updateBars() {
        boolean inside = openFolder != null || openPlaylist != null || openSquare != null || openHistory;
        boolean sortable = !TAB_SORT_KEY[tab].isEmpty() && !(tab == TAB_SONGS && Prefs.squares(this) && openSquare == null);
        tabBar.setVisibility(tab == TAB_SETTINGS || tab == TAB_PLAYLISTS ? View.GONE : View.VISIBLE);
        viewSwitch.setVisibility((tab == TAB_SONGS || tab == TAB_VIDEOS) && !inside ? View.VISIBLE : View.GONE);
        sortBtn.setVisibility(sortable ? View.VISIBLE : View.GONE);
        if (sortable) sortBtn.setText(sortShortLabel(currentSort()));
        galleryBar.setVisibility(tab == TAB_GALLERY ? View.VISIBLE : View.GONE);
        boolean grid = tab == TAB_SONGS ? Prefs.squares(this) : Prefs.videosGrid(this);
        viewGrid.setText(tab == TAB_SONGS ? R.string.view_squares : R.string.view_grid);
        setSeg(viewList, !grid);
        setSeg(viewGrid, grid);
    }

    private void refresh() {
        updateBars();
        if (tab == TAB_GALLERY) { refreshGallery(); return; }
        if (tab == TAB_SONGS && Prefs.squares(this) && openSquare == null) { refreshSquares(); return; }
        if (tab == TAB_VIDEOS && Prefs.videosGrid(this)) { refreshVideoGrid(); return; }

        useList();
        List<RowAdapter.Row> out = new ArrayList<>();
        int emptyText = R.string.nothing_here;

        if (tab == TAB_SONGS) {
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
            }
            emptyText = R.string.no_songs;
        } else if (tab == TAB_VIDEOS) {
            for (MediaInfo m : sorted(video, currentSort())) if (matches(m)) out.add(RowAdapter.Row.media(m));
            tabHint.setText(getResources().getQuantityString(R.plurals.videos, video.size(), video.size()));
            emptyText = R.string.no_videos;
        } else if (tab == TAB_FOLDERS) {
            buildFolders(out);
            emptyText = R.string.no_folders;
        } else if (tab == TAB_PLAYLISTS) {
            emptyText = buildPlaylists(out);
        } else {
            buildSettings(out);
        }

        rows.setRows(out);
        if (permBox.getVisibility() == View.VISIBLE) return;
        boolean noMedia = true;
        for (RowAdapter.Row r : out)
            if (r.type == RowAdapter.MEDIA || r.type == RowAdapter.FOLDER || r.type == RowAdapter.PLAYLIST) { noMedia = false; break; }
        boolean show = loaded && noMedia && tab != TAB_SETTINGS && !(tab == TAB_PLAYLISTS && openPlaylist == null && !openHistory);
        empty.setText(filter.isEmpty() ? emptyText : R.string.no_matches);
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void buildFolders(List<RowAdapter.Row> out) {
        if (openFolder == null) {
            Map<String, int[]> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            Map<String, Boolean> hiddenFolder = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (MediaInfo m : audio) { count(folders, m.folder, 0); if (m.hidden) hiddenFolder.put(m.folder, true); }
            for (MediaInfo m : video) { count(folders, m.folder, 1); if (m.hidden) hiddenFolder.put(m.folder, true); }
            List<Map.Entry<String, int[]>> entries = new ArrayList<>(folders.entrySet());
            String s = currentSort();
            if ("name_desc".equals(s)) Collections.reverse(entries);
            else if ("most".equals(s)) Collections.sort(entries, (a, b) -> Integer.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
            for (Map.Entry<String, int[]> e : entries) {
                if (!matchesText(e.getKey())) continue;
                boolean hid = hiddenFolder.containsKey(e.getKey());
                out.add(new RowAdapter.Row(RowAdapter.FOLDER, e.getKey(),
                        (hid ? getString(R.string.hidden_prefix) : "") + folderCount(e.getValue()), null, e.getKey(),
                        hid ? R.drawable.ic_eye_off : R.drawable.ic_folder));
            }
            tabHint.setText(getResources().getQuantityString(R.plurals.folders, entries.size(), entries.size()));
        } else {
            out.add(new RowAdapter.Row(RowAdapter.BACK, openFolder, getString(R.string.all_folders), null, null, R.drawable.ic_back));
            List<MediaInfo> inside = new ArrayList<>();
            for (MediaInfo m : audio) if (m.folder.equals(openFolder) && matches(m)) inside.add(m);
            for (MediaInfo m : video) if (m.folder.equals(openFolder) && matches(m)) inside.add(m);
            for (MediaInfo m : sorted(inside, Prefs.sort(this, "songs", "name"))) out.add(RowAdapter.Row.media(m));
            tabHint.setText(openFolder);
        }
    }

    private int buildPlaylists(List<RowAdapter.Row> out) {
        if (openHistory) {
            out.add(new RowAdapter.Row(RowAdapter.BACK, getString(R.string.history), getString(R.string.all_playlists), null, null, R.drawable.ic_back));
            List<History.Entry> h = History.all(this);
            if (!h.isEmpty())
                out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.clear_history), null, null, "clear_history", R.drawable.ic_delete));
            for (History.Entry e : h)
                if (matches(e.media)) out.add(RowAdapter.Row.media(e.media, when(e.when) + " · " + kind(e.media) + subOf(e.media)));
            return History.enabled(this) ? R.string.history_empty : R.string.history_off;
        }
        if (openPlaylist == null) {
            out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.history),
                    History.enabled(this) ? getResources().getQuantityString(R.plurals.items, History.count(this), History.count(this))
                            : getString(R.string.history_off_short), null, "history", R.drawable.ic_history));
            out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.new_playlist), null, null, "new", R.drawable.ic_add));
            List<String> names = Playlists.names(this);
            List<String> ordered = new ArrayList<>();
            for (String f : new String[]{Prefs.FAV_SONGS, Prefs.FAV_VIDEOS, Prefs.FAV_PHOTOS}) if (names.contains(f)) ordered.add(f);
            for (String n : names) if (!ordered.contains(n)) ordered.add(n);
            for (String name : ordered) {
                if (!matchesText(name)) continue;
                int n = Playlists.count(this, name);
                out.add(new RowAdapter.Row(RowAdapter.PLAYLIST, name,
                        getResources().getQuantityString(R.plurals.items, n, n), null, name,
                        Prefs.isFavouriteList(name) ? R.drawable.ic_heart_filled : R.drawable.ic_playlist));
            }
            return R.string.nothing_here;
        }
        List<MediaInfo> items = Playlists.items(this, openPlaylist);
        out.add(new RowAdapter.Row(RowAdapter.BACK, openPlaylist, getString(R.string.all_playlists), null, null, R.drawable.ic_back));
        if (!items.isEmpty())
            out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.play_all), null, null, "playall", R.drawable.ic_play));
        for (MediaInfo m : items) if (matches(m)) out.add(RowAdapter.Row.media(m));
        return R.string.empty_playlist;
    }

    private void buildSettings(List<RowAdapter.Row> out) {
        List<RowAdapter.Row> all = new ArrayList<>();
        boolean access = HiddenScanner.hasAllFilesAccess(this);
        all.add(RowAdapter.Row.header(getString(R.string.set_library)));
        all.add(RowAdapter.Row.toggle(getString(R.string.show_hidden), getString(R.string.show_hidden_sub), "hidden",
                R.drawable.ic_eye_off, Prefs.showHidden(this) && access));
        all.add(RowAdapter.Row.toggle(getString(R.string.show_thumbs), getString(R.string.show_thumbs_sub), "thumbs",
                R.drawable.ic_image, Prefs.showThumbnails(this) && access));
        all.add(RowAdapter.Row.header(getString(R.string.set_playback)));
        all.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.eq_and_karaoke), getString(R.string.eq_and_karaoke_sub), null, "eq", R.drawable.ic_eq));
        all.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.video_rotation),
                getResources().getStringArray(R.array.orientations)[Prefs.videoOrientation(this)], null, "orient", R.drawable.ic_screen_rotation));
        all.add(RowAdapter.Row.toggle(getString(R.string.autonext), getString(R.string.autonext_sub), "autonext",
                R.drawable.ic_next, Prefs.sp(this).getBoolean("video_autonext", true)));
        all.add(RowAdapter.Row.header(getString(R.string.set_history)));
        all.add(RowAdapter.Row.toggle(getString(R.string.keep_history), getString(R.string.keep_history_sub), "history",
                R.drawable.ic_history, History.enabled(this)));
        all.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.clear_history),
                getResources().getQuantityString(R.plurals.items, History.count(this), History.count(this)), null, "clear_history", R.drawable.ic_delete));
        all.add(RowAdapter.Row.header(getString(R.string.set_sharing)));
        all.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.nkt_share), getString(R.string.nkt_share_sub), null, "share", R.drawable.ic_pc));
        all.add(RowAdapter.Row.header(getString(R.string.set_about)));
        all.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.app_name), getString(R.string.version_line), null, "about", R.drawable.ic_info));
        for (RowAdapter.Row r : all) {
            if (r.type == RowAdapter.HEADER || matchesText(r.title) || (r.sub != null && matchesText(r.sub))) out.add(r);
        }
    }

    private String when(long t) {
        if (DateUtils.isToday(t)) return getString(R.string.today_at, DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_TIME));
        if (DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS)) return getString(R.string.yesterday_at, DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_TIME));
        return DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
    }

    private String kind(MediaInfo m) { return getString(m.image ? R.string.kind_photo : m.video ? R.string.kind_video : R.string.kind_song); }

    private String subOf(MediaInfo m) { return m.artist.isEmpty() ? "" : " · " + m.artist; }

    // ── Sorting ──────────────────────────────────────────────────────────────

    private String currentSort() {
        String key = TAB_SORT_KEY[tab];
        return key.isEmpty() ? "" : Prefs.sort(this, key, TAB_SORT_DEF[tab]);
    }

    private String[] sortKeysFor(int t) {
        switch (t) {
            case TAB_SONGS: return new String[]{"name", "name_desc", "artist", "album", "new", "old", "long", "short", "large", "plays"};
            case TAB_VIDEOS: return new String[]{"name", "name_desc", "new", "old", "long", "short", "large", "small"};
            case TAB_GALLERY: return new String[]{"new", "old", "name", "large"};
            case TAB_FOLDERS: return new String[]{"name", "name_desc", "most"};
        }
        return new String[0];
    }

    private String sortLabel(String k) {
        switch (k) {
            case "name": return getString(R.string.sort_name);
            case "name_desc": return getString(R.string.sort_name_desc);
            case "artist": return getString(R.string.sort_artist);
            case "album": return getString(R.string.sort_album);
            case "new": return getString(R.string.sort_new);
            case "old": return getString(R.string.sort_old);
            case "long": return getString(R.string.sort_long);
            case "short": return getString(R.string.sort_short);
            case "large": return getString(R.string.sort_large);
            case "small": return getString(R.string.sort_small);
            case "plays": return getString(R.string.sort_plays);
            case "most": return getString(R.string.sort_most);
        }
        return k;
    }

    private String sortShortLabel(String k) {
        switch (k) {
            case "name": return "A–Z";
            case "name_desc": return "Z–A";
            case "artist": return getString(R.string.sort_s_artist);
            case "album": return getString(R.string.sort_s_album);
            case "new": return getString(R.string.sort_s_new);
            case "old": return getString(R.string.sort_s_old);
            case "long": case "short": return getString(R.string.sort_s_length);
            case "large": case "small": return getString(R.string.sort_s_size);
            case "plays": return getString(R.string.sort_s_plays);
            case "most": return getString(R.string.sort_s_most);
        }
        return "";
    }

    private void chooseSort() {
        final String[] keys = sortKeysFor(tab);
        if (keys.length == 0) return;
        String[] labels = new String[keys.length];
        int checked = 0;
        String cur = currentSort();
        for (int i = 0; i < keys.length; i++) { labels[i] = sortLabel(keys[i]); if (keys[i].equals(cur)) checked = i; }
        Dialogs.builder(this)
                .setTitle(R.string.sort_by)
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    Prefs.setSort(this, TAB_SORT_KEY[tab], keys[w]);
                    d.dismiss();
                    refresh();
                    showHeader();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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

    // ── Videos grid ──────────────────────────────────────────────────────────

    private void refreshVideoGrid() {
        List<MediaInfo> shown = new ArrayList<>();
        for (MediaInfo m : sorted(video, currentSort())) if (matches(m)) shown.add(m);
        videoGrid.set(shown);
        int cols = gridCols(2);
        if (list.getAdapter() != videoGrid || ((GridLayoutManager) list.getLayoutManager()).getSpanCount() != cols) {
            list.setLayoutManager(new GridLayoutManager(this, cols));
            list.setAdapter(videoGrid);
        }
        tabHint.setText(getResources().getQuantityString(R.plurals.videos, video.size(), video.size()));
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

        // album strip (biggest first)
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
        if (list.getAdapter() != gallery || ((GridLayoutManager) list.getLayoutManager()).getSpanCount() != cols) {
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

    /** Google Photos style: pinch the grid to show more or fewer per row. */
    private void setupPinch() {
        ScaleGestureDetector pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { pinchAcc = 1f; return tab == TAB_GALLERY; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                pinchAcc *= d.getScaleFactor();
                if (pinchAcc > 1.25f) { stepGrid(-1); pinchAcc = 1f; }       // fingers apart: bigger
                else if (pinchAcc < 0.8f) { stepGrid(+1); pinchAcc = 1f; }   // fingers together: more per row
                return true;
            }
        });
        list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                if (tab != TAB_GALLERY) return false;
                pinch.onTouchEvent(e);
                return e.getPointerCount() > 1 || pinch.isInProgress();
            }
            @Override public void onTouchEvent(RecyclerView rv, MotionEvent e) { pinch.onTouchEvent(e); }
        });
    }

    private void stepGrid(int dir) {
        int cur = Prefs.galleryCols(this), idx = 1;
        for (int i = 0; i < GRID_STEPS.length; i++) if (GRID_STEPS[i] == cur) idx = i;
        int next = Math.max(0, Math.min(GRID_STEPS.length - 1, idx + dir));
        if (GRID_STEPS[next] == cur) return;
        GridLayoutManager glm = (GridLayoutManager) list.getLayoutManager();
        int first = glm == null ? 0 : glm.findFirstVisibleItemPosition();
        Prefs.setGalleryCols(this, GRID_STEPS[next]);
        refreshGallery();
        list.scrollToPosition(Math.max(0, first));
        zoomPop.setText(getString(GRID_STEPS[next] >= 5 ? R.string.per_row_month : R.string.per_row, GRID_STEPS[next]));
        zoomPop.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideZoomPop);
        ui.postDelayed(hideZoomPop, 900);
    }

    private final Runnable hideZoomPop = () -> zoomPop.setVisibility(View.GONE);

    private void openGridItem(MediaInfo m, List<MediaInfo> all) {
        if (m.video) {
            List<MediaInfo> vids = new ArrayList<>();
            for (MediaInfo x : all) if (x.video) vids.add(x);
            PlayerActivity.open(this, vids, Math.max(0, vids.indexOf(m)));
        } else {
            List<MediaInfo> photos = new ArrayList<>();
            for (MediaInfo x : all) if (x.image) photos.add(x);
            ViewerActivity.open(this, photos, Math.max(0, photos.indexOf(m)));
        }
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
            if (byDate || openHistory) return m.dateMs > 0 ? new SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(new Date(m.dateMs)) : "";
            if ("long".equals(sort) || "short".equals(sort)) return Ui.time(m.durationMs);
            if ("artist".equals(sort)) text = m.artist;
            else if ("album".equals(sort)) text = m.album;
            else text = m.title;
        }
        if (text == null || text.isEmpty()) return "";
        return text.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    // ── Multi-select ─────────────────────────────────────────────────────────

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

    /** Permanently deletes files. Android shows its own confirmation on Android 11+. */
    private void deleteItems(List<MediaInfo> items) {
        if (items.isEmpty()) return;
        pendingDelete = new ArrayList<>(items);
        List<Uri> content = new ArrayList<>();
        for (MediaInfo m : items) if ("content".equals(m.uri.getScheme())) content.add(m.uri);
        if (Build.VERSION.SDK_INT >= 30 && !content.isEmpty()) {
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), content);
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception e) { Ui.toast(this, R.string.delete_failed); }
            return;
        }
        if (Build.VERSION.SDK_INT <= 29 && !content.isEmpty()
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_DELETE_PERM);
            return;
        }
        confirmAndDelete(pendingDelete);
    }

    private void confirmAndDelete(List<MediaInfo> items) {
        Dialogs.builder(this)
                .setTitle(getResources().getQuantityString(R.plurals.delete_title, items.size(), items.size()))
                .setMessage(R.string.delete_warning)
                .setPositiveButton(R.string.delete, (d, w) -> doDelete(items, true))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DELETE && pendingDelete != null) {
            if (resultCode == RESULT_OK) doDelete(pendingDelete, false);   // Android already deleted the library items
            else pendingDelete = null;
        }
    }

    private void doDelete(List<MediaInfo> items, boolean deleteContent) {
        new Thread(() -> {
            int ok = 0;
            Set<String> gone = new HashSet<>();
            for (MediaInfo m : items) {
                boolean done = false;
                try {
                    if ("file".equals(m.uri.getScheme())) done = new File(m.uri.getPath()).delete();
                    else if (!deleteContent) done = true;
                    else done = getContentResolver().delete(m.uri, null, null) > 0;
                } catch (Exception ignored) { }
                if (done) { ok++; gone.add(m.key()); Thumbs.forget(m); }
            }
            final int n = ok;
            ui.post(() -> {
                removeGone(audio, gone); removeGone(video, gone); removeGone(images, gone);
                pendingDelete = null;
                sel.clear();
                refresh();
                Ui.toast(this, n > 0 ? getResources().getQuantityString(R.plurals.deleted, n, n) : getString(R.string.delete_failed));
            });
        }).start();
    }

    private static void removeGone(List<MediaInfo> l, Set<String> gone) {
        for (int i = l.size() - 1; i >= 0; i--) if (gone.contains(l.get(i).key())) l.remove(i);
    }

    // ── Clicks ───────────────────────────────────────────────────────────────

    private static void count(Map<String, int[]> map, String folder, int kind) {
        int[] c = map.get(folder);
        if (c == null) { c = new int[2]; map.put(folder, c); }
        c[kind]++;
    }

    private String folderCount(int[] c) {
        String songs = c[0] > 0 ? getResources().getQuantityString(R.plurals.songs, c[0], c[0]) : "";
        String vids = c[1] > 0 ? getResources().getQuantityString(R.plurals.videos, c[1], c[1]) : "";
        if (songs.isEmpty()) return vids;
        return vids.isEmpty() ? songs : songs + " · " + vids;
    }

    /** 0 songs, 1 videos, 2 photos — the visible rows of that kind. */
    private List<MediaInfo> visible(int kind) {
        List<MediaInfo> out = new ArrayList<>();
        for (RowAdapter.Row r : rows.rows()) {
            if (r.type != RowAdapter.MEDIA) continue;
            int k = r.media.image ? 2 : r.media.video ? 1 : 0;
            if (k == kind) out.add(r.media);
        }
        return out;
    }

    private void openMedia(MediaInfo m) {
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
            case RowAdapter.FOLDER: openFolder = r.key; refresh(); showHeader(); break;
            case RowAdapter.PLAYLIST: openPlaylist = r.key; refresh(); showHeader(); break;
            case RowAdapter.BACK: goBack(); break;
            case RowAdapter.ACTION: onAction(r.key); break;
        }
    }

    private void onAction(String key) {
        switch (key) {
            case "new": Dialogs.newPlaylist(this, name -> refresh()); break;
            case "playall": playPlaylist(); break;
            case "history": openHistory = true; refresh(); showHeader(); break;
            case "clear_history":
                Dialogs.builder(this).setMessage(R.string.clear_history_confirm)
                        .setPositiveButton(R.string.clear, (d, w) -> { History.clear(this); refresh(); })
                        .setNegativeButton(R.string.cancel, null).show();
                break;
            case "eq": startActivity(new Intent(this, EqualizerActivity.class)); break;
            case "share": startActivity(new Intent(this, NktShareActivity.class)); break;
            case "orient":
                Dialogs.builder(this).setTitle(R.string.video_rotation)
                        .setSingleChoiceItems(getResources().getStringArray(R.array.orientations), Prefs.videoOrientation(this), (d, w) -> {
                            Prefs.setVideoOrientation(this, w);
                            d.dismiss();
                            refresh();
                        }).setNegativeButton(R.string.cancel, null).show();
                break;
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

    private void goBack() {
        openFolder = null; openPlaylist = null; openSquare = null; openHistory = false;
        refresh();
        showHeader();
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
        if (r.type != RowAdapter.MEDIA) return;
        if (sel.active()) sel.toggle(r.media); else sel.start(r.media);
    }

    @Override
    public void onMore(RowAdapter.Row r, int position) {
        if (r.type == RowAdapter.PLAYLIST) { playlistMenu(r); return; }
        if (r.type == RowAdapter.MEDIA) itemMenu(r.media);
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
        boolean fav = Prefs.isFavourite(this, m);
        List<String> opts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        opts.add(getString(m.image ? R.string.open : R.string.play)); ids.add("play");
        opts.add(getString(fav ? R.string.unfavourite : R.string.favourite)); ids.add("fav");
        opts.add(getString(R.string.add_to_playlist)); ids.add("add");
        if (!m.video && !m.image) {
            opts.add(getString(R.string.edit_info)); ids.add("edit");
            opts.add(getString(R.string.set_as_ringtone)); ids.add("ringtone");
        }
        opts.add(getString(R.string.share)); ids.add("share");
        opts.add(getString(R.string.select)); ids.add("select");
        opts.add(getString(R.string.details)); ids.add("details");
        if (inPlaylist) { opts.add(getString(R.string.remove_from_playlist)); ids.add("remove"); }
        opts.add(getString(R.string.delete_file)); ids.add("delete");
        Dialogs.builder(this)
                .setTitle(m.title)
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    switch (ids.get(which)) {
                        case "play": openMedia(m); break;
                        case "fav":
                            Ui.toast(this, Prefs.toggleFavourite(this, m)
                                    ? getString(R.string.fav_added_to, Prefs.favouritesFor(m)) : getString(R.string.fav_removed_from, Prefs.favouritesFor(m)));
                            if (tab == TAB_PLAYLISTS) refresh();
                            break;
                        case "add": Dialogs.addToPlaylist(this, m); break;
                        case "edit": MetaEditActivity.open(this, m); break;
                        case "ringtone": Ringtones.set(this, m); break;
                        case "share": Share.send(this, m); break;
                        case "select": sel.start(m); break;
                        case "details": Dialogs.details(this, m); break;
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
                    if (YouTube.isYouTube(s)) { Ui.toast(this, R.string.youtube_no_download); return; }
                    Downloads.start(this, Uri.parse(s), null);
                })
                .show();
    }

    private void playLink(String text, boolean asVideo) {
        String s = text.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            Ui.toast(this, R.string.link_invalid);
            return;
        }
        if (YouTube.isYouTube(s)) {                 // YouTube only plays in its own player
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
        Runnable go = () -> {
            controller.setShuffleModeEnabled(shuffle);
            controller.setMediaItems(Media.items(copy), shuffle ? (int) (Math.random() * copy.size()) : index, 0);
            controller.prepare();
            controller.play();
        };
        if (controller != null) go.run(); else pending = go;
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) { updateMini(); }
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
        if (controller == null || controller.getMediaItemCount() == 0 || controller.getCurrentMediaItem() == null) {
            mini.setVisibility(View.GONE);
            rows.setPlaying(null);
            return;
        }
        mini.setVisibility(View.VISIBLE);
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

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (sel.active()) { sel.clear(); return; }
        if (openFolder != null || openPlaylist != null || openSquare != null || openHistory) { goBack(); return; }
        if (search.getText().length() > 0) { search.setText(""); return; }
        super.onBackPressed();
    }
}
