package com.nkt.mediaplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Photo view with pinch-to-zoom, double-tap zoom and panning (Google Photos style). */
public class ZoomImageView extends ImageView {

    private static final float MAX = 5f, DOUBLE_TAP = 2.5f;

    private final Matrix base = new Matrix(), user = new Matrix(), draw = new Matrix();
    private final float[] vals = new float[9];
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private Runnable onTap;

    public ZoomImageView(Context c) { this(c, null); }

    public ZoomImageView(Context c, AttributeSet a) {
        super(c, a);
        setScaleType(ScaleType.MATRIX);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float s = scale(), f = d.getScaleFactor();
                if (s * f > MAX) f = MAX / s;
                if (s * f < 1f) f = 1f / s;
                user.postScale(f, f, d.getFocusX(), d.getFocusY());
                fix();
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (onTap != null) onTap.run(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (scale() > 1.05f) user.reset();
                else user.postScale(DOUBLE_TAP, DOUBLE_TAP, e.getX(), e.getY());
                fix();
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale() <= 1.01f) return false;
                user.postTranslate(-dx, -dy);
                fix();
                return true;
            }
        });
    }

    public void setOnTap(Runnable r) { onTap = r; }

    public boolean isZoomed() { return scale() > 1.01f; }

    public void resetZoom() { user.reset(); fix(); }

    private float scale() { user.getValues(vals); return vals[Matrix.MSCALE_X]; }

    @Override
    public void setImageDrawable(Drawable d) {
        super.setImageDrawable(d);
        if (user == null) return;          // called by the base class before this one is set up
        user.reset();
        fitBase();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fitBase();
    }

    private void fitBase() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) return;
        base.setRectToRect(new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight()),
                new RectF(0, 0, getWidth(), getHeight()), Matrix.ScaleToFit.CENTER);
        fix();
    }

    /** Keep the zoomed photo inside the screen edges. */
    private void fix() {
        Drawable d = getDrawable();
        if (d == null) return;
        draw.set(base);
        draw.postConcat(user);
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        draw.mapRect(r);
        float dx = 0, dy = 0, w = getWidth(), h = getHeight();
        if (r.width() <= w) dx = (w - r.width()) / 2 - r.left; else if (r.left > 0) dx = -r.left; else if (r.right < w) dx = w - r.right;
        if (r.height() <= h) dy = (h - r.height()) / 2 - r.top; else if (r.top > 0) dy = -r.top; else if (r.bottom < h) dy = h - r.bottom;
        user.postTranslate(dx, dy);
        draw.set(base);
        draw.postConcat(user);
        setImageMatrix(draw);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaler.onTouchEvent(ev);
        gestures.onTouchEvent(ev);
        // while zoomed (or pinching), keep the swipe-to-next-photo list from taking the touch
        if (getParent() != null)
            getParent().requestDisallowInterceptTouchEvent(isZoomed() || ev.getPointerCount() > 1);
        return true;
    }
}
