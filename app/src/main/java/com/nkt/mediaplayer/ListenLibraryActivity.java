package com.nkt.mediaplayer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Listen: the host's shared library. Tapping a song or video plays it here, on this phone only (not in step with the
 * host); BACK TO LIVE on the Listen screen goes back to following the host.
 */
public class ListenLibraryActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String[]> items = new ArrayList<>();     // id, title, artist, seconds, video
    private LinearLayout list;
    private TextView message;
    private EditText search;
    private final TextView[] kindPill = new TextView[2];
    private int kind = 0;          // 0 songs, 1 videos
    private boolean loaded = false;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setPadding(dp(20), 0, dp(20), 0);
        setContentView(root);
        Ui.padForSystemBars(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setBackgroundResource(R.drawable.bg_round_btn);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(getColor(R.color.accent));
        back.setContentDescription(getString(R.string.cd_back));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text(getString(R.string.listen_library_title), 16, R.color.text, true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tl.setMarginStart(dp(14));
        top.addView(title, tl);
        root.addView(top, new LinearLayout.LayoutParams(MATCH, dp(64)));

        search = new EditText(this);
        search.setHint(R.string.listen_library_search);
        search.setHintTextColor(getColor(R.color.muted));
        search.setTextColor(getColor(R.color.text));
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(stroked(0xFF111827, 0x1F00E5FF, 14));
        search.setPadding(dp(16), 0, dp(16), 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { draw(); }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, dp(48));
        sl.topMargin = dp(4);
        root.addView(search, sl);

        LinearLayout pills = new LinearLayout(this);
        String[] names = {getString(R.string.listen_library_songs), getString(R.string.listen_library_videos)};
        for (int i = 0; i < 2; i++) {
            final int k = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(11);
            t.setLetterSpacing(0.08f);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setOnClickListener(v -> { kind = k; paintPills(); draw(); });
            kindPill[i] = t;
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (i > 0) pl.setMarginStart(dp(6));
            pills.addView(t, pl);
        }
        LinearLayout.LayoutParams pll = new LinearLayout.LayoutParams(MATCH, WRAP);
        pll.topMargin = dp(12);
        root.addView(pills, pll);
        paintPills();

        message = text(getString(R.string.listen_library_loading), 13, R.color.muted, false);
        message.setPadding(0, dp(18), 0, 0);
        root.addView(message, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new ViewGroup.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        svl.topMargin = dp(6);
        root.addView(sv, svl);

        load();
    }

    private void load() {
        new Thread(() -> {
            String body = ListenService.fetch("/api/library", 20000);
            final List<String[]> got = new ArrayList<>();
            boolean ok = false;
            try {
                if (body != null) {
                    JSONArray a = new JSONObject(body).optJSONArray("items");
                    for (int i = 0; a != null && i < a.length(); i++) {
                        JSONArray x = a.getJSONArray(i);
                        got.add(new String[]{x.getString(0), x.getString(1), x.optString(2), String.valueOf(x.optLong(3)), String.valueOf(x.optInt(4))});
                    }
                    ok = true;
                }
            } catch (Throwable ignored) { }
            final boolean fine = ok;
            ui.post(() -> {
                if (isFinishing()) return;
                loaded = true;
                items.clear();
                items.addAll(got);
                message.setText(fine ? "" : getString(R.string.listen_library_off));
                message.setVisibility(fine ? View.GONE : View.VISIBLE);
                draw();
            });
        }, "nkt-listen-lib").start();
    }

    private void draw() {
        list.removeAllViews();
        if (!loaded) return;
        String term = search.getText().toString().trim().toLowerCase();
        int shown = 0;
        for (String[] it : items) {
            boolean video = "1".equals(it[4]);
            if (video != (kind == 1)) continue;
            if (!term.isEmpty() && !(it[1] + " " + it[2]).toLowerCase().contains(term)) continue;
            if (++shown > 400) break;
            list.addView(row(it));
        }
        if (shown == 0 && message.getVisibility() != View.VISIBLE) {
            TextView none = text(getString(R.string.listen_library_empty), 13, R.color.muted, false);
            none.setPadding(0, dp(18), 0, 0);
            list.addView(none);
        }
    }

    private View row(String[] it) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(14), dp(10), dp(14), dp(10));
        r.setBackground(stroked(0xFF111827, 0x1400E5FF, 14));
        TextView t = text(it[1], 14, R.color.text, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t);
        long sec = 0;
        try { sec = Long.parseLong(it[3]); } catch (Exception ignored) { }
        String sub = (it[2].isEmpty() ? "" : it[2] + " · ") + Ui.time(sec * 1000);
        TextView s = text(sub, 12, R.color.muted, false);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(s);
        r.setOnClickListener(v -> {
            ListenService.playOwn(this, it[0], it[1], "1".equals(it[4]));
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(6);
        r.setLayoutParams(lp);
        return r;
    }

    private void paintPills() {
        for (int i = 0; i < 2; i++) {
            boolean on = i == kind;
            kindPill[i].setTextColor(on ? getColor(R.color.accent) : getColor(R.color.muted));
            kindPill[i].setBackground(on ? stroked(0x1A00E5FF, getColor(R.color.accent), 20) : stroked(0xFF111827, 0x1F00E5FF, 20));
        }
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private android.graphics.drawable.GradientDrawable stroked(int fill, int stroke, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ListenService.running) finish();          // listening ended meanwhile
    }
}
