package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.content.ClipData;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/**
 * Music screen: art, seek bar, play/pause, previous/next, shuffle, repeat,
 * plus MX-style options: equalizer, sleep timer, speed, lyrics, queue, favourite, ⋮ menu.
 */
public class NowPlayingActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ListenableFuture<MediaController> future;
    private MediaController controller;

    private ImageView art;
    private TextView title, artist, tCur, tDur, sleepChip, upNext, optFav, optSpeed;
    private SeekBar seek;
    private ImageButton play, shuffle, repeat;
    private boolean dragging = false;
    private String artFor = null;
    private List<Uri> openedUris;      // files opened or shared from another app

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            updateSleep();
            updateLyricLine();
            ui.postDelayed(this, lyricsOpen() ? 200 : 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);
        Ui.padForSystemBars(findViewById(R.id.root));

        art = findViewById(R.id.np_art);
        art.setClipToOutline(true);
        title = findViewById(R.id.np_title);
        artist = findViewById(R.id.np_artist);
        tCur = findViewById(R.id.np_cur);
        tDur = findViewById(R.id.np_dur);
        seek = findViewById(R.id.np_seek);
        play = findViewById(R.id.np_play);
        shuffle = findViewById(R.id.np_shuffle);
        repeat = findViewById(R.id.np_repeat);
        ((TextView) findViewById(R.id.np_label)).setTypeface(Ui.orbitron(this));
        sleepChip = findViewById(R.id.np_sleep);
        upNext = findViewById(R.id.np_upnext_title);
        optFav = findViewById(R.id.opt_fav);
        optSpeed = findViewById(R.id.opt_speed);
        ((TextView) findViewById(R.id.np_upnext_label)).setTypeface(Ui.orbitron(this));

        findViewById(R.id.np_back).setOnClickListener(v -> finish());
        play.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.isPlaying()) controller.pause();
            else {
                if (controller.getPlaybackState() == Player.STATE_ENDED) controller.seekTo(0, 0);
                Media.play(controller);
            }
        });
        findViewById(R.id.np_prev).setOnClickListener(v -> { if (controller != null) controller.seekToPrevious(); });
        findViewById(R.id.np_next).setOnClickListener(v -> { if (controller != null) controller.seekToNext(); });
        shuffle.setOnClickListener(v -> {
            if (controller != null) controller.setShuffleModeEnabled(!controller.getShuffleModeEnabled());
        });
        repeat.setOnClickListener(v -> {
            if (controller == null) return;
            int m = controller.getRepeatMode();
            controller.setRepeatMode(m == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL
                    : m == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        });
        findViewById(R.id.np_more).setOnClickListener(v -> moreMenu());
        findViewById(R.id.opt_eq).setOnClickListener(v -> startActivity(new Intent(this, EqualizerActivity.class)));
        findViewById(R.id.opt_timer).setOnClickListener(v -> openSpeedSheet());
        optSpeed.setOnClickListener(v -> openSpeedSheet());
        findViewById(R.id.opt_queue).setOnClickListener(v -> openQueue());
        findViewById(R.id.np_upnext).setOnClickListener(v -> openQueue());
        optFav.setOnClickListener(v -> toggleFavourite());
        findViewById(R.id.opt_lyrics).setOnClickListener(v -> toggleLyrics());
        findViewById(R.id.opt_karaoke).setOnClickListener(v -> toggleKaraoke());
        deleter = new FileOps.Deleter(this);
        findViewById(R.id.opt_karaoke).setOnLongClickListener(v -> { startActivity(new Intent(this, EqualizerActivity.class)); return true; });
        showKaraoke();
        findViewById(R.id.np_watch).setOnClickListener(v -> watchVideo());
        setupLyrics();
        sleepChip.setOnClickListener(v -> openSpeedSheet());

        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && controller != null && controller.getDuration() > 0)
                    tCur.setText(Ui.time(controller.getDuration() * p / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (controller != null && controller.getDuration() > 0)
                    controller.seekTo(controller.getDuration() * s.getProgress() / 1000);
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** "Open with" / "Share" from another app: one or several audio files. */
    private void handleIntent(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return;
        openedUris = new ArrayList<>();
        ClipData clip = i.getClipData();
        if (clip != null && clip.getItemCount() > 1) {
            for (int k = 0; k < clip.getItemCount(); k++) if (clip.getItemAt(k).getUri() != null) openedUris.add(clip.getItemAt(k).getUri());
        } else openedUris.add(i.getData());
        if (controller != null) playOpened();
    }

    private void playOpened() {
        if (openedUris == null || openedUris.isEmpty() || controller == null) return;
        List<MediaInfo> list = new ArrayList<>();
        for (Uri u : openedUris) list.add(MediaInfo.link(u, Ui.displayName(this, u), false));
        openedUris = null;
        controller.setMediaItems(Media.items(list), 0, 0);
        controller.prepare();
        controller.play();
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) { updateAll(); }
    };

    @Override
    protected void onStart() {
        super.onStart();
        future = Media.connect(this);
        future.addListener(() -> {
            try {
                controller = future.get();
                controller.addListener(listener);
                playOpened();
                updateAll();
                ui.post(tick);
            } catch (Exception ignored) { }
        }, ui::post);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(tick);
        if (controller != null) controller.removeListener(listener);
        controller = null;
        MediaController.releaseFuture(future);
        super.onStop();
    }

    private void updateAll() {
        if (controller == null) return;
        MediaMetadata md = controller.getMediaMetadata();
        title.setText(md.title == null ? getString(R.string.nothing_playing) : md.title);
        artist.setText(md.artist == null ? "" : md.artist);
        play.setImageResource(controller.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);

        int on = getColor(R.color.accent), off = getColor(R.color.muted);
        shuffle.setColorFilter(controller.getShuffleModeEnabled() ? on : off);
        int rm = controller.getRepeatMode();
        repeat.setImageResource(rm == Player.REPEAT_MODE_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repeat.setColorFilter(rm == Player.REPEAT_MODE_OFF ? off : on);

        updateArt(md);
        updateProgress();
        updateFavourite();
        updateUpNext();
        optSpeed.setText(SpeedSheet.label(controller.getPlaybackParameters().speed));
        findViewById(R.id.np_watch).setVisibility(Media.isVideo(md) ? View.VISIBLE : View.GONE);
        if (lyricsOpen()) loadLyricsIfNeeded();
        if (queueAdapter != null) queueAdapter.reload();
    }

    private void updateArt(MediaMetadata md) {
        String id = controller.getCurrentMediaItem() == null ? null : controller.getCurrentMediaItem().mediaId;
        Bitmap b = null;
        if (md.artworkData != null) {
            try { b = BitmapFactory.decodeByteArray(md.artworkData, 0, md.artworkData.length); } catch (Exception ignored) { }
        }
        if (b != null) { Thumbs.showBitmap(art, b); artFor = id; return; }
        if (id == null) { Thumbs.showPlaceholder(art, R.drawable.ic_music); artFor = null; return; }
        if (id.equals(artFor)) return;            // already showing (or fetching) this song's art
        artFor = id;
        Thumbs.showPlaceholder(art, R.drawable.ic_music);
        MediaInfo m = Media.current(controller);
        if (m == null) return;
        final String want = id;
        Thumbs.big(this, m, bmp -> { if (bmp != null && want.equals(artFor)) Thumbs.showBitmap(art, bmp); });
    }

    private void updateProgress() {
        if (controller == null || dragging) return;
        long dur = controller.getDuration(), pos = controller.getCurrentPosition();
        tCur.setText(Ui.time(pos));
        tDur.setText(dur > 0 ? Ui.time(dur) : "--:--");
        seek.setProgress(dur > 0 ? (int) (pos * 1000 / dur) : 0);
    }

    // ── Options ──────────────────────────────────────────────────────────────

    private void openSpeedSheet() {
        if (controller == null) return;
        SpeedSheet.show(this, new SpeedSheet.Target() {
            @Override public float speed() { return controller == null ? 1f : controller.getPlaybackParameters().speed; }
            @Override public void setSpeed(float sp) {
                // keep the karaoke key while changing speed
                if (controller != null) controller.setPlaybackParameters(new PlaybackParameters(sp, controller.getPlaybackParameters().pitch));
                optSpeed.setText(SpeedSheet.label(sp));
            }
        }, true);
    }

    private void updateSleep() {
        PlaybackService svc = PlaybackService.instance;
        if (svc == null) { sleepChip.setVisibility(View.GONE); return; }
        long ms = svc.sleepLeftMs();
        if (svc.sleepIsEndOfSong()) {
            sleepChip.setText(R.string.stops_end_of_song);
            sleepChip.setVisibility(View.VISIBLE);
        } else if (ms > 0) {
            sleepChip.setText(getString(R.string.stops_in, Ui.time(ms)));
            sleepChip.setVisibility(View.VISIBLE);
        } else sleepChip.setVisibility(View.GONE);
    }

    private void updateFavourite() {
        MediaInfo m = Media.current(controller);
        boolean fav = m != null && Prefs.isFavourite(this, m);
        optFav.setCompoundDrawablesRelativeWithIntrinsicBounds(0, fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart, 0, 0);
        optFav.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(getColor(fav ? R.color.accent : R.color.text)));
        optFav.setTextColor(getColor(fav ? R.color.accent : R.color.muted));
    }

    private void toggleFavourite() {
        MediaInfo m = Media.current(controller);
        if (m == null) return;
        boolean now = Prefs.toggleFavourite(this, m);
        Ui.toast(this, now ? getString(R.string.fav_added_to, Prefs.favouritesFor(m)) : getString(R.string.fav_removed_from, Prefs.favouritesFor(m)));
        updateFavourite();
    }

    private void updateUpNext() {
        int next = controller.getNextMediaItemIndex();
        if (next == C.INDEX_UNSET || next >= controller.getMediaItemCount()) {
            upNext.setText(R.string.end_of_queue);
            upNext.setTextColor(getColor(R.color.muted));
            return;
        }
        MediaMetadata md = controller.getMediaItemAt(next).mediaMetadata;
        String t = md.title == null ? getString(R.string.unknown) : md.title.toString();
        if (md.artist != null) t += "  ·  " + md.artist;
        upNext.setText(t);
        upNext.setTextColor(getColor(R.color.text));
    }

    private void moreMenu() {
        MediaInfo m = Media.current(controller);
        if (m == null) return;
        List<String> opts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        opts.add(getString(R.string.add_to_playlist)); ids.add("add");
        if (!m.video) {
            opts.add(getString(R.string.edit_info)); ids.add("edit");
            opts.add(getString(R.string.set_as_ringtone)); ids.add("ringtone");
        }
        opts.add(getString(R.string.share)); ids.add("share");
        if (Downloads.isOnline(m)) { opts.add(getString(R.string.download)); ids.add("download"); }
        boolean local = m.uri != null && ("content".equals(m.uri.getScheme()) || "file".equals(m.uri.getScheme()));
        if (local) { opts.add(getString(R.string.rename)); ids.add("rename"); }
        if (local) { opts.add(getString(R.string.delete_file)); ids.add("delete"); }
        opts.add(getString(R.string.properties)); ids.add("details");
        Dialogs.builder(this)
                .setTitle(m.title)
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    switch (ids.get(w)) {
                        case "add": Dialogs.addToPlaylist(this, m); break;
                        case "edit": MetaEditActivity.open(this, m); break;
                        case "ringtone": Ringtones.set(this, m); break;
                        case "share": Share.send(this, m); break;
                        case "download": Downloads.start(this, m.uri, m.title); break;
                        case "details": FileOps.properties(this, m, deleter, gone -> dropCurrent(), null); break;
                        case "rename": FileOps.rename(this, m, null); break;
                        case "delete": {
                            List<MediaInfo> one = new ArrayList<>();
                            one.add(m);
                            deleter.delete(one, gone -> dropCurrent());
                            break;
                        }
                    }
                })
                .show();
    }

    /** A video sent to background play: open it again in the video player at the same spot. */
    private void watchVideo() {
        if (controller == null) return;
        List<MediaInfo> list = new ArrayList<>();
        for (int i = 0; i < controller.getMediaItemCount(); i++) list.add(Media.info(controller.getMediaItemAt(i), 0));
        int index = controller.getCurrentMediaItemIndex();
        long pos = controller.getCurrentPosition();
        controller.stop();
        controller.clearMediaItems();
        PlayerActivity.open(this, list, index, pos);
        finish();
    }

    // ── Delete the song that's playing: it leaves the queue too ─────────────

    private FileOps.Deleter deleter;

    private void dropCurrent() {
        if (controller == null || controller.getMediaItemCount() == 0) return;
        controller.removeMediaItem(controller.getCurrentMediaItemIndex());
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (deleter != null) deleter.onActivityResult(requestCode, resultCode);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (deleter != null) deleter.onRequestPermissionsResult(requestCode, results);
    }

    // ── Karaoke: one tap; lyrics open big while singing ─────────────────────

    private void toggleKaraoke() {
        boolean on = !Prefs.karaoke(this);
        Prefs.setKaraoke(this, on);
        Ui.toast(this, on ? R.string.karaoke_now_on : R.string.karaoke_now_off);
        showKaraoke();
        if (on && Prefs.karaokeLyrics(this) && !lyricsOpen()) toggleLyrics();
        if (lyrAdapter != null) lyrAdapter.notifyDataSetChanged();
    }

    private void showKaraoke() {
        TextView opt = findViewById(R.id.opt_karaoke);
        boolean on = Prefs.karaoke(this);
        int c = getColor(on ? R.color.accent : R.color.text);
        opt.setTextColor(getColor(on ? R.color.accent : R.color.muted));
        opt.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(c));
    }

    private boolean karaokeStyle() { return Prefs.karaoke(this) && Prefs.karaokeLyrics(this); }

    // ── Lyrics ───────────────────────────────────────────────────────────────

    private View lyricsPanel;
    private RecyclerView lyrList;
    private TextView lyrStatus, lyrSync;
    private LyricAdapter lyrAdapter;
    private Lyrics.Result lyr;
    private String lyrFor;
    private long lyrTouched = 0;
    private int lyrCurrent = -1;

    private boolean lyricsOpen() { return lyricsPanel != null && lyricsPanel.getVisibility() == View.VISIBLE; }

    @SuppressLint("ClickableViewAccessibility")
    private void setupLyrics() {
        lyricsPanel = findViewById(R.id.np_lyrics);
        lyrList = findViewById(R.id.lyr_list);
        lyrStatus = findViewById(R.id.lyr_status);
        lyrSync = findViewById(R.id.lyr_sync);
        lyrList.setLayoutManager(new LinearLayoutManager(this));
        lyrAdapter = new LyricAdapter();
        lyrList.setAdapter(lyrAdapter);
        lyrList.setOnTouchListener((v, e) -> { lyrTouched = System.currentTimeMillis(); return false; });
        findViewById(R.id.lyr_close).setOnClickListener(v -> toggleLyrics());
        findViewById(R.id.lyr_minus).setOnClickListener(v -> nudge(-500));
        findViewById(R.id.lyr_plus).setOnClickListener(v -> nudge(500));
        findViewById(R.id.lyr_search).setOnClickListener(v -> searchAgain());
        if (Prefs.sp(this).getBoolean("lyrics_open", false)) lyricsPanel.setVisibility(View.VISIBLE);
        art.setVisibility(lyricsOpen() ? View.INVISIBLE : View.VISIBLE);
    }

    private void toggleLyrics() {
        boolean open = !lyricsOpen();
        lyricsPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        art.setVisibility(open ? View.INVISIBLE : View.VISIBLE);
        Prefs.sp(this).edit().putBoolean("lyrics_open", open).apply();
        TextView opt = findViewById(R.id.opt_lyrics);
        opt.setTextColor(getColor(open ? R.color.accent : R.color.muted));
        if (open) loadLyricsIfNeeded();
    }

    private void loadLyricsIfNeeded() {
        MediaInfo m = Media.current(controller);
        if (m == null || m.key().equals(lyrFor)) return;
        lyrFor = m.key();
        // the queued song's own details: the player's combined details can still be the previous song's
        // for a moment after changing song, which used to fetch (and keep) the previous song's lyrics
        MediaMetadata md = controller.getCurrentMediaItem().mediaMetadata;
        String t = md.title == null ? m.title : md.title.toString();
        String a = md.artist == null ? "" : md.artist.toString();
        String al = md.albumTitle == null ? "" : md.albumTitle.toString();
        long dur = controller.getDuration() > 0 ? controller.getDuration() : 0;
        fetchLyrics(m, t, a, al, dur, false);
    }

    private void fetchLyrics(MediaInfo m, String t, String a, String al, long dur, boolean onlineOnly) {
        lyr = null;
        lyrCurrent = -1;
        lyrAdapter.notifyDataSetChanged();
        lyrStatus.setText(R.string.lyr_looking);
        lyrStatus.setVisibility(View.VISIBLE);
        showSync(m);
        final String want = m.key();
        new Thread(() -> {
            if (onlineOnly) Lyrics.forget(this, m);
            Lyrics.Result r = onlineOnly ? Lyrics.online(this, m, t, a, al, dur) : Lyrics.find(this, m, t, a, al, dur);
            ui.post(() -> {
                if (!want.equals(lyrFor)) return;
                lyr = r;
                lyrCurrent = -1;
                lyrAdapter.notifyDataSetChanged();
                lyrList.scrollToPosition(0);
                if (r.instrumental) { lyrStatus.setText(R.string.lyr_instrumental); lyrStatus.setVisibility(View.VISIBLE); }
                else if (r.lines.isEmpty()) { lyrStatus.setText(R.string.lyr_none); lyrStatus.setVisibility(View.VISIBLE); }
                else lyrStatus.setVisibility(View.GONE);
                showSync(m);
            });
        }).start();
    }

    private void showSync(MediaInfo m) {
        if (lyr != null && !lyr.lines.isEmpty() && !lyr.synced) { lyrSync.setText(R.string.lyr_plain); return; }
        long off = Lyrics.offset(this, m);
        lyrSync.setText(getString(R.string.lyr_sync, (off >= 0 ? "+" : "−") + String.format(java.util.Locale.US, "%.1f", Math.abs(off) / 1000f)));
    }

    private void nudge(long ms) {
        MediaInfo m = Media.current(controller);
        if (m == null) return;
        Lyrics.setOffset(this, m, Lyrics.offset(this, m) + ms);
        showSync(m);
        lyrCurrent = -1;
        updateLyricLine();
    }

    private void searchAgain() {
        MediaInfo m = Media.current(controller);
        if (m == null) return;
        MediaMetadata md = controller.getMediaMetadata();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText t = new EditText(this);
        t.setHint(R.string.f_title);
        t.setSingleLine(true);
        t.setText(Lyrics.cleanTitle(md.title == null ? m.title : md.title.toString()));
        EditText a = new EditText(this);
        a.setHint(R.string.f_artist);
        a.setSingleLine(true);
        a.setText(md.artist == null ? "" : md.artist);
        box.addView(t);
        box.addView(a);
        Dialogs.builder(this)
                .setTitle(R.string.lyr_search_again)
                .setMessage(R.string.lyr_search_help)
                .setView(box)
                .setPositiveButton(R.string.search, (d, w) -> {
                    lyrFor = m.key();
                    fetchLyrics(m, t.getText().toString(), a.getText().toString(), "",
                            controller == null ? 0 : Math.max(0, controller.getDuration()), true);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Highlights the line being sung and keeps it in view. */
    private void updateLyricLine() {
        if (!lyricsOpen() || controller == null || lyr == null || !lyr.synced || lyr.lines.isEmpty()) return;
        MediaInfo m = Media.current(controller);
        long pos = controller.getCurrentPosition() + (m == null ? 0 : Lyrics.offset(this, m));
        int idx = -1;
        for (int i = 0; i < lyr.lines.size(); i++) { if (lyr.lines.get(i).ms <= pos) idx = i; else break; }
        if (idx == lyrCurrent) return;
        int old = lyrCurrent;
        lyrCurrent = idx;
        if (old >= 0) lyrAdapter.notifyItemChanged(old);
        if (idx >= 0) lyrAdapter.notifyItemChanged(idx);
        if (idx >= 0 && System.currentTimeMillis() - lyrTouched > 4000) {
            LinearLayoutManager lm = (LinearLayoutManager) lyrList.getLayoutManager();
            androidx.recyclerview.widget.LinearSmoothScroller s = new androidx.recyclerview.widget.LinearSmoothScroller(this) {
                @Override public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int pref) {
                    return (boxStart + (boxEnd - boxStart) / 3) - (viewStart + (viewEnd - viewStart) / 2);
                }
            };
            s.setTargetPosition(idx);
            if (lm != null) lm.startSmoothScroll(s);
        }
    }

    private class LyricAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return lyr == null ? 0 : lyr.lines.size(); }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.lyric_line, parent, false)) { };
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            Lyrics.Line l = lyr.lines.get(position);
            TextView t = (TextView) h.itemView;
            t.setText(l.text.isEmpty() ? "♪" : l.text);
            boolean now = position == lyrCurrent;
            boolean plain = !lyr.synced;
            boolean big = karaokeStyle() && !plain;
            t.setTextColor(getColor(now && big ? R.color.accent : plain || now ? R.color.text : R.color.muted));
            t.setTextSize(now ? (big ? 27 : 20) : 17);
            t.setAlpha(plain || now || lyrCurrent < 0 ? 1f : position < lyrCurrent ? 0.45f : 0.75f);
            t.setTypeface(null, now ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            t.setOnClickListener(v -> {
                if (!lyr.synced || controller == null || l.ms < 0) return;
                MediaInfo m = Media.current(controller);
                controller.seekTo(Math.max(0, l.ms - (m == null ? 0 : Lyrics.offset(NowPlayingActivity.this, m))));
                lyrTouched = 0;
            });
        }
    }

    // ── Queue ────────────────────────────────────────────────────────────────

    private QueueAdapter queueAdapter;

    private void openQueue() {
        if (controller == null) return;
        View v = getLayoutInflater().inflate(R.layout.sheet_queue, null);
        ((TextView) v.findViewById(R.id.q_title)).setTypeface(Ui.orbitron(this));
        RecyclerView list = v.findViewById(R.id.q_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        queueAdapter = new QueueAdapter();
        list.setAdapter(queueAdapter);
        ItemTouchHelper drag = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.START | ItemTouchHelper.END) {
            @Override public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder a, RecyclerView.ViewHolder b) {
                int from = a.getBindingAdapterPosition(), to = b.getBindingAdapterPosition();
                if (from < 0 || to < 0 || controller == null) return false;
                queueAdapter.moving = true;
                controller.moveMediaItem(from, to);
                queueAdapter.notifyItemMoved(from, to);
                return true;
            }
            @Override public void onSwiped(RecyclerView.ViewHolder vh, int dir) {
                int p = vh.getBindingAdapterPosition();
                if (p >= 0 && controller != null) controller.removeMediaItem(p);
            }
            @Override public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                queueAdapter.moving = false;
                queueAdapter.reload();
            }
            @Override public boolean isLongPressDragEnabled() { return false; }
        });
        drag.attachToRecyclerView(list);
        queueAdapter.drag = drag;
        int cur = controller.getCurrentMediaItemIndex();
        if (cur > 1) list.scrollToPosition(cur - 1);

        Dialog d = SpeedSheet.bottom(this, v);
        d.setOnDismissListener(x -> queueAdapter = null);
        v.findViewById(R.id.q_clear).setOnClickListener(x -> {
            if (controller == null) return;
            int now = controller.getCurrentMediaItemIndex();
            int count = controller.getMediaItemCount();
            if (now + 1 < count) controller.removeMediaItems(now + 1, count);   // keep the song that's playing
            if (now > 0) controller.removeMediaItems(0, now);
            Ui.toast(this, R.string.queue_cleared);
        });
        v.findViewById(R.id.q_save).setOnClickListener(x -> {
            if (controller == null) return;
            final List<MediaInfo> all = new ArrayList<>();
            for (int i = 0; i < controller.getMediaItemCount(); i++) all.add(Media.info(controller.getMediaItemAt(i), 0));
            Dialogs.newPlaylist(this, name -> {
                for (MediaInfo m : all) Playlists.add(this, name, m);
                Ui.toast(this, getString(R.string.added_to, name));
            });
        });
    }

    private class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.VH> {
        ItemTouchHelper drag;
        boolean moving = false;
        private final List<MediaItem> items = new ArrayList<>();

        QueueAdapter() { reload(); }

        void reload() {
            if (moving || controller == null) return;
            items.clear();
            for (int i = 0; i < controller.getMediaItemCount(); i++) items.add(controller.getMediaItemAt(i));
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return items.size(); }

        @Override public VH onCreateViewHolder(ViewGroup parent, int type) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.queue_item, parent, false));
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public void onBindViewHolder(VH h, int position) {
            MediaMetadata md = items.get(position).mediaMetadata;
            boolean now = controller != null && position == controller.getCurrentMediaItemIndex();
            h.title.setText(md.title == null ? getString(R.string.unknown) : md.title);
            h.title.setTextColor(getColor(now ? R.color.accent : R.color.text));
            h.sub.setText(now ? getString(R.string.playing_now) : (md.artist == null ? "" : md.artist));
            h.itemView.setSelected(now);
            h.itemView.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p >= 0 && controller != null) { controller.seekTo(p, 0); Media.play(controller); }
            });
            h.remove.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p >= 0 && controller != null) controller.removeMediaItem(p);
            });
            h.handle.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN && drag != null) drag.startDrag(h);
                return false;
            });
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView title, sub;
            final View handle, remove;
            VH(View v) {
                super(v);
                title = v.findViewById(R.id.qi_title);
                sub = v.findViewById(R.id.qi_sub);
                handle = v.findViewById(R.id.qi_drag);
                remove = v.findViewById(R.id.qi_remove);
            }
        }
    }
}
