package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * Set as wallpaper: the photo fills the screen the way the wallpaper will; pinch to zoom and drag to place it, then
 * HOME, LOCK or BOTH. A tap hides the buttons to see the picture alone.
 */
public class WallpaperActivity extends Activity {

    private static final String EXTRA_URI = "uri";

    public static void open(Activity a, MediaInfo m) {
        if (m == null || !m.image || m.uri == null) return;
        a.startActivity(new Intent(a, WallpaperActivity.class).putExtra(EXTRA_URI, m.uri.toString()));
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Fitter view;
    private View top, bottom;
    private Bitmap bmp;
    private boolean busy = false;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        Ui.fullScreen(this);

        view = new Fitter(this);
        view.onTap = this::toggleBars;
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // top: back · SET AS WALLPAPER · RESET
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setBackgroundResource(R.drawable.bg_viewer_top);
        ImageButton back = new ImageButton(this);
        back.setBackgroundResource(R.drawable.bg_round_btn);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(getColor(R.color.accent));
        back.setContentDescription(getString(R.string.cd_back));
        back.setOnClickListener(v -> finish());
        t.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = new TextView(this);
        title.setText(R.string.wallpaper_adjust_caps);
        title.setTypeface(Ui.orbitron(this));
        title.setTextSize(13);
        title.setLetterSpacing(0.16f);
        title.setTextColor(getColor(R.color.accent));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(14));
        t.addView(title, tl);
        TextView reset = pill(getString(R.string.wallpaper_reset_caps));
        reset.setOnClickListener(v -> view.fit());
        t.addView(reset, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        top = t;
        root.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        Ui.padTopBar(t);

        // bottom: the hint and where it goes
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(16), dp(14), dp(16), dp(18));
        b.setBackgroundResource(R.drawable.bg_viewer_bottom);
        TextView hint = new TextView(this);
        hint.setText(R.string.wallpaper_adjust_hint);
        hint.setTextColor(0xFFE8F0FE);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        b.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(this);
        int[] labels = {R.string.wallpaper_home_caps, R.string.wallpaper_lock_caps, R.string.wallpaper_both_caps};
        final int[] flags = {WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK};
        for (int i = 0; i < 3; i++) {
            final int f = flags[i];
            TextView go = pill(getString(labels[i]));
            if (i == 2) { go.setTextColor(getColor(R.color.bg)); go.setBackground(round(getColor(R.color.accent), getColor(R.color.accent))); }
            go.setOnClickListener(v -> apply(f));
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, dp(46), 1f);
            if (i > 0) gl.setMarginStart(dp(8));
            row.addView(go, gl);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(12);
        b.addView(row, rl);
        bottom = b;
        root.addView(b, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        Ui.padBottomBar(b);

        load(Uri.parse(getIntent().getStringExtra(EXTRA_URI)));
    }

