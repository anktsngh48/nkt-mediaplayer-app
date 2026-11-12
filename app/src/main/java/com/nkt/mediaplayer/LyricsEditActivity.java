package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edit the lyrics of the song that is playing. TEXT: type or paste (lines may start with [mm:ss.xx]).
 * TIMING: play the song and stamp each line when it is sung. SAVE keeps them on this phone; they are used
 * first the next time this song plays.
 */
public class LyricsEditActivity extends Activity {

    private static final String X_URI = "uri", X_TITLE = "title", X_ARTIST = "artist", X_ALBUM = "album";
    private static final Pattern TAG = Pattern.compile("^\\s*\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    public static void open(Context c, MediaInfo m, String title, String artist, String album) {
        c.startActivity(new Intent(c, LyricsEditActivity.class)
                .putExtra(X_URI, m.uri.toString()).putExtra(X_TITLE, title)
                .putExtra(X_ARTIST, artist).putExtra(X_ALBUM, album));
    }

    private static class Entry {
        long ms = -1;
        String words = "";
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaInfo song;
    private String title, artist, album;
    private ListenableFuture<MediaController> future;
    private MediaController controller;

    private View pageText, pageTiming, linesEmpty;
    private TextView[] tabs = new TextView[2];
    private android.widget.EditText text;
    private TextView sub, cur, dur;
    private SeekBar seek;
    private ImageButton play;
    private RecyclerView list;
    private LineAdapter adapter;
    private final List<Entry> entries = new ArrayList<>();
    private int selected = 0;
    private int page = 0;
    private boolean dirty = false, loaded = false, typing = true, dragging = false;

    // undo / redo of the text
    private final ArrayList<String> undo = new ArrayList<>(), redo = new ArrayList<>();
    private String before = "";
    private long lastPush = 0;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            progress();
            ui.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lyrics_edit);
        Ui.padForSystemBars(findViewById(R.id.root));
        Intent in = getIntent();
        Uri uri = Uri.parse(in.getStringExtra(X_URI));
        title = nz(in.getStringExtra(X_TITLE));
        artist = nz(in.getStringExtra(X_ARTIST));
        album = nz(in.getStringExtra(X_ALBUM));
        long id = -1;
        try { if ("content".equals(uri.getScheme())) id = android.content.ContentUris.parseId(uri); } catch (Exception ignored) { }
        song = new MediaInfo(id, uri, title, artist, album, -1, "", 0, false);

        ((TextView) findViewById(R.id.le_title)).setTypeface(Ui.orbitron(this));
        TextView save = findViewById(R.id.le_save);
        save.setTypeface(Ui.orbitron(this));
        save.setOnClickListener(v -> save());
        findViewById(R.id.le_back).setOnClickListener(v -> onBackPressed());
        ((TextView) findViewById(R.id.le_song)).setText(title);
        sub = findViewById(R.id.le_sub);
        sub.setText(artist);

        pageText = findViewById(R.id.le_page_text);
        pageTiming = findViewById(R.id.le_page_timing);
        linesEmpty = findViewById(R.id.le_lines_empty);
        text = findViewById(R.id.le_text);
        cur = findViewById(R.id.le_cur);
        dur = findViewById(R.id.le_dur);
        seek = findViewById(R.id.le_seek);
        play = findViewById(R.id.le_play);
        list = findViewById(R.id.le_lines);

        // tabs
        LinearLayout box = findViewById(R.id.le_tabs);
        int[] labels = {R.string.lyr_tab_text, R.string.lyr_tab_timing};
        for (int i = 0; i < 2; i++) {
            final int t = i;
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTypeface(Ui.orbitron(this));
            b.setTextSize(10);
            b.setLetterSpacing(0.14f);
            b.setGravity(Gravity.CENTER);
            b.setBackgroundResource(R.drawable.bg_segment);
            b.setOnClickListener(v -> showPage(t));
            box.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            tabs[i] = b;
        }

