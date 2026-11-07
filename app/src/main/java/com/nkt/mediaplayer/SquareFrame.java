package com.nkt.mediaplayer;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** A frame that is always as tall as it is wide (gallery tiles, music squares). */
public class SquareFrame extends FrameLayout {
    public SquareFrame(Context c) { super(c); }
    public SquareFrame(Context c, AttributeSet a) { super(c, a); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, widthSpec);
    }
}
