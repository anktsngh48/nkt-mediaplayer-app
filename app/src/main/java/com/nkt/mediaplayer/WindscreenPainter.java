package com.nkt.mediaplayer;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * The night-drive windscreen picture, drawn in the design's own units (348 wide, 298 high). Used live by the
 * visualiser and as a still picture on the home-screen widgets.
 */
final class WindscreenPainter {

    private static final int ACC = 0xFF00E5FF;
    private static final float H0 = 110f, KK = 376f, XC = 174f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path clip = new Path();

    /**
     * @param L,R   the left and right edge to fill, in design units (0..348 is the usual view)
     * @param off   how far the car has driven (lamp posts and lane dashes)
     * @param pulse 0..1: how strongly the last beat is felt
     */
    void paint(Canvas cv, float L, float R, float off, float pulse) {
        cv.save();
        // sky and ground
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 0, 0, 298, new int[]{0xFF05080F, 0xFF121C48, 0xFF2C3F82}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(L, 0, R, 298, p);
        p.setShader(null);
        p.setColor(0xFF0A1020);
        cv.drawRect(L, H0, R, 298, p);

        // glow on the horizon
        cv.save();
        cv.clipRect(L, 76, R, 120);
        cv.translate(174, 120);
        cv.scale(1f, 44f / 209f);
        p.setShader(new RadialGradient(0, 0, 209f, new int[]{0x8C00E5FF, 0x0000E5FF}, null, Shader.TileMode.CLAMP));
        p.setAlpha(Math.round(255 * (0.35f + 0.55f * pulse)));
        cv.drawCircle(0, 0, 209f, p);
        p.setShader(null);
        p.setAlpha(255);
        cv.restore();

        // the road, bobbing a little on every beat
        cv.save();
        cv.translate(0, 2.5f * pulse);
        cv.save();
        clip.reset();
        clip.moveTo(L - 20, 0);
        clip.lineTo(R + 20, 0);
        clip.lineTo(R + 20, 258);
        clip.quadTo(174, 230, L - 20, 258);
        clip.close();
        cv.clipPath(clip);

        path.reset();                                           // far buildings
        path.moveTo(L, 110); path.lineTo(L, 96);
        float[][] sk = {{22, 96}, {22, 84}, {44, 84}, {44, 100}, {60, 100}, {60, 90}, {88, 90}, {88, 102}, {112, 102}, {112, 92}, {138, 92}, {138, 104},
                {210, 104}, {210, 94}, {236, 94}, {236, 86}, {262, 86}, {262, 98}, {290, 98}, {290, 88}, {316, 88}, {316, 100}, {348, 100}};
        for (float[] q : sk) path.lineTo(q[0], q[1]);
        path.lineTo(R, 100); path.lineTo(R, 110); path.close();
        p.setColor(0x1A00E5FF);
        cv.drawPath(path, p);

        path.reset();                                           // road surface
        path.moveTo(174, 110); path.lineTo(-42, 298); path.lineTo(390, 298); path.close();
        p.setColor(0xFF111A2E);
        cv.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(0x8C00E5FF);
        cv.drawLine(174, 110, -42, 298, p);
        cv.drawLine(174, 110, 390, 298, p);

        // lamp posts
        p.setStrokeCap(Paint.Cap.ROUND);
        Path posts = new Path(), heads = new Path();
        float po = off % 5f;
        for (float side : new float[]{-1.8f, 1.8f}) {
            for (int j = 0; j < 9; j++) {
                float D = 3 + j * 5 - po;
                if (D < 2.6f) continue;
                float g = H0 + KK / D, x = XC + side * KK / D, top = g - 1.4f * KK / D;
                posts.moveTo(x, g); posts.lineTo(x, top);
                heads.moveTo(x, top); heads.lineTo(x, top + 0.1f);
            }
        }
        p.setStrokeWidth(1.5f);
        p.setColor(0xFF4A5878);
        cv.drawPath(posts, p);
        p.setStrokeWidth(11f);
        p.setColor(0x3300E5FF);
        cv.drawPath(heads, p);
        p.setStrokeWidth(5f);
        p.setColor(ACC);
        cv.drawPath(heads, p);

        // lane dashes
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setColor(0xFFE8F0FE);
        path.reset();
        float dOff = off % 2f;
        for (float lat : new float[]{-0.38f, 0.38f}) {
            for (int i = 0; i < 30; i++) {
                float ds = 2 + i * 2 - dOff, de = ds + 1;
                if (de < 1.5f) continue;
                if (ds < 1.5f) ds = 1.5f;
                float yn = H0 + KK / ds, yf = H0 + KK / de, w = 0.035f;
                path.moveTo(XC + (lat - w) * KK / ds, yn);
                path.lineTo(XC + (lat + w) * KK / ds, yn);
                path.lineTo(XC + (lat + w) * KK / de, yf);
                path.lineTo(XC + (lat - w) * KK / de, yf);
                path.close();
            }
        }
        cv.drawPath(path, p);
        cv.restore();                                           // end of the dashboard clip

        // headlight pool on the road
        cv.save();
        cv.clipRect(64, 168, 284, 252);
        cv.translate(174, 252);
        cv.scale(1f, 119f / 155f);
        p.setShader(new RadialGradient(0, 0, 155f, new int[]{0x99FFF7C2, 0x99FFF7C2, 0x00FFF7C2}, new float[]{0f, 0.01f, 0.66f}, Shader.TileMode.CLAMP));
        p.setAlpha(Math.round(255 * (0.25f + 0.65f * pulse)));
        cv.drawCircle(0, 0, 155f, p);
        p.setShader(null);
        p.setAlpha(255);
        cv.restore();
        cv.restore();                                           // end of bob

        // dashboard and steering wheel (wheel on the right)
        path.reset();
        path.moveTo(L, 252); path.quadTo(174, 226, R, 252); path.lineTo(R, 298); path.lineTo(L, 298); path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B1222);
        cv.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(0x4000E5FF);
        cv.drawPath(path, p);

        p.setStrokeWidth(16f);
        p.setColor(0xFF111827);
        cv.drawCircle(262, 338, 84, p);
        p.setStrokeWidth(1.5f);
        p.setColor(0x5900E5FF);
        cv.drawCircle(262, 338, 84, p);
        p.setStrokeWidth(1f);
        p.setColor(0x2600E5FF);
        cv.drawCircle(262, 338, 92, p);
        cv.restore();
        p.setStyle(Paint.Style.FILL);
    }
}
