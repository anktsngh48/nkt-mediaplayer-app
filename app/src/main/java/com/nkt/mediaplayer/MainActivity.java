package com.nkt.mediaplayer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Library: Songs (list or music squares) · Videos · Gallery · Folders · Playlists. */
public class MainActivity extends Activity implements RowAdapter.Listener {

    private static final int TAB_SONGS = 0, TAB_VIDEOS = 1, TAB_GALLERY = 2, TAB_FOLDERS = 3, TAB_PLAYLISTS = 4;
    private static final int REQ_PERMS = 11, REQ_LEGACY_WRITE = 12;
    private static final int[] GRID_STEPS = {2, 3, 4, 5, 7};
    private static final String ALL_ALBUMS = "All";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<MediaInfo> audio = new ArrayList<>();
    private final List<MediaInfo> video = new ArrayList<>();
    private final List<MediaInfo> images = new ArrayList<>();

    private RecyclerView list;
    private RowAdapter rows;
    private GalleryAdapter gallery;
    private SquareAdapter squares;
    private AlbumAdapter albums;
    private TextView[] tabs;
    private TextView empty, zoomPop, gridSize, songsHint, viewList, viewSquares, segAll, segPhotos, segVideos;
    private View permBox, mini, songsBar, galleryBar;
    private ImageView miniArt;
    private TextView miniTitle, miniSub;
    private ImageButton miniPlay;
    private EditText search;

    private int tab = TAB_SONGS;
    private String openFolder, openPlaylist;
    private Groups.Group openSquare;
    private String filter = "";
    private String album = ALL_ALBUMS;
    private int galleryKind = 0;              // 0 all, 1 photos, 2 videos
    private boolean loaded = false, loading = false, wantHidden = false;
    private float pinchAcc = 1f;

    private ListenableFuture<MediaController> future;
    private MediaController controller;
    private Runnable pending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.padForSystemBars(findViewById(R.id.root));

        TextView title = findViewById(R.id.header_title);
        title.setTypeface(Ui.orbitronBlack(this));
        Ui.gradient(title);

        tabs = new TextView[]{findViewById(R.id.tab_songs), findViewById(R.id.tab_videos), findViewById(R.id.tab_gallery),
                findViewById(R.id.tab_folders), findViewById(R.id.tab_playlists)};
        for (int i = 0; i < tabs.length; i++) {
            final int t = i;
            tabs[i].setTypeface(Ui.orbitron(this));
            tabs[i].setOnClickListener(v -> setTab(t));
        }

        list = findViewById(R.id.list);
        rows = new RowAdapter(this);
        gallery = new GalleryAdapter((m, all) -> openGalleryItem(m, all));
        squares = new SquareAdapter(g -> { openSquare = g; refresh(); });
        albums = new AlbumAdapter(a -> { album = a.name; refresh(); });
        RecyclerView albumStrip = findViewById(R.id.albums);
        albumStrip.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        albumStrip.setAdapter(albums);
        setupPinch();

        empty = findViewById(R.id.empty);
        zoomPop = findViewById(R.id.zoom_pop);
        zoomPop.setTypeface(Ui.orbitron(this));
        gridSize = findViewById(R.id.grid_size);
        songsBar = findViewById(R.id.songs_bar);
        songsHint = findViewById(R.id.songs_hint);
        galleryBar = findViewById(R.id.gallery_bar);
        viewList = findViewById(R.id.view_list);
        viewSquares = findViewById(R.id.view_squares);
        segAll = findViewById(R.id.seg_all);
        segPhotos = findViewById(R.id.seg_photos);
        segVideos = findViewById(R.id.seg_videos);
        for (TextView t : new TextView[]{viewList, viewSquares, segAll, segPhotos, segVideos}) t.setTypeface(Ui.orbitron(this));
        viewList.setOnClickListener(v -> { Prefs.setSquares(this, false); openSquare = null; refresh(); });
        viewSquares.setOnClickListener(v -> { Prefs.setSquares(this, true); refresh(); });
        segAll.setOnClickListener(v -> { galleryKind = 0; refresh(); });
        segPhotos.setOnClickListener(v -> { galleryKind = 1; refresh(); });
        segVideos.setOnClickListener(v -> { galleryKind = 2; refresh(); });

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
        mini.setOnClickListener(v -> startActivity(new Intent(this, NowPlayingActivity.class)));

