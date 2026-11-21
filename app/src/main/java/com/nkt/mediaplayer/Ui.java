package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.TextView;

import java.util.Locale;

/** Small shared helpers: fonts, time format, full screen. */
public final class Ui {

    private static Typeface bold, black;

    private Ui() { }

    public static Typeface orbitron(Context c) {
        if (bold == null) {
            try { bold = Typeface.createFromAsset(c.getAssets(), "fonts/orbitron_700.ttf"); }
            catch (Exception e) { bold = Typeface.DEFAULT_BOLD; }
        }
        return bold;
    }

    public static Typeface orbitronBlack(Context c) {
        if (black == null) {
            try { black = Typeface.createFromAsset(c.getAssets(), "fonts/orbitron_900.ttf"); }
            catch (Exception e) { black = Typeface.DEFAULT_BOLD; }
        }
        return black;
    }

    /** Cyan → purple lettering for headings. */
    public static void gradient(TextView tv) {
        tv.post(() -> {
            float w = tv.getPaint().measureText(tv.getText().toString());
            tv.getPaint().setShader(new LinearGradient(0, 0, Math.max(w, 1), 0,
                    tv.getContext().getColor(R.color.accent), tv.getContext().getColor(R.color.accent2),
                    Shader.TileMode.CLAMP));
            tv.invalidate();
        });
    }

    public static String time(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                     : String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** File name of something opened from another app. */
    public static String displayName(Context c, Uri uri) {
        if (uri == null) return "Unknown";
        if ("content".equals(uri.getScheme())) {
            try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    String n = cur.getString(0);
                    if (n != null) return stripExt(n);
                }
            } catch (Exception ignored) { }
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "Unknown" : stripExt(last);
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 && n.length() - dot <= 5 ? n.substring(0, dot) : n;
    }

