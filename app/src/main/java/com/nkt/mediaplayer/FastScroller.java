package com.nkt.mediaplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Side bar for long lists and grids: appears while scrolling; drag the handle to jump
 * anywhere from top to bottom. A bubble shows where you are (letter or date).
 */
public class FastScroller extends View {

    public interface Labeler { String label(int position); }

    private RecyclerView rv;
    private TextView bubble;
    private Labeler labeler;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;
    private float thumbY = 0, topInset = 0;
    private boolean dragging = false;
    private final Runnable fade = () -> { if (!dragging) animate().alpha(0f).setDuration(300).start(); };

    public FastScroller(Context c) { this(c, null); }

    public FastScroller(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        paint.setColor(c.getColor(R.color.accent));
        track.setColor(0x2200E5FF);
        setAlpha(0f);
    }

    public void attach(RecyclerView list, TextView bubbleView, Labeler l) {
        rv = list;
        bubble = bubbleView;
        labeler = l;
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView v, int dx, int dy) {
                if (dy != 0) reveal();
                if (!dragging) { place(); invalidate(); }
            }
        });
    }

    public void setLabeler(Labeler l) { labeler = l; }

    public void setTopInset(float px) {
        if (Math.abs(px - topInset) < 1) return;
        topInset = px;
        place();
        invalidate();
    }

    private boolean worthIt() {
        return rv != null && rv.computeVerticalScrollRange() > rv.computeVerticalScrollExtent() * 2.5f;
    }

    private void reveal() {
        if (!worthIt()) return;
        removeCallbacks(fade);
        animate().cancel();
        setAlpha(1f);
        postDelayed(fade, 1500);
    }

    private float thumbH() { return 52 * dp; }
    private float trackTop() { return topInset + 6 * dp; }
    private float trackH() { return Math.max(1, getHeight() - trackTop() - 6 * dp); }

    private void place() {
        if (rv == null) return;
        int range = rv.computeVerticalScrollRange(), extent = rv.computeVerticalScrollExtent(), off = rv.computeVerticalScrollOffset();
        float frac = range - extent <= 0 ? 0 : Math.min(1f, off / (float) (range - extent));
        thumbY = trackTop() + frac * (trackH() - thumbH());
    }

    @Override
    protected void onDraw(Canvas c) {
        if (rv == null || !worthIt()) return;
        float x = getWidth() - 10 * dp;
        r.set(x, trackTop(), x + 4 * dp, trackTop() + trackH());
        c.drawRoundRect(r, 2 * dp, 2 * dp, track);
        float w = dragging ? 8 * dp : 6 * dp;
        r.set(x + 2 * dp - w / 2, thumbY, x + 2 * dp + w / 2, thumbY + thumbH());
        c.drawRoundRect(r, w / 2, w / 2, paint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (rv == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getAlpha() < 0.3f || !worthIt()) return false;
                if (e.getY() < thumbY - 24 * dp || e.getY() > thumbY + thumbH() + 24 * dp) return false;
                dragging = true;
                removeCallbacks(fade);
                getParent().requestDisallowInterceptTouchEvent(true);
                rv.stopScroll();
                jump(e.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                jump(e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                if (bubble != null) bubble.setVisibility(GONE);
                postDelayed(fade, 1200);
                invalidate();
                return true;
        }
        return false;
    }

    private void jump(float y) {
        RecyclerView.Adapter<?> a = rv.getAdapter();
        if (a == null || a.getItemCount() == 0) return;
        float frac = (y - trackTop() - thumbH() / 2) / (trackH() - thumbH());
        frac = Math.max(0, Math.min(1, frac));
        thumbY = trackTop() + frac * (trackH() - thumbH());
        int pos = Math.round(frac * (a.getItemCount() - 1));
        if (rv.getLayoutManager() instanceof LinearLayoutManager)
            ((LinearLayoutManager) rv.getLayoutManager()).scrollToPositionWithOffset(pos, 0);
        if (bubble != null && labeler != null) {
            String label = labeler.label(pos);
            if (label == null || label.isEmpty()) bubble.setVisibility(GONE);
            else {
                bubble.setText(label);
                bubble.setVisibility(VISIBLE);
                bubble.setTranslationY(Math.max(0, thumbY + thumbH() / 2 - bubble.getHeight() / 2f));
            }
        }
        invalidate();
    }
}
