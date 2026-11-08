package com.nkt.mediaplayer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.media3.session.MediaController;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Equalizer: on/off, karaoke, presets, bands, bass boost, surround, loudness. Saved and re-applied every time. */
public class EqualizerActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ListenableFuture<MediaController> future;
    private JSONObject s;
    private final List<SeekBar> bandBars = new ArrayList<>();
    private final List<TextView> bandValues = new ArrayList<>();
    private final List<TextView> presetChips = new ArrayList<>();
    private int[] range = {-1500, 1500};
    private boolean built = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equalizer);
        Ui.padForSystemBars(findViewById(R.id.root));
        ((TextView) findViewById(R.id.eq_title)).setTypeface(Ui.orbitron(this));
        findViewById(R.id.eq_back).setOnClickListener(v -> finish());
        s = Prefs.eq(this);

        Switch on = findViewById(R.id.eq_on);
        on.setChecked(s.optBoolean("on", false));
        on.setOnCheckedChangeListener((b, v) -> { put("on", v); save(); });

        Switch karaoke = findViewById(R.id.eq_karaoke);
        karaoke.setChecked(Prefs.karaoke(this));
        karaoke.setOnCheckedChangeListener((b, v) -> {
            Prefs.setKaraoke(this, v);
            karaokeStatus();
        });
        findViewById(R.id.eq_karaoke_row).setOnClickListener(v -> karaoke.toggle());
        buildKaraokeMore();

        findViewById(R.id.eq_reset).setOnClickListener(v -> {
            s = new JSONObject();
            put("on", ((Switch) findViewById(R.id.eq_on)).isChecked());
            put("preset", -1);
            JSONArray zero = new JSONArray();
            for (int i = 0; i < bandBars.size(); i++) zero.put(0);
            put("bands", zero);
            save();
            built = false;
            build();
        });
    }

    // ── Karaoke: strength, key of this song, lyrics while singing ────────────

    private android.widget.TextView keyValue;
    private final android.widget.TextView[] strengthSegs = new android.widget.TextView[3];

    private void buildKaraokeMore() {
        android.widget.LinearLayout box = findViewById(R.id.eq_karaoke_more);
        box.removeAllViews();
        int dp = Ui.dp(this, 1);
        box.addView(caption(R.string.strength_caps));
        android.widget.LinearLayout seg = new android.widget.LinearLayout(this);
        seg.setBackgroundResource(R.drawable.bg_segment_box);
        seg.setPadding(2 * dp, 2 * dp, 2 * dp, 2 * dp);
        String[] names = getResources().getStringArray(R.array.karaoke_strengths);
        for (int i = 0; i < 3; i++) {
            final int lvl = i;
            android.widget.TextView t = new android.widget.TextView(this);
            t.setText(names[i].toUpperCase(java.util.Locale.ROOT));
            t.setTypeface(Ui.orbitron(this));
            t.setTextSize(10);
            t.setLetterSpacing(0.1f);
            t.setGravity(android.view.Gravity.CENTER);
            t.setBackgroundResource(R.drawable.bg_segment);
            t.setPadding(0, 9 * dp, 0, 9 * dp);
            t.setOnClickListener(v -> { Prefs.setKaraokeStrength(this, lvl); showStrength(); karaokeStatus(); });
            strengthSegs[i] = t;
            seg.addView(t, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(seg);
        showStrength();

        box.addView(caption(R.string.key_caps));
        android.widget.LinearLayout keyRow = new android.widget.LinearLayout(this);
        keyRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        keyRow.addView(keyButton(R.drawable.ic_minus, R.string.cd_key_down, () -> changeKey(-1)));
        keyValue = new android.widget.TextView(this);
        keyValue.setTextColor(getColor(R.color.text));
        keyValue.setTextSize(15);
        keyValue.setGravity(android.view.Gravity.CENTER);
        keyRow.addView(keyValue, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        keyRow.addView(keyButton(R.drawable.ic_plus, R.string.cd_key_up, () -> changeKey(1)));
        keyRow.addView(keyButton(R.drawable.ic_refresh, R.string.cd_key_reset, () -> changeKey(0)));
        box.addView(keyRow);
        showKey();

        android.widget.LinearLayout lyr = new android.widget.LinearLayout(this);
        lyr.setGravity(android.view.Gravity.CENTER_VERTICAL);
        lyr.setPadding(0, 10 * dp, 0, 0);
        android.widget.LinearLayout texts = new android.widget.LinearLayout(this);
        texts.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView t1 = new android.widget.TextView(this);
        t1.setText(R.string.karaoke_lyrics);
        t1.setTextColor(getColor(R.color.text));
        t1.setTextSize(14);
        android.widget.TextView t2 = new android.widget.TextView(this);
        t2.setText(R.string.karaoke_lyrics_sub);
        t2.setTextColor(getColor(R.color.muted));
        t2.setTextSize(12);
        texts.addView(t1);
        texts.addView(t2);
        lyr.addView(texts, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(Prefs.karaokeLyrics(this));
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        sw.setOnCheckedChangeListener((b, on) -> Prefs.setBool(this, "karaoke_lyrics", on));
        lyr.addView(sw);
        lyr.setOnClickListener(v -> sw.toggle());
        box.addView(lyr);
    }

    private android.widget.TextView caption(int text) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(text);
        t.setTypeface(Ui.orbitron(this));
        t.setTextSize(9.5f);
        t.setLetterSpacing(0.14f);
        t.setTextColor(getColor(R.color.muted));
        int dp = Ui.dp(this, 1);
        t.setPadding(0, 8 * dp, 0, 6 * dp);
        return t;
    }

    private android.widget.ImageButton keyButton(int icon, int desc, Runnable r) {
        android.widget.ImageButton b = new android.widget.ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        b.setBackgroundResource(R.drawable.bg_round_btn);
        b.setContentDescription(getString(desc));
        b.setOnClickListener(v -> r.run());
        int s = Ui.dp(this, 40);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(s, s);
        lp.setMarginStart(Ui.dp(this, 6));
        b.setLayoutParams(lp);
        return b;
    }

    private void showStrength() {
        int cur = Prefs.karaokeStrength(this);
        for (int i = 0; i < 3; i++) {
            strengthSegs[i].setSelected(i == cur);
            strengthSegs[i].setTextColor(getColor(i == cur ? R.color.accent : R.color.muted));
        }
    }

    private String currentSong() {
        PlaybackService svc = PlaybackService.instance;
        return svc == null ? null : svc.currentMediaId();
    }

    private static String keyText(int semis) { return semis == 0 ? "0" : (semis > 0 ? "+" : "−") + Math.abs(semis); }

    private void showKey() {
        String id = currentSong();
        keyValue.setText(getString(R.string.key_value, keyText(Prefs.key(this, id))));
        keyValue.setAlpha(id == null ? 0.5f : 1f);
    }

    /** d = -1 down, +1 up, 0 reset. The key is kept for this song only. */
    private void changeKey(int d) {
        String id = currentSong();
        if (id == null) { Ui.toast(this, R.string.nothing_playing); return; }
        int k = d == 0 ? 0 : Math.max(-6, Math.min(6, Prefs.key(this, id) + d));
        Prefs.setKey(this, id, k);
        if (PlaybackService.instance != null) PlaybackService.instance.applyKey();
        showKey();
        karaokeStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Connecting starts the background player, which owns the effects.
        future = Media.connect(this);
        future.addListener(this::build, ui::post);
    }

    @Override
    protected void onStop() {
        MediaController.releaseFuture(future);
        super.onStop();
    }

    private final Runnable statusTick = new Runnable() {
        @Override public void run() { karaokeStatus(); if (keyValue != null) showKey(); ui.postDelayed(this, 1000); }
    };

    @Override
    protected void onResume() { super.onResume(); ui.post(statusTick); }

    @Override
    protected void onPause() { ui.removeCallbacks(statusTick); super.onPause(); }

    /** Tells you whether karaoke is working on the current song. */
    private void karaokeStatus() {
        TextView t = findViewById(R.id.eq_karaoke_status);
        if (!KaraokeProcessor.enabled) { t.setVisibility(View.GONE); return; }
        boolean playing = System.currentTimeMillis() - KaraokeProcessor.lastAudioMs < 2500;
        t.setVisibility(View.VISIBLE);
        if (KaraokeProcessor.status == 2) {
            t.setText(R.string.karaoke_mono);
            t.setTextColor(getColor(R.color.accent));
        } else if (playing) {
            String id = currentSong();
            t.setText(getString(R.string.karaoke_working_detail,
                    getResources().getStringArray(R.array.karaoke_strengths)[Prefs.karaokeStrength(this)],
                    getString(R.string.key_value, keyText(Prefs.key(this, id)))));
            t.setTextColor(getColor(R.color.success));
        } else {
            t.setText(R.string.karaoke_waiting);
            t.setTextColor(getColor(R.color.muted));
        }
    }

    private void put(String k, Object v) { try { s.put(k, v); } catch (Exception ignored) { } }

    private void save() {
        Prefs.setEq(this, s);
        AudioFx.apply(this);
    }

    private void build() {
        if (built) return;
        if (!AudioFx.available()) {
            findViewById(R.id.eq_missing).setVisibility(View.VISIBLE);
            findViewById(R.id.eq_body).setVisibility(View.GONE);
            return;
        }
        built = true;
        range = AudioFx.range();

        // presets
        LinearLayout presets = findViewById(R.id.eq_presets);
        presets.removeAllViews();
        presetChips.clear();
        String[] names = AudioFx.presets();
        addPresetChip(presets, getString(R.string.custom), -1);
        for (int i = 0; i < names.length; i++) addPresetChip(presets, names[i], i);
        markPreset();

        // bands
        LinearLayout bands = findViewById(R.id.eq_bands);
        bands.removeAllViews();
        bandBars.clear();
        bandValues.clear();
        int n = AudioFx.bandCount();
        for (int b = 0; b < n; b++) {
            final int band = b;
            View row = getLayoutInflater().inflate(R.layout.eq_slider, bands, false);
            ((TextView) row.findViewById(R.id.es_label)).setText(AudioFx.bandLabel(b) + " Hz");
            SeekBar bar = row.findViewById(R.id.es_bar);
            TextView val = row.findViewById(R.id.es_value);
            bar.setMax(range[1] - range[0]);
            int lvl = AudioFx.bandLevel(b);
            bar.setProgress(lvl - range[0]);
            val.setText(db(lvl));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) return;
                    val.setText(db(p + range[0]));
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    JSONArray arr = new JSONArray();
                    for (int i = 0; i < bandBars.size(); i++)
                        arr.put(i == band ? sb.getProgress() + range[0] : bandBars.get(i).getProgress() + range[0]);
                    put("bands", arr);
                    put("preset", -1);
                    turnOn();
                    save();
                    markPreset();
                }
            });
            bandBars.add(bar);
            bandValues.add(val);
            bands.addView(row);
        }

        // effects
        LinearLayout fx = findViewById(R.id.eq_effects);
        fx.removeAllViews();
        addEffect(fx, getString(R.string.bass_boost), "bass", 1000);
        addEffect(fx, getString(R.string.surround), "virt", 1000);
        addEffect(fx, getString(R.string.loudness), "loud", 1000);
    }

    private void addPresetChip(LinearLayout box, String name, int index) {
        TextView chip = new TextView(this);
        chip.setText(name);
        chip.setTextColor(getColor(R.color.text));
        chip.setTextSize(13);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setBackgroundResource(R.drawable.bg_chip);
        int pad = dp(16);
        chip.setPadding(pad, 0, pad, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMarginEnd(dp(8));
        chip.setTag(index);
        chip.setOnClickListener(v -> {
            put("preset", index);
            turnOn();
            save();
            markPreset();
            // show the preset's band levels on the sliders
            JSONArray arr = new JSONArray();
            for (int i = 0; i < bandBars.size(); i++) {
                int lvl = AudioFx.bandLevel(i);
                bandBars.get(i).setProgress(lvl - range[0]);
                bandValues.get(i).setText(db(lvl));
                arr.put(lvl);
            }
            put("bands", arr);
            Prefs.setEq(this, s);
        });
        box.addView(chip, lp);
        presetChips.add(chip);
    }

    private void markPreset() {
        int p = s.optInt("preset", -1);
        for (TextView c : presetChips) {
            boolean on = (int) c.getTag() == p;
            c.setSelected(on);
            c.setTextColor(getColor(on ? R.color.accent : R.color.text));
        }
    }

    private void addEffect(LinearLayout box, String label, String key, int max) {
        View row = getLayoutInflater().inflate(R.layout.eq_slider, box, false);
        ((TextView) row.findViewById(R.id.es_label)).setText(label);
        SeekBar bar = row.findViewById(R.id.es_bar);
        TextView val = row.findViewById(R.id.es_value);
        bar.setMax(max);
        int v0 = s.optInt(key, 0);
        bar.setProgress(v0);
        val.setText(pct(v0, max));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { val.setText(pct(p, max)); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                put(key, sb.getProgress());
                if (sb.getProgress() > 0) turnOn();
                save();
            }
        });
        box.addView(row);
    }

    private void turnOn() {
        Switch on = findViewById(R.id.eq_on);
        if (!on.isChecked()) on.setChecked(true);     // listener saves "on"
        put("on", true);
    }

    private static String db(int mB) {
        float d = mB / 100f;
        return (d > 0 ? "+" : "") + String.format(Locale.US, "%.0f dB", d);
    }

    private static String pct(int v, int max) { return Math.round(v * 100f / max) + "%"; }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
