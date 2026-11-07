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
import android.widget.Toast;
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
 * plus MX-style options: equalizer, sleep timer, speed, queue, favourite, share, ⋮ menu.
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
    private Uri openedUri;      // a file opened from another app ("Open with")

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            updateSleep();
            ui.postDelayed(this, 500);
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
                controller.play();
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
        findViewById(R.id.opt_share).setOnClickListener(v -> {
            MediaInfo m = Media.current(controller);
            if (m != null) Share.send(this, m);
        });
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

    /** "Open with NKT Media Player" on an audio file. */
    private void handleIntent(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            openedUri = i.getData();
            if (controller != null) playOpened();
        }
    }

    private void playOpened() {
        if (openedUri == null || controller == null) return;
        List<MediaInfo> one = new ArrayList<>();
        one.add(MediaInfo.link(openedUri, Ui.displayName(this, openedUri), false));
        openedUri = null;
        controller.setMediaItems(Media.items(one), 0, 0);
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
                if (controller != null) controller.setPlaybackParameters(new PlaybackParameters(sp));
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
        boolean fav = m != null && Prefs.isFavourite(this, m.uri.toString());
        optFav.setCompoundDrawablesRelativeWithIntrinsicBounds(0, fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart, 0, 0);
        optFav.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(getColor(fav ? R.color.accent : R.color.text)));
        optFav.setTextColor(getColor(fav ? R.color.accent : R.color.muted));
    }

    private void toggleFavourite() {
        MediaInfo m = Media.current(controller);
        if (m == null) return;
        if (Prefs.isFavourite(this, m.uri.toString())) {
            Playlists.removeUri(this, Prefs.FAVOURITES, m.uri.toString());
            Toast.makeText(this, R.string.fav_removed, Toast.LENGTH_SHORT).show();
        } else {
            if (!Playlists.exists(this, Prefs.FAVOURITES)) Playlists.create(this, Prefs.FAVOURITES);
            Playlists.add(this, Prefs.FAVOURITES, m);
            Toast.makeText(this, R.string.fav_added, Toast.LENGTH_SHORT).show();
        }
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
        String[] items = {getString(R.string.add_to_playlist), getString(R.string.edit_info), getString(R.string.details)};
        Dialogs.builder(this)
                .setTitle(m.title)
                .setItems(items, (d, w) -> {
                    if (w == 0) Dialogs.addToPlaylist(this, m);
                    else if (w == 1) MetaEditActivity.open(this, m);
                    else Dialogs.details(this, m);
                })
                .show();
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
            Toast.makeText(this, R.string.queue_cleared, Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.q_save).setOnClickListener(x -> {
            if (controller == null) return;
            final List<MediaInfo> all = new ArrayList<>();
            for (int i = 0; i < controller.getMediaItemCount(); i++) all.add(Media.info(controller.getMediaItemAt(i), 0));
            Dialogs.newPlaylist(this, name -> {
                for (MediaInfo m : all) Playlists.add(this, name, m);
                Toast.makeText(this, getString(R.string.added_to, name), Toast.LENGTH_SHORT).show();
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
                if (p >= 0 && controller != null) { controller.seekTo(p, 0); controller.play(); }
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
