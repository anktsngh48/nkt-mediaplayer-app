package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Colours, shapes, fonts and small view builders shared by every screen. */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xFF05080F, CARD = 0xFF111827, ACCENT = 0xFF00E5FF, ACCENT2 = 0xFF7B61FF,
            TEXT = 0xFFE8F0FE, MUTED = 0xFF8A9AB2, SUCCESS = 0xFF00E5A0, DANGER = 0xFFFF4F6A,
            BORDER = 0x1F00E5FF, FILL = 0xFF0B1222, CELL = 0xFF0E1628, HANDLE = 0xFF2A3446;

    private static Typeface orb, orbBlack;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void post(Runnable r) { MAIN.post(r); }
    public static void postDelayed(Runnable r, long ms) { MAIN.postDelayed(r, ms); }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static Typeface orb(Context c) {
        if (orb == null) orb = loadOrb(c, 700);
        return orb;
    }

    public static Typeface orbBlack(Context c) {
        if (orbBlack == null) orbBlack = loadOrb(c, 900);
        return orbBlack;
    }

    private static Typeface loadOrb(Context c, int weight) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                return new Typeface.Builder(c.getAssets(), "fonts/orbitron.ttf")
                        .setFontVariationSettings("'wght' " + weight).build();
            }
            return Typeface.create(Typeface.createFromAsset(c.getAssets(), "fonts/orbitron.ttf"), Typeface.BOLD);
        } catch (Throwable t) {
            return Typeface.DEFAULT_BOLD;
        }
    }

    public static GradientDrawable rect(Context c, int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return g;
    }

    public static GradientDrawable oval(Context c, int fill, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return g;
    }

    public static Drawable pressable(Drawable normal, Drawable pressed) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, normal);
        return s;
    }

    public static Drawable rowBg(Context c, boolean nowPlaying) {
        return pressable(rect(c, CARD, 12, nowPlaying ? SUCCESS : BORDER, 1),
                rect(c, 0x1400E5FF, 12, 0x5900E5FF, 1));
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView label(Context c, String s, float sp, int color, float spacing) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(orb(c));
        t.setLetterSpacing(spacing);
        t.setAllCaps(true);
        return t;
    }

    public static TextView single(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static void gradientText(TextView t) {
        t.post(() -> {
            float w = t.getPaint().measureText(t.getText().toString());
            t.getPaint().setShader(new LinearGradient(0, 0, Math.max(1, w), 0, ACCENT, ACCENT2, Shader.TileMode.CLAMP));
            t.invalidate();
        });
    }

    public static ImageButton roundBtn(Context c, String icon, int sizeDp, int tint, boolean border) {
        ImageButton b = new ImageButton(c);
        b.setImageDrawable(new Icon(c, icon, tint));
        int p = dp(c, sizeDp >= 50 ? 14 : 11);
        b.setPadding(p, p, p, p);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setBackground(pressable(border ? oval(c, 0, 0x4000E5FF, 1) : oval(c, 0, 0, 0),
                oval(c, 0x3300E5FF, ACCENT, border ? 1 : 0)));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        b.setContentDescription(icon);
        return b;
    }

    public static ImageButton iconBtn(Context c, String icon, int sizeDp, int tint) {
        return roundBtn(c, icon, sizeDp, tint, false);
    }

    public static TextView pill(Context c, String s, boolean filled, int color) {
        TextView t = label(c, s, 11, filled ? BG : color, 0.18f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 22), 0, dp(c, 22), 0);
        t.setMinHeight(dp(c, 46));
        t.setBackground(filled ? pressable(rect(c, color, 27, 0, 0), rect(c, 0xFF00B8CC, 27, 0, 0))
                : pressable(rect(c, 0, 27, color, 2), rect(c, 0x3300E5FF, 27, color, 2)));
        return t;
    }

    public static TextView chip(Context c, String s, boolean on) {
        TextView t = text(c, s, 13, on ? ACCENT : TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        t.setMinHeight(dp(c, 38));
        t.setBackground(rect(c, on ? 0x2400E5FF : FILL, 20, on ? 0x8C00E5FF : BORDER, 1));
        return t;
    }

    public static void setChip(Context c, TextView t, boolean on) {
        t.setTextColor(on ? ACCENT : TEXT);
        t.setBackground(rect(c, on ? 0x2400E5FF : FILL, 20, on ? 0x8C00E5FF : BORDER, 1));
    }

    public static Switch toggle(Context c, boolean on) {
        Switch s = new Switch(c);
        s.setChecked(on);
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(st, new int[]{ACCENT, MUTED}));
        s.setTrackTintList(new ColorStateList(st, new int[]{0x8C00E5FF, 0xFF26324A}));
        return s;
    }

    public static void styleSeek(SeekBar s) {
        s.setProgressTintList(ColorStateList.valueOf(ACCENT));
        s.setThumbTintList(ColorStateList.valueOf(ACCENT));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(0x4000E5FF));
        s.setSecondaryProgressTintList(ColorStateList.valueOf(0x5500E5FF));
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    public static LinearLayout.LayoutParams lpw(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    public static LinearLayout.LayoutParams lpm(Context c, int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    public static View space(Context c, float w, float h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(w < 0 ? 0 : dp(c, w), dp(c, h)));
        if (w < 0) ((LinearLayout.LayoutParams) v.getLayoutParams()).weight = 1;
        return v;
    }

    public static TextView section(Context c, String s) {
        TextView t = label(c, s, 10, ACCENT, 0.18f);
        t.setPadding(dp(c, 4), dp(c, 18), 0, dp(c, 6));
        return t;
    }

    /** Edge-to-edge window; root pads itself so nothing sits under the status or navigation bar. */
    @SuppressWarnings("deprecation")
    public static void edgeToEdge(Activity a, View root) {
        Window w = a.getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        w.setStatusBarColor(Color.TRANSPARENT);
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = i.top; bottom = i.bottom; left = i.left; right = i.right;
            } else {
                top = ins.getSystemWindowInsetTop(); bottom = ins.getSystemWindowInsetBottom();
                left = ins.getSystemWindowInsetLeft(); right = ins.getSystemWindowInsetRight();
            }
            v.setPadding(l + left, t + top + dp(a, 8), r + right, b + bottom);
            return ins;
        });
        root.requestApplyInsets();
    }

    /** Short in-app message pill (no app icon, unlike system toasts on Android 12+). */
    public static void msg(Context ctx, String s) {
        if (!(ctx instanceof Activity) || ((Activity) ctx).isFinishing()) {
            Toast.makeText(ctx.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
            return;
        }
        Activity a = (Activity) ctx;
        a.runOnUiThread(() -> {
            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            TextView t = text(a, s, 14, TEXT);
            t.setBackground(rect(a, 0xCC05080F, 24, 0x5900E5FF, 1));
            t.setPadding(dp(a, 18), dp(a, 11), dp(a, 18), dp(a, 11));
            t.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            p.bottomMargin = dp(a, 110);
            p.leftMargin = p.rightMargin = dp(a, 24);
            t.setAlpha(0f);
            decor.addView(t, p);
            t.animate().alpha(1f).setDuration(150).start();
            t.postDelayed(() -> t.animate().alpha(0f).setDuration(250).withEndAction(() -> decor.removeView(t)).start(), 2200);
        });
    }

    /** A bottom sheet dialog holding the given content view. */
    public static Dialog sheet(Activity a, View content) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = col(a);
        box.setBackground(a.getResources().getDisplayMetrics() != null ? sheetBg(a) : null);
        box.setPadding(dp(a, 20), dp(a, 10), dp(a, 20), dp(a, 24));
        View handle = new View(a);
        handle.setBackground(rect(a, HANDLE, 2, 0, 0));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(a, 40), dp(a, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(a, 16);
        box.addView(handle, hp);
        ScrollView sv = new ScrollView(a);
        sv.addView(content);
        box.addView(sv, new LinearLayout.LayoutParams(MATCH, WRAP));
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(MATCH, WRAP);
            w.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams p = w.getAttributes();
            p.dimAmount = 0.6f;
            w.setAttributes(p);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return d;
    }

    private static Drawable sheetBg(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        float r = dp(c, 24);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        g.setStroke(dp(c, 1), 0x4D00E5FF);
        return g;
    }

    /** Horizontal scrolling row of chips. */
    public static HorizontalScrollView chipRow(Context c, LinearLayout inner) {
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(inner);
        return h;
    }

    public static String time(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    public static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    /** Keyline row used in lists and settings: title, subtitle, optional trailing view. */
    public static LinearLayout infoRow(Context c, String title, String sub, View trailing) {
        LinearLayout r = row(c);
        r.setBackground(rowBg(c, false));
        r.setPadding(dp(c, 14), dp(c, 10), dp(c, 10), dp(c, 10));
        r.setMinimumHeight(dp(c, 64));
        LinearLayout t = col(c);
        t.addView(text(c, title, 14, TEXT));
        if (sub != null) {
            TextView s = text(c, sub, 12, MUTED);
            s.setPadding(0, dp(c, 3), 0, 0);
            t.addView(s);
        }
        r.addView(t, lpw(0, WRAP, 1));
        if (trailing != null) r.addView(trailing);
        LinearLayout.LayoutParams p = lpm(c, MATCH, WRAP, 0, 6, 0, 0);
        r.setLayoutParams(p);
        return r;
    }
}
