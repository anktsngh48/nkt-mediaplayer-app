package com.nkt.mediaplayer;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Settings → PLAYBACK → Now Playing opens with: the first view, and the visualiser style. */
public class DefaultViewActivity extends Activity {

    private LinearLayout box;
    private final List<Runnable> redraw = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_options);
        Ui.padForSystemBars(findViewById(R.id.root));
        TextView title = findViewById(R.id.op_title);
        title.setText(R.string.np_opens_caps);
        title.setTypeface(Ui.orbitron(this));
        findViewById(R.id.op_back).setOnClickListener(v -> finish());
        box = findViewById(R.id.op_box);

        header(R.string.np_default_view_caps);
        addView(R.string.np_view_viz, R.string.np_view_viz_sub, Prefs.VIEW_VIZ);
        addView(R.string.np_view_lyrics, 0, Prefs.VIEW_LYRICS);
        addView(R.string.np_view_art, 0, Prefs.VIEW_ART);
        header(R.string.np_viz_style_caps);
        addStyle(R.string.viz_windscreen, 0);
        addStyle(R.string.viz_gamaka, 1);
        update();
    }

    private void header(int text) {
        TextView t = (TextView) LayoutInflater.from(this).inflate(R.layout.gallery_header, box, false);
        t.setText(text);
        t.setTypeface(Ui.orbitron(this));
        box.addView(t);
    }

    private View option(int title, int sub) {
        View v = LayoutInflater.from(this).inflate(R.layout.option_item, box, false);
        ((TextView) v.findViewById(R.id.opt_title)).setText(title);
        TextView s = v.findViewById(R.id.opt_sub);
        if (sub == 0) s.setVisibility(View.GONE); else s.setText(sub);
        box.addView(v);
        return v;
    }

    private void addView(int title, int sub, int value) {
        View v = option(title, sub);
        v.setOnClickListener(x -> { Prefs.setNpView(this, value); update(); });
        redraw.add(() -> mark(v, Prefs.npView(this) == value));
    }

    private void addStyle(int title, int value) {
        View v = option(title, 0);
        v.setOnClickListener(x -> { Prefs.setVizStyle(this, value); update(); });
        redraw.add(() -> mark(v, Prefs.vizStyle(this) == value));
    }

    private void mark(View v, boolean on) {
        v.setActivated(on);
        ((ImageView) v.findViewById(R.id.opt_check)).setImageResource(on ? R.drawable.ic_check_circle : R.drawable.ic_circle);
    }

    private void update() { for (Runnable r : redraw) r.run(); }

    /** The short line shown under "Now Playing opens with" in Settings. */
    public static String summary(android.content.Context c) {
        int v = Prefs.npView(c);
        if (v == Prefs.VIEW_LYRICS) return c.getString(R.string.np_view_lyrics);
        if (v == Prefs.VIEW_ART) return c.getString(R.string.np_view_art);
        return c.getString(R.string.np_view_viz) + " · "
                + c.getString(Prefs.vizStyle(c) == 1 ? R.string.viz_gamaka : R.string.viz_windscreen);
    }
}