        setTab(TAB_SONGS);
        if (hasPermission()) load(); else showPermissionBox();
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
        if (requestCode == REQ_LEGACY_WRITE && HiddenScanner.hasAllFilesAccess(this)) enableHidden();
    }

    private void load() {
        if (loading) return;
        loading = true;
        permBox.setVisibility(View.GONE);
        if (!loaded) { empty.setText(R.string.loading); empty.setVisibility(View.VISIBLE); }
        final boolean withHidden = Prefs.showHidden(this) && HiddenScanner.hasAllFilesAccess(this);
        new Thread(() -> {
            List<MediaInfo> a = MediaLibrary.audio(this);
            List<MediaInfo> v = MediaLibrary.video(this);
            List<MediaInfo> im = MediaLibrary.images(this);
            if (withHidden) {
                HiddenScanner.Result h = HiddenScanner.scan();
                a.addAll(h.audio);
                v.addAll(h.video);
                im.addAll(h.images);
                Collections.sort(a, (x, y) -> x.title.compareToIgnoreCase(y.title));
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

    @Override
    protected void onResume() {
        super.onResume();
        if (wantHidden && HiddenScanner.hasAllFilesAccess(this)) { wantHidden = false; enableHidden(); return; }
        wantHidden = false;
        if (!loaded && hasPermission()) load();
        else if (tab == TAB_PLAYLISTS || (tab == TAB_SONGS && Prefs.squares(this))) refresh();
    }

    // ── Hidden files ─────────────────────────────────────────────────────────

    @Override
    public void onToggle(RowAdapter.Row r, boolean on) {
        if (!on) { Prefs.setShowHidden(this, false); load(); return; }
        if (HiddenScanner.hasAllFilesAccess(this)) { enableHidden(); return; }
        rows.setToggle(false);
        rows.notifyDataSetChanged();
        Dialogs.builder(this)
                .setTitle(R.string.hidden_title)
                .setMessage(R.string.hidden_explain)
                .setPositiveButton(R.string.allow, (d, w) -> {
                    wantHidden = true;
                    if (Build.VERSION.SDK_INT >= 30) {
                        try { startActivity(HiddenScanner.allFilesAccessIntent(this)); } catch (Exception ignored) { }
                    } else {
                        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY_WRITE);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void enableHidden() {
        Prefs.setShowHidden(this, true);
        Toast.makeText(this, R.string.hidden_scanning, Toast.LENGTH_SHORT).show();
        load();
    }

    // ── Tabs & views ─────────────────────────────────────────────────────────

    private void setTab(int t) {
        tab = t;
        openFolder = null;
        openPlaylist = null;
        openSquare = null;
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setSelected(i == t);
            tabs[i].setTextColor(getColor(i == t ? R.color.accent : R.color.muted));
        }
        search.setHint(t == TAB_GALLERY ? R.string.search_gallery_hint : R.string.search_hint);
        refresh();
    }

    private boolean matches(MediaInfo m) {
        if (filter.isEmpty()) return true;
        return m.title.toLowerCase(Locale.ROOT).contains(filter)
                || m.artist.toLowerCase(Locale.ROOT).contains(filter)
                || m.album.toLowerCase(Locale.ROOT).contains(filter)
                || m.folder.toLowerCase(Locale.ROOT).contains(filter)
                || m.fileName.toLowerCase(Locale.ROOT).contains(filter);
    }

    private void useList() {
        if (list.getAdapter() != rows) {
            list.setLayoutManager(new LinearLayoutManager(this));
            list.setAdapter(rows);
        }
    }

    private void refresh() {
        boolean songsSquares = tab == TAB_SONGS && Prefs.squares(this) && openSquare == null;
        songsBar.setVisibility(tab == TAB_SONGS && openSquare == null ? View.VISIBLE : View.GONE);
        galleryBar.setVisibility(tab == TAB_GALLERY ? View.VISIBLE : View.GONE);
        setSeg(viewList, !Prefs.squares(this));
        setSeg(viewSquares, Prefs.squares(this));

        if (tab == TAB_GALLERY) { refreshGallery(); return; }
        if (songsSquares) { refreshSquares(); return; }

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
                for (MediaInfo m : audio) if (matches(m)) out.add(RowAdapter.Row.media(m));
                songsHint.setText(getResources().getQuantityString(R.plurals.songs, audio.size(), audio.size()));
            }
            emptyText = R.string.no_songs;
        } else if (tab == TAB_VIDEOS) {
            for (MediaInfo m : video) if (matches(m)) out.add(RowAdapter.Row.media(m));
            emptyText = R.string.no_videos;
        } else if (tab == TAB_FOLDERS) {
            if (openFolder == null) {
                rows.setToggle(Prefs.showHidden(this) && HiddenScanner.hasAllFilesAccess(this));
                out.add(new RowAdapter.Row(RowAdapter.TOGGLE, getString(R.string.show_hidden), getString(R.string.show_hidden_sub), null, "hidden", R.drawable.ic_eye_off));
                Map<String, int[]> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                Map<String, Boolean> hiddenFolder = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (MediaInfo m : audio) { count(folders, m.folder, 0); if (m.hidden) hiddenFolder.put(m.folder, true); }
                for (MediaInfo m : video) { count(folders, m.folder, 1); if (m.hidden) hiddenFolder.put(m.folder, true); }
                for (Map.Entry<String, int[]> e : folders.entrySet()) {
                    if (!filter.isEmpty() && !e.getKey().toLowerCase(Locale.ROOT).contains(filter)) continue;
                    boolean hid = hiddenFolder.containsKey(e.getKey());
                    out.add(new RowAdapter.Row(RowAdapter.FOLDER, e.getKey(),
                            (hid ? getString(R.string.hidden_prefix) : "") + folderCount(e.getValue()), null, e.getKey(),
                            hid ? R.drawable.ic_eye_off : R.drawable.ic_folder));
                }
            } else {
                out.add(new RowAdapter.Row(RowAdapter.BACK, openFolder, getString(R.string.all_folders), null, null, R.drawable.ic_back));
                for (MediaInfo m : audio) if (m.folder.equals(openFolder) && matches(m)) out.add(RowAdapter.Row.media(m));
                for (MediaInfo m : video) if (m.folder.equals(openFolder) && matches(m)) out.add(RowAdapter.Row.media(m));
            }
            emptyText = R.string.no_folders;
        } else {
            if (openPlaylist == null) {
                out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.new_playlist), null, null, "new", R.drawable.ic_add));
                for (String name : Playlists.names(this)) {
                    if (!filter.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(filter)) continue;
                    int n = Playlists.count(this, name);
                    out.add(new RowAdapter.Row(RowAdapter.PLAYLIST, name,
                            getResources().getQuantityString(R.plurals.items, n, n), null, name,
                            Prefs.FAVOURITES.equals(name) ? R.drawable.ic_heart_filled : R.drawable.ic_playlist));
                }
            } else {
                List<MediaInfo> items = Playlists.items(this, openPlaylist);
                out.add(new RowAdapter.Row(RowAdapter.BACK, openPlaylist, getString(R.string.all_playlists), null, null, R.drawable.ic_back));
                if (!items.isEmpty())
                    out.add(new RowAdapter.Row(RowAdapter.ACTION, getString(R.string.play_all), null, null, "playall", R.drawable.ic_play));
                for (MediaInfo m : items) if (matches(m)) out.add(RowAdapter.Row.media(m));
                emptyText = R.string.empty_playlist;
            }
        }

        rows.setRows(out);
        if (permBox.getVisibility() == View.VISIBLE) return;
        boolean noMedia = true;
        for (RowAdapter.Row r : out)
            if (r.type == RowAdapter.MEDIA || r.type == RowAdapter.FOLDER || r.type == RowAdapter.PLAYLIST) { noMedia = false; break; }
        boolean show = loaded && noMedia && !(tab == TAB_PLAYLISTS && openPlaylist == null);
        empty.setText(filter.isEmpty() ? emptyText : R.string.no_matches);
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setSeg(TextView t, boolean on) {
        t.setSelected(on);
        t.setTextColor(getColor(on ? R.color.accent : R.color.muted));
        t.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(getColor(on ? R.color.accent : R.color.muted)));
    }

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
        songsHint.setText(R.string.squares_hint);
        empty.setText(R.string.no_songs);
        empty.setVisibility(loaded && squares.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── Gallery ──────────────────────────────────────────────────────────────

    private void refreshGallery() {
        setSeg(segAll, galleryKind == 0);
        setSeg(segPhotos, galleryKind == 1);
        setSeg(segVideos, galleryKind == 2);

        List<MediaInfo> all = new ArrayList<>();
        if (galleryKind != 2) all.addAll(images);
        if (galleryKind != 1) all.addAll(video);
        Collections.sort(all, (a, b) -> Long.compare(b.dateMs, a.dateMs));

        // album strip
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
        gallery.set(shown, cols);
        if (list.getAdapter() != gallery || ((GridLayoutManager) list.getLayoutManager()).getSpanCount() != cols) {
            GridLayoutManager glm = new GridLayoutManager(this, cols);
            glm.setSpanSizeLookup(gallery.spans());
            list.setLayoutManager(glm);
            list.setAdapter(gallery);
        }
        gridSize.setText(getString(R.string.per_row, cols));
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

    private void openGalleryItem(MediaInfo m, List<MediaInfo> all) {
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

    private List<MediaInfo> visible(boolean wantVideo) {
        List<MediaInfo> out = new ArrayList<>();
        for (RowAdapter.Row r : rows.rows())
            if (r.type == RowAdapter.MEDIA && r.media.video == wantVideo) out.add(r.media);
        return out;
    }

    @Override
    public void onClick(RowAdapter.Row r, int position) {
        switch (r.type) {
            case RowAdapter.MEDIA: {
                List<MediaInfo> same = visible(r.media.video);
                int idx = Math.max(0, same.indexOf(r.media));
                if (r.media.video) PlayerActivity.open(this, same, idx);
                else playAudio(same, idx, false);
                break;
            }
            case RowAdapter.FOLDER: openFolder = r.key; refresh(); break;
            case RowAdapter.PLAYLIST: openPlaylist = r.key; refresh(); break;
            case RowAdapter.BACK: openFolder = null; openPlaylist = null; openSquare = null; refresh(); break;
            case RowAdapter.ACTION:
                if ("new".equals(r.key)) Dialogs.newPlaylist(this, name -> refresh());
                else if ("playall".equals(r.key)) playPlaylist();
                else if ("sq_play".equals(r.key) && openSquare != null) playAudio(openSquare.songs, 0, false);
                else if ("sq_shuffle".equals(r.key) && openSquare != null) playAudio(openSquare.songs, 0, true);
                else if ("sq_save".equals(r.key) && openSquare != null) {
                    final List<MediaInfo> songs = openSquare.songs;
                    Dialogs.newPlaylist(this, name -> {
                        for (MediaInfo m : songs) Playlists.add(this, name, m);
                        Toast.makeText(this, getString(R.string.added_to, name), Toast.LENGTH_SHORT).show();
                    });
                }
                break;
        }
    }

    private void playPlaylist() {
        List<MediaInfo> songs = visible(false), vids = visible(true);
        if (!songs.isEmpty()) playAudio(songs, 0, false);
        else if (!vids.isEmpty()) PlayerActivity.open(this, vids, 0);
    }

    @Override
    public void onLongClick(RowAdapter.Row r, int position) {
        if (r.type == RowAdapter.PLAYLIST) {
            Dialogs.builder(this)
                    .setTitle(r.title)
                    .setItems(new String[]{getString(R.string.delete_playlist)}, (d, w) ->
                            Dialogs.builder(this)
                                    .setMessage(getString(R.string.delete_confirm, r.title))
                                    .setPositiveButton(R.string.delete, (d2, w2) -> { Playlists.delete(this, r.title); refresh(); })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show())
                    .show();
            return;
        }
        if (r.type != RowAdapter.MEDIA) return;
        final boolean inPlaylist = openPlaylist != null;
        List<String> opts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        opts.add(getString(R.string.play)); ids.add("play");
        opts.add(getString(R.string.add_to_playlist)); ids.add("add");
        if (!r.media.video) { opts.add(getString(R.string.edit_info)); ids.add("edit"); }
        opts.add(getString(R.string.share)); ids.add("share");
        opts.add(getString(R.string.details)); ids.add("details");
        if (inPlaylist) { opts.add(getString(R.string.remove_from_playlist)); ids.add("remove"); }
        Dialogs.builder(this)
                .setTitle(r.title)
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    String id = ids.get(which);
                    switch (id) {
                        case "play": onClick(r, position); break;
                        case "add": Dialogs.addToPlaylist(this, r.media); break;
                        case "edit": MetaEditActivity.open(this, r.media); break;
                        case "share": Share.send(this, r.media); break;
                        case "details": Dialogs.details(this, r.media); break;
                        case "remove": {
                            List<MediaInfo> items = Playlists.items(this, openPlaylist);
                            int idx = -1;
                            for (int i = 0; i < items.size(); i++) if (items.get(i).uri.equals(r.media.uri)) { idx = i; break; }
                            Playlists.remove(this, openPlaylist, idx);
                            refresh();
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
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        Dialogs.builder(this)
                .setTitle(R.string.open_link)
                .setMessage(R.string.link_help)
                .setView(box)
                .setPositiveButton(R.string.play_as_audio, (d, w) -> playLink(input.getText().toString(), false))
                .setNeutralButton(R.string.play_as_video, (d, w) -> playLink(input.getText().toString(), true))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void playLink(String text, boolean asVideo) {
        String s = text.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            Toast.makeText(this, R.string.link_invalid, Toast.LENGTH_SHORT).show();
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
        miniSub.setText(md.artist == null ? "" : md.artist);
        miniPlay.setImageResource(controller.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        Bitmap art = null;
        if (md.artworkData != null) {
            try { art = BitmapFactory.decodeByteArray(md.artworkData, 0, md.artworkData.length); } catch (Exception ignored) { }
        }
        if (art != null) Thumbs.showBitmap(miniArt, art); else Thumbs.showPlaceholder(miniArt, R.drawable.ic_music);
        rows.setPlaying(controller.getCurrentMediaItem().mediaId);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (openFolder != null || openPlaylist != null || openSquare != null) {
            openFolder = null; openPlaylist = null; openSquare = null; refresh();
            return;
        }
        if (search.getText().length() > 0) { search.setText(""); return; }
        super.onBackPressed();
    }
}
