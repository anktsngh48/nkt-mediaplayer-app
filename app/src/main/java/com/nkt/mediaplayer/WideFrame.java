package com.nkt.mediaplayer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** A 16:9 frame (video grid thumbnails). */
public class WideFrame extends FrameLayout {
    public WideFrame(Context c) { super(c); }
    public WideFrame(Context c, AttributeSet a) { super(c, a); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = View.MeasureSpec.getSize(widthSpec);
        super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(w * 9 / 16, View.MeasureSpec.EXACTLY));
    }
}
