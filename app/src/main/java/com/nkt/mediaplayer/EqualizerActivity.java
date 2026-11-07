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
        karaoke.setChecked(Prefs.sp(this).getBoolean("karaoke", false));
        karaoke.setOnCheckedChangeListener((b, v) -> {
            KaraokeProcessor.enabled = v;
            Prefs.sp(this).edit().putBoolean("karaoke", v).apply();
        });
        findViewById(R.id.eq_karaoke_row).setOnClickListener(v -> karaoke.toggle());

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
