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
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;

/** Set as wallpaper: the photo is moved, zoomed and turned inside a frame shaped like this phone's screen, then set. */
public class WallpaperActivity extends Activity {

    public static final String EXTRA_URI = "uri";

    public static void open(Context c, MediaInfo m) {
        if (m == null || m.uri == null) return;
        c.startActivity(new Intent(c, WallpaperActivity.class).putExtra(EXTRA_URI, m.uri.toString()));
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private CropView crop;
    private SeekBar zoom;
    private TextView fillBtn, fitBtn, setBtn;
    private final TextView[] where = new TextView[3];
    private int target = 2;                       // 0 home, 1 lock, 2 both
    private boolean busy = false;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setPadding(dp(20), 0, dp(20), dp(20));
        setContentView(root);
        Ui.padForSystemBars(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(round(R.drawable.ic_back, R.string.cd_back, v -> finish()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text(getString(R.string.wallpaper_title), 13, R.color.accent);
        title.setTypeface(Ui.orbitron(this));
        title.setLetterSpacing(0.2f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(14));
        top.addView(title, tl);
        top.addView(round(R.drawable.ic_refresh, R.string.wallpaper_rotate, v -> crop.rotate()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.topMargin = dp(8);
        root.addView(top, topLp);

        crop = new CropView(this);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cl.topMargin = dp(14);
        root.addView(crop, cl);

        TextView hint = text(getString(R.string.wallpaper_hint), 12, R.color.muted);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(8);
        root.addView(hint, hl);

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(dp(2), dp(2), dp(2), dp(2));
        seg.setBackgroundResource(R.drawable.bg_segment_box);
        fillBtn = segment(getString(R.string.wallpaper_fill), v -> { crop.fill(); paint(); });
        fitBtn = segment(getString(R.string.wallpaper_fit), v -> { crop.fit(); paint(); });
        seg.addView(fillBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        seg.addView(fitBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        tools.addView(seg);
        zoom = new SeekBar(this);
        zoom.setMax(100);
        zoom.setContentDescription(getString(R.string.wallpaper_hint));
        zoom.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        zoom.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) crop.zoomTo(p / 100f); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams zl = new LinearLayout.LayoutParams(0, dp(40), 1f);
        zl.setMarginStart(dp(10));
        tools.addView(zoom, zl);
        LinearLayout.LayoutParams tol = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tol.topMargin = dp(10);
        root.addView(tools, tol);

        LinearLayout chips = new LinearLayout(this);
        int[] names = {R.string.wallpaper_home, R.string.wallpaper_lock, R.string.wallpaper_both};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView c = text(getString(names[i]), 13, R.color.text);
            c.setGravity(Gravity.CENTER);
            c.setSingleLine(true);
            c.setPadding(dp(10), 0, dp(10), 0);
            c.setOnClickListener(v -> { target = k; paint(); });
            where[i] = c;
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, dp(40), i == 2 ? 1.6f : 1f);
            if (i > 0) pl.setMarginStart(dp(6));
            chips.addView(c, pl);
        }
        LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chl.topMargin = dp(12);
        root.addView(chips, chl);

        setBtn = text(getString(R.string.wallpaper_go), 14, R.color.bg);
        setBtn.setGravity(Gravity.CENTER);
        setBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        setBtn.setLetterSpacing(0.16f);
        setBtn.setBackgroundResource(R.drawable.bg_start_btn);
        setBtn.setOnClickListener(v -> apply());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        sl.topMargin = dp(14);
        root.addView(setBtn, sl);
        paint();

        String u = getIntent().getStringExtra(EXTRA_URI);
        if (u == null) { finish(); return; }
        final Uri uri = Uri.parse(u);
        new Thread(() -> {
            Bitmap b = load(uri);
            ui.post(() -> {
                if (isFinishing()) return;
                if (b == null) { Ui.toastApp(this, getString(R.string.wallpaper_failed)); finish(); return; }
                crop.setBitmap(b);
                paint();
            });
        }, "nkt-wallpaper-load").start();
    }

    private void paint() {
        boolean fill = crop.isFill();
        fillBtn.setTextColor(getColor(fill ? R.color.accent : R.color.muted));
        fillBtn.setBackgroundResource(fill ? R.drawable.bg_chip : 0);
        fitBtn.setTextColor(getColor(!fill ? R.color.accent : R.color.muted));
        fitBtn.setBackgroundResource(!fill ? R.drawable.bg_chip : 0);
        for (int i = 0; i < 3; i++) {
            where[i].setBackgroundResource(R.drawable.bg_chip);
            where[i].setSelected(i == target);
        }
        if (zoom != null) zoom.setProgress(Math.round(crop.zoomLevel() * 100));
    }

    private TextView segment(String s, View.OnClickListener l) {
        TextView t = text(s, 9.5f, R.color.muted);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setOnClickListener(l);
        return t;
    }

    private ImageButton round(int icon, int desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setBackgroundResource(R.drawable.bg_round_btn);
        b.setImageResource(icon);
        b.setColorFilter(getColor(R.color.accent));
        b.setContentDescription(getString(desc));
        b.setOnClickListener(l);
        return b;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        return t;
    }

    /** The photo, made small enough to handle (about twice the screen), the right way up. Background thread. */
    private Bitmap load(Uri uri) {
        DisplayMetrics dm = screen();
        int want = Math.max(dm.widthPixels, dm.heightPixels) * 2;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                android.graphics.ImageDecoder.Source src = "file".equals(uri.getScheme()) && uri.getPath() != null
                        ? android.graphics.ImageDecoder.createSource(new java.io.File(uri.getPath()))
                        : android.graphics.ImageDecoder.createSource(getContentResolver(), uri);
                return android.graphics.ImageDecoder.decodeBitmap(src, (dec, info, s) -> {
                    int big = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    int sample = 1;
                    while (big / (sample * 2) >= want) sample *= 2;
                    dec.setTargetSampleSize(sample);
                    dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = open(uri)) { BitmapFactory.decodeStream(in, null, o); }
            int big = Math.max(o.outWidth, o.outHeight), sample = 1;
            while (big / (sample * 2) >= want) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            try (InputStream in = open(uri)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (Throwable t) {
            return null;
        }
    }

    private InputStream open(Uri uri) throws java.io.IOException {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new java.io.FileInputStream(uri.getPath());
        return getContentResolver().openInputStream(uri);
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics screen() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    private void apply() {
        if (busy || !crop.ready()) return;
        busy = true;
        setBtn.setAlpha(0.5f);
        Ui.toastApp(this, getString(R.string.wallpaper_setting));
        DisplayMetrics dm = screen();
        final Bitmap out = crop.render(Math.min(dm.widthPixels, dm.heightPixels), Math.max(dm.widthPixels, dm.heightPixels));
        final int which = target == 0 ? WallpaperManager.FLAG_SYSTEM : target == 1 ? WallpaperManager.FLAG_LOCK
                : WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
        new Thread(() -> {
            boolean ok;
            try {
                WallpaperManager.getInstance(this).setBitmap(out, null, true, which);
                ok = true;
            } catch (Throwable t) { ok = false; }
            final boolean done = ok;
            ui.post(() -> {
                Ui.toastApp(getApplicationContext(), getString(done ? R.string.wallpaper_set : R.string.wallpaper_failed));
                busy = false;
                setBtn.setAlpha(1f);
                if (done) finish();
            });
        }, "nkt-wallpaper-set").start();
    }

    /** The photo under a frame the shape of the screen: drag to move, pinch to zoom. */
    private final class CropView extends View {
        private Bitmap bmp;
        private final Matrix m = new Matrix();
        private final RectF frame = new RectF();
        private final Paint pic = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path hole = new Path();
        private final ScaleGestureDetector pinch;
        private float lastX, lastY, minScale = 1f, scale = 1f;
        private boolean fill = true, dragging = false;

        CropView(Context c) {
            super(c);
            dim.setColor(0x9905080F);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            line.setColor(getColor(R.color.accent));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.BLACK);
            bg.setCornerRadius(dp(16));
            setBackground(bg);
            setClipToOutline(true);
            pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    WallpaperActivity.this.zoom.setProgress(Math.round(zoomLevel() * 100));
                    return true;
                }
            });
        }

        boolean ready() { return bmp != null && frame.width() > 0; }
        boolean isFill() { return fill; }

        void setBitmap(Bitmap b) { bmp = b; place(); }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { place(); }

        private void place() {
            if (getWidth() == 0) return;
            DisplayMetrics dm = screen();
            float ratio = (float) Math.min(dm.widthPixels, dm.heightPixels) / Math.max(dm.widthPixels, dm.heightPixels);
            float pad = dp(20), aw = getWidth() - 2 * pad, ah = getHeight() - 2 * pad;
            float fw = Math.min(aw, ah * ratio), fh = fw / ratio;
            frame.set((getWidth() - fw) / 2f, (getHeight() - fh) / 2f, (getWidth() + fw) / 2f, (getHeight() + fh) / 2f);
            hole.reset();
            hole.addRoundRect(frame, dp(22), dp(22), Path.Direction.CW);
            if (fill) fill(); else fit();
        }

        private float coverScale() { return Math.max(frame.width() / bmp.getWidth(), frame.height() / bmp.getHeight()); }
        private float containScale() { return Math.min(frame.width() / bmp.getWidth(), frame.height() / bmp.getHeight()); }

        void fill() { if (bmp == null || frame.width() == 0) return; fill = true; minScale = coverScale(); setScale(minScale); }
        void fit() { if (bmp == null || frame.width() == 0) return; fill = false; minScale = containScale(); setScale(minScale); }

        private void setScale(float s) {
            scale = s;
            m.reset();
            m.postScale(s, s);
            m.postTranslate(frame.centerX() - bmp.getWidth() * s / 2f, frame.centerY() - bmp.getHeight() * s / 2f);
            invalidate();
        }

        /** 0 = smallest (as FILL or FIT left it), 1 = four times bigger. */
        float zoomLevel() { return bmp == null ? 0 : Math.max(0, Math.min(1, (scale / minScale - 1f) / 3f)); }

        void zoomTo(float level) {
            if (bmp == null) return;
            float target = minScale * (1f + 3f * level);
            zoomBy(target / scale, frame.centerX(), frame.centerY());
        }

        private void zoomBy(float f, float fx, float fy) {
            if (bmp == null) return;
            float next = Math.max(minScale, Math.min(minScale * 4f, scale * f));
            f = next / scale;
            scale = next;
            m.postScale(f, f, fx, fy);
            keepCovered();
            invalidate();
        }

        void rotate() {
            if (bmp == null) return;
            Matrix r = new Matrix();
            r.postRotate(90);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), r, true);
            place();
            paint();
        }

        /** In FILL the photo always covers the frame; in FIT it stays inside the frame's middle. */
        private void keepCovered() {
            RectF r = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
            m.mapRect(r);
            float dx = 0, dy = 0;
            if (r.width() >= frame.width()) {
                if (r.left > frame.left) dx = frame.left - r.left;
                else if (r.right < frame.right) dx = frame.right - r.right;
            } else dx = frame.centerX() - r.centerX();
            if (r.height() >= frame.height()) {
                if (r.top > frame.top) dy = frame.top - r.top;
                else if (r.bottom < frame.bottom) dy = frame.bottom - r.bottom;
            } else dy = frame.centerY() - r.centerY();
            m.postTranslate(dx, dy);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (bmp == null) return true;
            pinch.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX(); lastY = e.getY(); dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN: dragging = false; break;
                case MotionEvent.ACTION_MOVE:
                    if (dragging && !pinch.isInProgress() && e.getPointerCount() == 1) {
                        m.postTranslate(e.getX() - lastX, e.getY() - lastY);
                        keepCovered();
                        invalidate();
                    }
                    lastX = e.getX(); lastY = e.getY();
                    break;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: dragging = false; break;
            }
            return true;
        }

        @SuppressWarnings("deprecation")
        @Override
        protected void onDraw(Canvas c) {
            if (bmp == null) return;
            c.drawBitmap(bmp, m, pic);
            int save = c.save();
            if (Build.VERSION.SDK_INT >= 26) c.clipOutPath(hole);
            else c.clipPath(hole, android.graphics.Region.Op.DIFFERENCE);
            c.drawRect(0, 0, getWidth(), getHeight(), dim);
            c.restoreToCount(save);
            c.drawRoundRect(frame, dp(22), dp(22), line);
        }

        /** What the frame shows, at the screen's size (black where FIT leaves room). */
        Bitmap render(int w, int h) {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(Color.BLACK);
            float k = w / frame.width();
            c.scale(k, k);
            c.translate(-frame.left, -frame.top);
            c.drawBitmap(bmp, m, pic);
            return out;
        }
    }
}