    @SuppressWarnings("deprecation")
    public static void fullScreen(Activity a) {
        if (Build.VERSION.SDK_INT >= 30) {
            a.getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = a.getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            a.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** The status and navigation bars back after {@link #fullScreen}. */
    @SuppressWarnings("deprecation")
    public static void showBars(Activity a) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = a.getWindow().getInsetsController();
            if (c != null) c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } else {
            a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    /**
     * Keeps content clear of the status bar, camera cut-out and navigation bar on every Android version.
     * Android 11+ draws the app edge to edge and pads the root by the bars; older Android keeps apps clear already.
     */
    @SuppressWarnings("deprecation")
    public static void padForSystemBars(View root) {
        if (Build.VERSION.SDK_INT < 30) return;
        Context c = root.getContext();
        if (c instanceof Activity) ((Activity) c).getWindow().setDecorFitsSystemWindows(false);
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(l + i.left, t + i.top, r + i.right, b + Math.max(i.bottom, ime.bottom));
            return insets;
        });
        root.requestApplyInsets();
    }

    /**
     * For full-screen screens (player, viewer, reader): moves a top bar below the status bar and the camera
     * cut-out while the bars are showing, and keeps it clear of the cut-out when they are hidden.
     */
    @SuppressWarnings("deprecation")
    public static void padTopBar(View bar) {
        final int l = bar.getPaddingLeft(), t = bar.getPaddingTop(), r = bar.getPaddingRight(), b = bar.getPaddingBottom();
        bar.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets cut = insets.getInsets(WindowInsets.Type.displayCutout());
                top = i.top; left = cut.left; right = cut.right;
            } else {
                top = insets.getStableInsetTop();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                left = right = 0;
            }
            v.setPadding(l + left, t + top, r + right, b);
            return insets;
        });
        bar.requestApplyInsets();
    }

    /** Bottom controls clear of the navigation bar on edge-to-edge screens. */
    public static void padBottomBar(View bar) {
        if (Build.VERSION.SDK_INT < 30) return;
        final int l = bar.getPaddingLeft(), t = bar.getPaddingTop(), r = bar.getPaddingRight(), b = bar.getPaddingBottom();
        bar.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
            android.graphics.Insets cut = insets.getInsets(WindowInsets.Type.displayCutout());
            v.setPadding(l + Math.max(i.left, cut.left), t, r + Math.max(i.right, cut.right), b + i.bottom);
            return insets;
        });
        bar.requestApplyInsets();
    }

    // ── Messages without the app icon (Android 12+ adds the icon to normal toasts) ──

    private static final Object TOAST_TAG = "nkt_toast";

    public static void toast(Context c, int res) { toast(c, c.getString(res)); }

    public static void toast(Context c, CharSequence text) {
        if (!(c instanceof Activity) || ((Activity) c).isFinishing()) {
            android.widget.Toast.makeText(c.getApplicationContext(), text, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Activity a = (Activity) c;
        android.widget.FrameLayout root = a.findViewById(android.R.id.content);
        if (root == null) return;
        TextView t = root.findViewWithTag(TOAST_TAG);
        float d = a.getResources().getDisplayMetrics().density;
        if (t == null) {
            t = new TextView(a);
            t.setTag(TOAST_TAG);
            t.setBackgroundResource(R.drawable.bg_indicator);
            t.setTextColor(a.getColor(R.color.text));
            t.setTextSize(14);
            t.setGravity(android.view.Gravity.CENTER);
            t.setMaxWidth((int) (320 * d));
            t.setPadding((int) (18 * d), (int) (11 * d), (int) (18 * d), (int) (11 * d));
            t.setElevation(12 * d);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = (int) (110 * d);
            root.addView(t, lp);
        }
        final TextView tv = t;
        tv.setText(text);
        tv.setOnClickListener(null);
        tv.setClickable(false);
        tv.bringToFront();
        tv.animate().cancel();
        tv.setAlpha(0f);
        tv.setVisibility(View.VISIBLE);
        tv.animate().alpha(1f).setDuration(150).start();
        Runnable hide = (Runnable) tv.getTag(R.id.toast_hide);
        if (hide != null) tv.removeCallbacks(hide);
        hide = () -> tv.animate().alpha(0f).setDuration(250).withEndAction(() -> tv.setVisibility(View.GONE)).start();
        tv.setTag(R.id.toast_hide, hide);
        tv.postDelayed(hide, 2200);
    }

    /** A message with one action, such as "Saved to Pictures · OPEN". */
    public static void toast(Activity a, CharSequence text, int action, Runnable onAction) {
        toast(a, text);
        android.widget.FrameLayout root = a.findViewById(android.R.id.content);
        if (root == null) return;
        TextView tv = root.findViewWithTag(TOAST_TAG);
        if (tv == null) return;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(text);
        sb.append("   ");
        int start = sb.length();
        sb.append(a.getString(action));
        sb.setSpan(new android.text.style.ForegroundColorSpan(a.getColor(R.color.accent)), start, sb.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start, sb.length(), 0);
        tv.setText(sb);
        tv.setOnClickListener(v -> { v.setVisibility(View.GONE); onAction.run(); });
        Runnable hide = (Runnable) tv.getTag(R.id.toast_hide);
        if (hide != null) { tv.removeCallbacks(hide); tv.postDelayed(hide, 4000); }
    }

    // ── Messages from background work, shown in whichever screen is open ─────

    private static java.lang.ref.WeakReference<Activity> top = new java.lang.ref.WeakReference<>(null);
    private static boolean tracking = false;

    /** Called once from the first screen; afterwards messages find the screen that's showing. */
    public static void track(android.app.Application app) {
        if (tracking) return;
        tracking = true;
        app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) { top = new java.lang.ref.WeakReference<>(a); }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityCreated(Activity a, android.os.Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    public static void toastApp(Context c, CharSequence text) {
        Activity a = c instanceof Activity ? (Activity) c : top.get();
        if (a != null && !a.isFinishing()) toast(a, text);
        else android.widget.Toast.makeText(c.getApplicationContext(), text, android.widget.Toast.LENGTH_SHORT).show();
    }

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /**
     * The NKT bar used at the top of the Library, for other screens: a back button, "NKT MEDIA PLAYER" in the
     * gradient lettering, and the screen's name as the small line under it.
     */
    public static android.widget.LinearLayout appBar(Activity a, String subheading, Runnable back) {
        android.widget.LinearLayout bar = new android.widget.LinearLayout(a);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(a, 8), 0, dp(a, 6));
        android.widget.ImageButton b = new android.widget.ImageButton(a);
        b.setBackgroundResource(R.drawable.bg_round_btn);
        b.setImageResource(R.drawable.ic_back);
        b.setColorFilter(a.getColor(R.color.accent));
        b.setContentDescription(a.getString(R.string.cd_back));
        b.setOnClickListener(v -> back.run());
        bar.addView(b, new android.widget.LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        android.widget.LinearLayout texts = new android.widget.LinearLayout(a);
        texts.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextView title = new TextView(a);
        title.setText(R.string.header);
        title.setTypeface(orbitronBlack(a));
        title.setTextSize(15);
        title.setLetterSpacing(0.08f);
        title.setMaxLines(1);
        title.setTextColor(a.getColor(R.color.accent));
        texts.addView(title);
        gradient(title);
        TextView sub = new TextView(a);
        sub.setText(subheading);
        sub.setTypeface(orbitron(a));
        sub.setTextSize(9.5f);
        sub.setLetterSpacing(0.16f);
        sub.setMaxLines(1);
        sub.setTextColor(a.getColor(R.color.muted));
        android.widget.LinearLayout.LayoutParams sl = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(a, 2);
        texts.addView(sub, sl);
        android.widget.LinearLayout.LayoutParams tl = new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(a, 14));
        bar.addView(texts, tl);
        return bar;
    }

    /**
     * How far in from the left and right edges a touch belongs to the system's back gesture (gesture navigation),
     * plus a little room, so swipes that start there are left to Android. 0 with button navigation.
     */
    public static int edgeZone(View v) {
        if (v == null || Build.VERSION.SDK_INT < 29) return 0;
        WindowInsets wi = v.getRootWindowInsets();
        if (wi == null) return 0;
        android.graphics.Insets g = wi.getSystemGestureInsets();
        int z = Math.max(g.left, g.right);
        return z <= 0 ? 0 : z + dp(v.getContext(), 8);
    }

    /** True when this touch starts in the back-gesture strip at the left or right edge of the screen. */
    public static boolean inEdgeZone(View v, android.view.MotionEvent e) {
        int z = edgeZone(v);
        if (z <= 0) return false;
        float x = e.getRawX();
        int w = v.getRootView() == null ? v.getWidth() : v.getRootView().getWidth();
        return x < z || x > w - z;
    }
}
