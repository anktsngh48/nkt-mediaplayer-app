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

    /** Cyan → purple lettering, like the NKT JustSay header. */
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

    /** Keeps content clear of the status/navigation bars on Android 15 (edge-to-edge by default). */
    public static void padForSystemBars(View root) {
        if (Build.VERSION.SDK_INT < 35) return;       // older Android already keeps apps clear of the bars
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }
}
