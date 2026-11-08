package com.nkt.mediaplayer;

import android.app.Activity;
import android.media.audiofx.Equalizer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Phone equalizer, bass, surround, loudness, plus NKT MP's own karaoke (vocal removal). */
public class EqualizerActivity extends Activity {
    private LinearLayout body;
    private Switch onSw;
    private TextView karStatus;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<SeekBar> bandBars = new ArrayList<>();
    private final List<TextView> bandVals = new ArrayList<>();
    private LinearLayout presetRow;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = Ui.col(this);
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 24));
        sv.addView(col);
        LinearLayout top = Ui.row(this);
        ImageButton back = Ui.roundBtn(this, "back", 44, Ui.TEXT, true);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = Ui.label(this, "Equalizer", 13, Ui.TEXT, 0.2f);
        t.setPadding(Ui.dp(this, 12), 0, 0, 0);
        top.addView(t, Ui.lpw(0, Ui.WRAP, 1));
        onSw = Ui.toggle(this, AudioFx.state(this).optBoolean("on", false));
        onSw.setOnCheckedChangeListener((x, on) -> edit(o -> o.put("on", on)));
        top.addView(onSw);
        col.addView(top);

        // karaoke card
        LinearLayout kc = Ui.col(this);
        kc.setBackground(Ui.rect(this, Ui.CARD, 16, Ui.BORDER, 1));
        kc.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        LinearLayout kr = Ui.row(this);
        ImageView mic = new ImageView(this);
        mic.setImageDrawable(new Icon(this, "mic_off", Ui.ACCENT));
        mic.setBackground(Ui.rect(this, 0x1F00E5FF, 12, 0, 0));
        int mp = Ui.dp(this, 10);
        mic.setPadding(mp, mp, mp, mp);
        kr.addView(mic, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout kt = Ui.col(this);
        kt.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        TextView kn = Ui.text(this, "Karaoke", 15, Ui.TEXT);
        kn.setTypeface(null, android.graphics.Typeface.BOLD);
        kt.addView(kn);
        kt.addView(Ui.text(this, "Removes the main vocals (works best on studio songs)", 12, Ui.MUTED));
        kr.addView(kt, Ui.lpw(0, Ui.WRAP, 1));
        Switch ks = Ui.toggle(this, Store.bool(this, "karaoke", false));
        ks.setOnCheckedChangeListener((x, on) -> { Store.put(this, "karaoke", on); KaraokeProcessor.enabled = on; });
        kr.addView(ks);
        kc.addView(kr);
        karStatus = Ui.text(this, "", 12, Ui.MUTED);
        karStatus.setPadding(Ui.dp(this, 52), Ui.dp(this, 10), 0, 0);
        kc.addView(karStatus);
        LinearLayout str = Ui.row(this);
        str.setPadding(Ui.dp(this, 52), Ui.dp(this, 10), 0, 0);
        TextView sl = Ui.label(this, "Strength", 9, Ui.MUTED, 0.16f);
        str.addView(sl, Ui.lp(Ui.dp(this, 76), Ui.WRAP));
        String[] names = {"Light", "Normal", "Strong"};
        float[] vals = {0.6f, 0.85f, 1f};
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            float v = vals[i];
            TextView c = Ui.chip(this, names[i], Math.abs(KaraokeProcessor.strength - v) < 0.01f);
            chips.add(c);
            c.setOnClickListener(x -> {
                KaraokeProcessor.strength = v;
                Store.prefs(this).edit().putFloat("karaoke_strength", v).apply();
                for (int k = 0; k < 3; k++) Ui.setChip(this, chips.get(k), Math.abs(vals[k] - v) < 0.01f);
            });
            str.addView(c, Ui.lpm(this, 0, Ui.dp(this, 34), 0, 0, 6, 0));
            ((LinearLayout.LayoutParams) c.getLayoutParams()).weight = 1;
        }
        kc.addView(str);
        col.addView(kc, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));

        body = Ui.col(this);
        col.addView(body);
        setContentView(sv);
        Ui.edgeToEdge(this, sv);
        // make sure the music service (and its effects) exist
        Media.get(this, c -> h.post(this::buildEq));
        buildEq();
    }

    interface Edit { void apply(JSONObject o) throws Exception; }

    private void edit(Edit e) {
        JSONObject o = AudioFx.state(this);
        try { e.apply(o); } catch (Exception ignored) {}
        AudioFx.save(this, o);
    }

    private void buildEq() {
        body.removeAllViews();
        bandBars.clear();
        bandVals.clear();
        Equalizer eq = AudioFx.eq;
        if (eq == null) {
            TextView t = Ui.text(this, PlaybackService.instance == null ? "Play a song once to set up the equalizer." :
                    "This phone doesn't offer an equalizer to apps. Karaoke still works.", 14, Ui.MUTED);
            t.setPadding(Ui.dp(this, 4), Ui.dp(this, 20), 0, 0);
            body.addView(t);
            return;
        }
        JSONObject st = AudioFx.state(this);
        body.addView(Ui.section(this, "Presets"));
        presetRow = Ui.row(this);
        body.addView(Ui.chipRow(this, presetRow));
        int cur = st.optInt("preset", -1);
        List<String> pn = new ArrayList<>();
        pn.add("Custom");
        try { for (short i = 0; i < eq.getNumberOfPresets(); i++) pn.add(eq.getPresetName(i)); } catch (Throwable ignored) {}
        for (int i = 0; i < pn.size(); i++) {
            final int p = i - 1;
            TextView c = Ui.chip(this, pn.get(i), p == cur);
            c.setOnClickListener(v -> {
                edit(o -> { o.put("preset", p); o.put("on", true); });
                onSw.setChecked(true);
                if (p >= 0) {
                    JSONArray arr = new JSONArray();
                    try { for (short k = 0; k < eq.getNumberOfBands(); k++) arr.put(eq.getBandLevel(k)); } catch (Throwable ignored) {}
                    edit(o -> o.put("bands", arr));
                }
                buildEq();
            });
            presetRow.addView(c, Ui.lpm(this, Ui.WRAP, Ui.dp(this, 36), 0, 0, 8, 0));
        }
        body.addView(Ui.section(this, "Bands"));
        short[] range;
        try { range = eq.getBandLevelRange(); } catch (Throwable t) { range = new short[]{-1500, 1500}; }
        final short min = range[0], max = range[1];
        JSONArray bands = st.optJSONArray("bands");
        short n;
        try { n = eq.getNumberOfBands(); } catch (Throwable t) { n = 0; }
        for (short i = 0; i < n; i++) {
            final short band = i;
            LinearLayout r = Ui.row(this);
            int hz;
            try { hz = eq.getCenterFreq(band) / 1000; } catch (Throwable t) { hz = 0; }
            TextView lab = Ui.text(this, hz >= 1000 ? (hz / 1000) + "k Hz" : hz + " Hz", 12, Ui.MUTED);
            r.addView(lab, Ui.lp(Ui.dp(this, 60), Ui.WRAP));
            SeekBar s = new SeekBar(this);
            Ui.styleSeek(s);
            s.setMax(max - min);
            int lvl;
            try { lvl = bands != null && band < bands.length() ? bands.optInt(band) : eq.getBandLevel(band); } catch (Throwable t) { lvl = 0; }
            s.setProgress(lvl - min);
            TextView val = Ui.text(this, db(lvl), 12, Ui.ACCENT);
            val.setGravity(Gravity.END);
            s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    val.setText(db(p + min));
                    if (!u) return;
                    try { eq.setBandLevel(band, (short) (p + min)); } catch (Throwable ignored) {}
                }
                public void onStartTrackingTouch(SeekBar sb) {}
                public void onStopTrackingTouch(SeekBar sb) {
                    edit(o -> {
                        JSONArray a = new JSONArray();
                        for (SeekBar x : bandBars) a.put(x.getProgress() + min);
                        o.put("bands", a);
                        o.put("preset", -1);
                        o.put("on", true);
                    });
                    onSw.setChecked(true);
                    refreshPresetChips();
                }
            });
            bandBars.add(s);
            bandVals.add(val);
            r.addView(s, Ui.lpw(0, Ui.WRAP, 1));
            r.addView(val, Ui.lp(Ui.dp(this, 56), Ui.WRAP));
            body.addView(r, Ui.lp(Ui.MATCH, Ui.dp(this, 42)));
        }
        body.addView(Ui.section(this, "Effects"));
        effect("Bass", "bass", AudioFx.bass != null);
        effect("Surround", "virt", AudioFx.virt != null);
        effect("Loudness", "loud", AudioFx.loud != null);
        TextView reset = Ui.pill(this, "RESET", false, Ui.ACCENT);
        reset.setOnClickListener(v -> {
            edit(o -> {
                JSONArray a = new JSONArray();
                for (int i = 0; i < bandBars.size(); i++) a.put(0);
                o.put("bands", a); o.put("preset", -1); o.put("bass", 0); o.put("virt", 0); o.put("loud", 0);
            });
            buildEq();
        });
        LinearLayout.LayoutParams rp = Ui.lpm(this, Ui.WRAP, Ui.dp(this, 46), 0, 24, 0, 0);
        rp.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(reset, rp);
    }

    private void refreshPresetChips() {
        if (presetRow == null) return;
        for (int i = 0; i < presetRow.getChildCount(); i++) Ui.setChip(this, (TextView) presetRow.getChildAt(i), i == 0);
    }

    private void effect(String name, String key, boolean available) {
        if (!available) return;
        LinearLayout r = Ui.row(this);
        r.addView(Ui.text(this, name, 13, Ui.TEXT), Ui.lp(Ui.dp(this, 80), Ui.WRAP));
        SeekBar s = new SeekBar(this);
        Ui.styleSeek(s);
        s.setMax(1000);
        s.setProgress(AudioFx.state(this).optInt(key, 0));
        TextView val = Ui.text(this, (s.getProgress() / 10) + "%", 12, Ui.ACCENT);
        val.setGravity(Gravity.END);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) { val.setText((p / 10) + "%"); }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                edit(o -> { o.put(key, sb.getProgress()); if (sb.getProgress() > 0) o.put("on", true); });
                if (sb.getProgress() > 0) onSw.setChecked(true);
            }
        });
        r.addView(s, Ui.lpw(0, Ui.WRAP, 1));
        r.addView(val, Ui.lp(Ui.dp(this, 48), Ui.WRAP));
        body.addView(r, Ui.lp(Ui.MATCH, Ui.dp(this, 42)));
    }

    static String db(int mb) {
        float d = mb / 100f;
        return (d > 0 ? "+" : "") + String.format(Locale.US, d == Math.round(d) ? "%.0f" : "%.1f", d) + " dB";
    }

    private final Runnable status = new Runnable() {
        @Override public void run() {
            String s = KaraokeProcessor.status();
            karStatus.setText(s);
            karStatus.setTextColor(s.startsWith("●") ? Ui.SUCCESS : s.startsWith("This song") ? Ui.ACCENT : Ui.MUTED);
            h.postDelayed(this, 1000);
        }
    };

    @Override protected void onResume() { super.onResume(); h.post(status); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(status); }
}