        // text editing + undo / redo
        text.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { if (typing) before = s.toString(); }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                if (!typing) return;
                dirty = true;
                long now = System.currentTimeMillis();
                if (now - lastPush > 800 || undo.isEmpty()) { undo.add(before); if (undo.size() > 100) undo.remove(0); }
                lastPush = now;
                redo.clear();
            }
        });
        findViewById(R.id.le_undo).setOnClickListener(v -> {
            if (undo.isEmpty()) return;
            redo.add(text.getText().toString());
            setText(undo.remove(undo.size() - 1));
        });
        findViewById(R.id.le_redo).setOnClickListener(v -> {
            if (redo.isEmpty()) return;
            undo.add(text.getText().toString());
            setText(redo.remove(redo.size() - 1));
        });
        findViewById(R.id.le_paste).setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            ClipData d = cm == null ? null : cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return;
            CharSequence t = d.getItemAt(0).coerceToText(this);
            int a = Math.max(0, text.getSelectionStart()), b = Math.max(0, text.getSelectionEnd());
            text.getText().replace(Math.min(a, b), Math.max(a, b), t);
        });
        findViewById(R.id.le_online).setOnClickListener(v -> searchOnline());

        // timing
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LineAdapter();
        list.setAdapter(adapter);
        play.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.isPlaying()) controller.pause(); else Media.play(controller);
        });
        findViewById(R.id.le_back5).setOnClickListener(v -> skip(-5000));
        findViewById(R.id.le_fwd5).setOnClickListener(v -> skip(5000));
        findViewById(R.id.le_stamp).setOnClickListener(v -> stamp());
        findViewById(R.id.le_earlier).setOnClickListener(v -> fine(-100));
        findViewById(R.id.le_later).setOnClickListener(v -> fine(100));
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && controller != null && controller.getDuration() > 0) cur.setText(clock(controller.getDuration() * p / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (controller != null && controller.getDuration() > 0) controller.seekTo(controller.getDuration() * s.getProgress() / 1000);
            }
        });

        showPage(0);
        loadText();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void setText(String t) {
        typing = false;
        text.setText(t);
        text.setSelection(t.length());
        typing = true;
        dirty = true;
    }

    /** What is saved already, else what would be shown while playing (saved copy, .lrc, inside the song, online). */
    private void loadText() {
        text.setEnabled(false);
        new Thread(() -> {
            String t = Lyrics.savedText(this, song);
            Lyrics.Result r = null;
            if (t == null || Lyrics.parse(t).isEmpty()) {
                r = Lyrics.find(this, song, title, artist, album, 0);
                t = Lyrics.toText(r);
            }
            final String shown = t == null ? "" : t;
            final String kind = shown.isEmpty() ? getString(R.string.lyr_none_word)
                    : Lyrics.parse(shown).synced ? getString(R.string.lyr_synced_word) : getString(R.string.lyr_plain_word);
            ui.post(() -> {
                typing = false;
                text.setText(shown);
                typing = true;
                text.setEnabled(true);
                undo.clear();
                redo.clear();
                dirty = false;
                loaded = true;
                sub.setText(artist.isEmpty() ? kind : getString(R.string.lyr_song_sub, artist, kind));
            });
        }).start();
    }

    private void searchOnline() {
        if (!loaded) return;
        Ui.toast(this, R.string.lyr_looking);
        new Thread(() -> {
            String keep = Lyrics.savedText(this, song);           // searching saves its result; put the old copy back
            Lyrics.Result r = Lyrics.online(this, song, title, artist, album, controller != null && controller.getDuration() > 0 ? controller.getDuration() : 0);
            if (keep == null) Lyrics.forget(this, song); else Lyrics.saveText(this, song, keep);
            final String t = Lyrics.toText(r);
            ui.post(() -> {
                if (t.isEmpty()) { Ui.toast(this, R.string.lyr_nothing_online); return; }
                undo.add(text.getText().toString());
                setText(t);
            });
        }).start();
    }

    // ── Pages ────────────────────────────────────────────────────────────────

    private void showPage(int p) {
        if (page == 1 && p == 0) entriesToText();
        if (p == 1) textToEntries();
        page = p;
        pageText.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        pageTiming.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 2; i++) {
            tabs[i].setActivated(i == p);
            tabs[i].setTextColor(getColor(i == p ? R.color.accent : R.color.muted));
        }
        if (p == 1) {
            android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (im != null) im.hideSoftInputFromWindow(text.getWindowToken(), 0);
        }
    }

    private void textToEntries() {
        entries.clear();
        String[] lines = text.getText().toString().split("\\r?\\n", -1);
        int n = lines.length;
        while (n > 0 && lines[n - 1].trim().isEmpty()) n--;
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            Matcher m = TAG.matcher(lines[i]);
            String rest = lines[i];
            if (m.find()) {
                String f = m.group(3);
                long frac = f == null ? 0 : f.length() == 1 ? Long.parseLong(f) * 100 : f.length() == 2 ? Long.parseLong(f) * 10 : Long.parseLong(f);
                e.ms = Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000 + frac;
                rest = lines[i].substring(m.end());
            }
            e.words = rest.trim();
            entries.add(e);
        }
        if (selected >= entries.size()) selected = Math.max(0, entries.size() - 1);
        adapter.notifyDataSetChanged();
        linesEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void entriesToText() {
        if (!dirtyEntries) return;
        StringBuilder b = new StringBuilder();
        for (Entry e : entries) {
            if (b.length() > 0) b.append('\n');
            if (e.ms >= 0) b.append(Lyrics.stamp(e.ms));
            b.append(e.words);
        }
        undo.add(text.getText().toString());
        setText(b.toString());
        dirtyEntries = false;
    }

    private boolean dirtyEntries = false;

    // ── Timing ───────────────────────────────────────────────────────────────

    private void stamp() {
        if (controller == null || entries.isEmpty()) return;
        entries.get(selected).ms = Math.max(0, controller.getCurrentPosition());
        dirtyEntries = true;
        dirty = true;
        int old = selected;
        if (selected + 1 < entries.size()) selected++;
        adapter.notifyItemChanged(old);
        adapter.notifyItemChanged(selected);
        list.smoothScrollToPosition(Math.min(entries.size() - 1, selected + 1));
    }

    private void fine(long ms) {
        if (entries.isEmpty()) return;
        Entry e = entries.get(selected);
        if (e.ms < 0) return;
        e.ms = Math.max(0, e.ms + ms);
        dirtyEntries = true;
        dirty = true;
        adapter.notifyItemChanged(selected);
        if (controller != null) controller.seekTo(Math.max(0, e.ms - 1500));
    }

    private void skip(long ms) {
        if (controller == null) return;
        long to = Math.max(0, controller.getCurrentPosition() + ms);
        if (controller.getDuration() > 0) to = Math.min(to, controller.getDuration());
        controller.seekTo(to);
    }

    private void progress() {
        if (controller == null) return;
        long pos = controller.getCurrentPosition(), d = controller.getDuration();
        play.setImageResource(controller.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        if (dragging) return;
        cur.setText(clock(pos));
        dur.setText(d > 0 ? Ui.time(d) : "--:--");
        seek.setProgress(d > 0 ? (int) (pos * 1000 / d) : 0);
    }

    private static String clock(long ms) {
        return String.format(java.util.Locale.US, "%d:%04.1f", ms / 60000, (ms % 60000) / 1000f);
    }

    private class LineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return entries.size(); }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int d = Ui.dp(parent.getContext(), 1);
            row.setPadding(8 * d, 6 * d, 12 * d, 6 * d);
            row.setMinimumHeight(52 * d);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 6 * d;
            row.setLayoutParams(lp);
            TextView t = new TextView(parent.getContext());
            t.setId(android.R.id.text1);
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            t.setTextSize(12);
            t.setGravity(Gravity.CENTER);
            row.addView(t, new LinearLayout.LayoutParams(82 * d, 30 * d));
            TextView w = new TextView(parent.getContext());
            w.setId(android.R.id.text2);
            w.setTextSize(14);
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            wl.setMarginStart(12 * d);
            row.addView(w, wl);
            return new RecyclerView.ViewHolder(row) { };
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            Entry e = entries.get(position);
            View row = h.itemView;
            TextView t = row.findViewById(android.R.id.text1), w = row.findViewById(android.R.id.text2);
            boolean sel = position == selected, done = e.ms >= 0;
            float d = row.getResources().getDisplayMetrics().density;
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(12 * d);
            bg.setColor(sel ? 0x1A00E5FF : done ? 0xFF111827 : 0x00000000);
            bg.setStroke(Math.round(d), sel ? 0xFF00E5FF : 0x1F00E5FF);
            row.setBackground(bg);
            android.graphics.drawable.GradientDrawable chip = new android.graphics.drawable.GradientDrawable();
            chip.setCornerRadius(8 * d);
            if (sel) chip.setStroke(Math.round(d), 0xFF00E5FF);
            else if (done) chip.setColor(0x2E7B61FF);
            else chip.setStroke(Math.round(d), 0xFF5F6F89);
            t.setBackground(chip);
            t.setText(done ? Lyrics.stamp(e.ms).replace("[", "").replace("]", "") : "--:--.--");
            t.setTextColor(sel ? 0xFF00E5FF : done ? 0xFFB3A6FF : 0xFF8A9AB2);
            w.setText(e.words.isEmpty() ? "♪" : e.words);
            w.setTextColor(getColor(done || sel ? R.color.text : R.color.muted));
            row.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p == RecyclerView.NO_POSITION) return;
                int old = selected;
                selected = p;
                notifyItemChanged(old);
                notifyItemChanged(selected);
                Entry en = entries.get(p);
                if (en.ms >= 0 && controller != null) controller.seekTo(Math.max(0, en.ms - 500));
            });
        }
    }

    // ── Save / leave ─────────────────────────────────────────────────────────

    private void save() {
        if (!loaded) return;
        if (page == 1) entriesToText();
        final String t = text.getText().toString();
        new Thread(() -> {
            Lyrics.saveText(this, song, t);
            ui.post(() -> {
                Ui.toast(this, R.string.lyr_saved);
                dirty = false;
                setResult(RESULT_OK);
                finish();
            });
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (!dirty && !dirtyEntries) { super.onBackPressed(); return; }
        Dialogs.builder(this).setMessage(R.string.lyr_discard)
                .setPositiveButton(R.string.lyr_leave, (d, w) -> { dirty = false; dirtyEntries = false; finish(); })
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ── The song, to stamp against ───────────────────────────────────────────

    @Override
    protected void onStart() {
        super.onStart();
        future = Media.connect(this);
        future.addListener(() -> {
            try {
                controller = future.get();
                ui.post(tick);
            } catch (Exception ignored) { }
        }, ui::post);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(tick);
        controller = null;
        MediaController.releaseFuture(future);
        super.onStop();
    }
}
