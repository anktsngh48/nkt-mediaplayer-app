package com.nkt.mediaplayer;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

import androidx.annotation.NonNull;

/** Image view with pinch zoom (up to 5×), double-tap 2.5×, and panning while zoomed. */
public class ZoomImageView extends ImageView {
    private final Matrix base = new Matrix(), m = new Matrix();
    private float scale = 1f;
    private final ScaleGestureDetector sgd;
    private final GestureDetector gd;
    private float lastX, lastY;
    public Runnable onTap;

    public ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(@NonNull ScaleGestureDetector d) {
                float f = d.getScaleFactor(), ns = Math.max(1f, Math.min(5f, scale * f));
                f = ns / scale;
                scale = ns;
                m.postScale(f, f, d.getFocusX(), d.getFocusY());
                fix();
                return true;
            }
        });
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (scale > 1.01f) reset(); else { scale = 2.5f; m.postScale(2.5f, 2.5f, e.getX(), e.getY()); fix(); }
                return true;
            }
            @Override public boolean onSingleTapConfirmed(@NonNull MotionEvent e) { if (onTap != null) onTap.run(); return true; }
        });
    }

    public boolean zoomed() { return scale > 1.01f; }

    @Override public void setImageDrawable(Drawable d) { super.setImageDrawable(d); post(this::fitBase); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); fitBase(); }

    private void fitBase() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) { setScaleType(ScaleType.CENTER_INSIDE); return; }
        setScaleType(ScaleType.MATRIX);
        float s = Math.min(getWidth() / dw, getHeight() / dh);
        base.reset();
        base.postScale(s, s);
        base.postTranslate((getWidth() - dw * s) / 2f, (getHeight() - dh * s) / 2f);
        reset();
    }

    public void reset() { scale = 1f; m.reset(); apply(); }

    private void apply() {
        Matrix t = new Matrix(base);
        t.postConcat(m);
        setImageMatrix(t);
    }

    private void fix() {
        Drawable d = getDrawable();
        if (d == null) return;
        Matrix t = new Matrix(base);
        t.postConcat(m);
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        t.mapRect(r);
        float dx = 0, dy = 0;
        if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        m.postTranslate(dx, dy);
        apply();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        sgd.onTouchEvent(e);
        gd.onTouchEvent(e);
        if (e.getPointerCount() > 1 || zoomed()) getParent().requestDisallowInterceptTouchEvent(true);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lastX = e.getX(); lastY = e.getY(); break;
            case MotionEvent.ACTION_MOVE:
                if (zoomed() && !sgd.isInProgress() && e.getPointerCount() == 1) {
                    m.postTranslate(e.getX() - lastX, e.getY() - lastY);
                    fix();
                }
                lastX = e.getX(); lastY = e.getY();
                break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                if (!zoomed()) getParent().requestDisallowInterceptTouchEvent(false);
                break;
        }
        return true;
    }
}