    private TextView pill(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Ui.orbitron(this));
        t.setTextSize(11);
        t.setLetterSpacing(0.12f);
        t.setTextColor(getColor(R.color.accent));
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setBackground(round(0x99050A14, getColor(R.color.accent)));
        return t;
    }

    private GradientDrawable round(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(23));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private void toggleBars() {
        boolean show = top.getVisibility() != View.VISIBLE;
        top.setVisibility(show ? View.VISIBLE : View.GONE);
        bottom.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** The screen's full size in pixels (what the wallpaper is made at). */
    @SuppressWarnings("deprecation")
    private int[] screen() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private void load(final Uri uri) {
        final int[] sc = screen();
        new Thread(() -> {
            Bitmap b = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = open(uri)) { BitmapFactory.decodeStream(in, null, o); }
                int want = Math.max(sc[0], sc[1]) * 2, sample = 1;
                while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= Math.min(sc[0], sc[1]) && Math.max(o.outWidth, o.outHeight) / (sample * 2) >= want / 2) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                try (InputStream in = open(uri)) { b = BitmapFactory.decodeStream(in, null, o2); }
                if (b != null) b = rotated(uri, b);
            } catch (Throwable ignored) { }
            final Bitmap got = b;
            ui.post(() -> {
                if (isFinishing()) return;
                if (got == null) { Ui.toast(this, R.string.wallpaper_failed); finish(); return; }
                bmp = got;
                view.setBitmap(got);
            });
        }, "nkt-wallpaper-load").start();
    }

    private InputStream open(Uri uri) throws Exception {
        return "file".equals(uri.getScheme()) ? new java.io.FileInputStream(uri.getPath()) : getContentResolver().openInputStream(uri);
    }

    /** Photos taken sideways are turned the way they are meant to be seen. */
    private Bitmap rotated(Uri uri, Bitmap b) {
        try (InputStream in = open(uri)) {
            android.media.ExifInterface e = new android.media.ExifInterface(in);
            int o = e.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            int deg = o == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90 : o == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : o == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (deg == 0) return b;
            Matrix m = new Matrix();
            m.postRotate(deg);
            return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        } catch (Throwable t) { return b; }
    }

    /** Draws what is on screen at the screen's full size and makes it the wallpaper. */
    private void apply(final int which) {
        if (busy || bmp == null || view.getWidth() == 0) return;
        busy = true;
        final int[] sc = screen();
        final Matrix m = new Matrix(view.matrix);
        final float vw = view.getWidth(), vh = view.getHeight();
        final Bitmap src = bmp;
        new Thread(() -> {
            boolean ok;
            try {
                Bitmap out = Bitmap.createBitmap(sc[0], sc[1], Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(out);
                c.drawColor(Color.BLACK);
                c.scale(sc[0] / vw, sc[1] / vh);
                c.drawBitmap(src, m, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
                WallpaperManager.getInstance(this).setBitmap(out, null, true, which);
                ok = true;
            } catch (Throwable t) { ok = false; }
            final boolean done = ok;
            ui.post(() -> {
                busy = false;
                Ui.toastApp(this, getString(done ? R.string.wallpaper_set : R.string.wallpaper_failed));
                if (done) finish();
            });
        }, "nkt-wallpaper-set").start();
    }

    /** The photo always covers the whole screen: pinch to zoom in, drag to move; it never shows an empty edge. */
    static final class Fitter extends View {
        final Matrix matrix = new Matrix();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final ScaleGestureDetector pinch;
        private final android.view.GestureDetector gestures;
        private Bitmap b;
        private float minScale = 1f;
        Runnable onTap;

        Fitter(Context c) {
            super(c);
            pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    float now = scale(), f = d.getScaleFactor();
                    float next = Math.max(minScale, Math.min(minScale * 6f, now * f));
                    f = next / now;
                    matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                    clamp();
                    invalidate();
                    return true;
                }
            });
            gestures = new android.view.GestureDetector(c, new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (onTap != null) onTap.run(); return true; }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    float target = scale() > minScale * 1.5f ? minScale : minScale * 2.5f, f = target / scale();
                    matrix.postScale(f, f, e.getX(), e.getY());
                    clamp();
                    invalidate();
                    return true;
                }
                @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    matrix.postTranslate(-dx, -dy);
                    clamp();
                    invalidate();
                    return true;
                }
            });
        }

        void setBitmap(Bitmap bmp) { b = bmp; fit(); }

        private float scale() { float[] v = new float[9]; matrix.getValues(v); return v[Matrix.MSCALE_X]; }

        /** Fills the screen, centred. */
        void fit() {
            if (b == null || getWidth() == 0) return;
            minScale = Math.max(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight());
            matrix.reset();
            matrix.postScale(minScale, minScale);
            matrix.postTranslate((getWidth() - b.getWidth() * minScale) / 2f, (getHeight() - b.getHeight() * minScale) / 2f);
            invalidate();
        }

        private void clamp() {
            if (b == null) return;
            RectF r = new RectF(0, 0, b.getWidth(), b.getHeight());
            matrix.mapRect(r);
            float dx = 0, dy = 0;
            if (r.left > 0) dx = -r.left; else if (r.right < getWidth()) dx = getWidth() - r.right;
            if (r.top > 0) dy = -r.top; else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
            matrix.postTranslate(dx, dy);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); fit(); }

        @Override protected void onDraw(Canvas c) { if (b != null) c.drawBitmap(b, matrix, paint); }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            pinch.onTouchEvent(e);
            if (!pinch.isInProgress()) gestures.onTouchEvent(e);
            return true;
        }
    }
}
