package com.nkt.mediaplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Line icons drawn in code on a 24×24 grid, so no icon files are needed. */
public class Icon extends Drawable {
    private final String name;
    private final Paint s = new Paint(Paint.ANTI_ALIAS_FLAG), f = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;

    public Icon(Context c, String name, int color) {
        this.name = name;
        size = Ui.dp(c, 24);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeCap(Paint.Cap.ROUND);
        s.setStrokeJoin(Paint.Join.ROUND);
        s.setStrokeWidth(2f);
        s.setColor(color);
        f.setStyle(Paint.Style.FILL);
        f.setColor(color);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int a) { s.setAlpha(a); f.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { s.setColorFilter(cf); f.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    public void setColor(int c) { s.setColor(c); f.setColor(c); invalidateSelf(); }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        float sc = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.left + (b.width() - 24 * sc) / 2f, b.top + (b.height() - 24 * sc) / 2f);
        c.scale(sc, sc);
        Path p = new Path();
        switch (name) {
            case "play": tri(p, 7, 4, 7, 20, 20, 12); c.drawPath(p, f); break;
            case "pause": c.drawRoundRect(new RectF(6, 4, 10, 20), 1, 1, f); c.drawRoundRect(new RectF(14, 4, 18, 20), 1, 1, f); break;
            case "next": tri(p, 5, 5, 5, 19, 15, 12); c.drawPath(p, f); c.drawRoundRect(new RectF(16.6f, 5, 19, 19), 1, 1, f); break;
            case "prev": tri(p, 19, 5, 19, 19, 9, 12); c.drawPath(p, f); c.drawRoundRect(new RectF(5, 5, 7.4f, 19), 1, 1, f); break;
            case "stop": c.drawRoundRect(new RectF(6, 6, 18, 18), 2, 2, f); break;
            case "close": line(c, 6, 6, 18, 18); line(c, 18, 6, 6, 18); break;
            case "back": poly(c, false, 15, 18, 9, 12, 15, 6); break;
            case "forward": poly(c, false, 9, 6, 15, 12, 9, 18); break;
            case "up": poly(c, false, 6, 15, 12, 9, 18, 15); break;
            case "down": poly(c, false, 6, 9, 12, 15, 18, 9); break;
            case "menu": line(c, 4, 7, 20, 7); line(c, 4, 12, 20, 12); line(c, 4, 17, 14, 17); break;
            case "more": c.drawCircle(12, 5, 1.8f, f); c.drawCircle(12, 12, 1.8f, f); c.drawCircle(12, 19, 1.8f, f); break;
            case "heart": case "heart_filled":
                p.moveTo(12, 21); p.cubicTo(12, 21, 4.5f, 16.4f, 2.5f, 11.7f); p.cubicTo(1.2f, 8.4f, 3.3f, 5, 6.8f, 5);
                p.cubicTo(8.8f, 5, 10.4f, 6.1f, 12, 8); p.cubicTo(13.6f, 6.1f, 15.2f, 5, 17.2f, 5);
                p.cubicTo(20.7f, 5, 22.8f, 8.4f, 21.5f, 11.7f); p.cubicTo(19.5f, 16.4f, 12, 21, 12, 21); p.close();
                c.drawPath(p, name.equals("heart") ? s : f); if (!name.equals("heart")) c.drawPath(p, s); break;
            case "search": c.drawCircle(11, 11, 7, s); line(c, 20, 20, 16.5f, 16.5f); break;
            case "sort": line(c, 3, 6, 21, 6); line(c, 6, 12, 18, 12); line(c, 10, 18, 14, 18); break;
            case "list": line(c, 9, 6, 21, 6); line(c, 9, 12, 21, 12); line(c, 9, 18, 21, 18);
                c.drawCircle(4, 6, 1.2f, f); c.drawCircle(4, 12, 1.2f, f); c.drawCircle(4, 18, 1.2f, f); break;
            case "grid": rr(c, 3, 3, 10, 10); rr(c, 14, 3, 21, 10); rr(c, 3, 14, 10, 21); rr(c, 14, 14, 21, 21); break;
            case "squares": poly(c, true, 12, 3, 21, 8, 12, 13, 3, 8); poly(c, false, 3, 13, 12, 18, 21, 13); break;
            case "share": c.drawCircle(18, 5, 3, s); c.drawCircle(6, 12, 3, s); c.drawCircle(18, 19, 3, s);
                line(c, 8.6f, 13.5f, 15.4f, 17.5f); line(c, 15.4f, 6.5f, 8.6f, 10.5f); break;
            case "delete": line(c, 3, 6, 21, 6); poly(c, false, 8, 6, 8, 4, 16, 4, 16, 6); poly(c, false, 6, 6, 7, 21, 17, 21, 18, 6); break;
            case "folder": p.moveTo(3, 6); p.lineTo(3, 18); p.quadTo(3, 20, 5, 20); p.lineTo(19, 20); p.quadTo(21, 20, 21, 18);
                p.lineTo(21, 8); p.quadTo(21, 6, 19, 6); p.lineTo(11, 6); p.lineTo(9, 4); p.lineTo(5, 4); p.quadTo(3, 4, 3, 6); c.drawPath(p, s); break;
            case "music": poly(c, false, 9, 18, 9, 5, 21, 3, 21, 16); c.drawCircle(6, 18, 3, s); c.drawCircle(18, 16, 3, s); break;
            case "video": rr(c, 2, 5, 17, 19); poly(c, true, 17, 10, 22, 7, 22, 17, 17, 14); break;
            case "image": rr(c, 3, 3, 21, 21); c.drawCircle(9, 9, 2, s); poly(c, false, 21, 15, 16, 10, 5, 21); break;
            case "file": poly(c, true, 14, 2, 6, 2, 4, 4, 4, 20, 6, 22, 18, 22, 20, 20, 20, 8); poly(c, false, 14, 2, 14, 8, 20, 8); break;
            case "wifi": arc(c, 12, 20, 10, 225, 90); arc(c, 12, 20, 6.5f, 225, 90); arc(c, 12, 20, 3, 225, 90); c.drawCircle(12, 20, 1.2f, f); break;
            case "pc": rr(c, 2, 3, 22, 17); line(c, 8, 21, 16, 21); line(c, 12, 17, 12, 21); break;
            case "devices": rr(c, 2, 4, 14, 13); rr(c, 15, 8, 22, 20); line(c, 5, 17, 11, 17); line(c, 8, 13, 8, 17); break;
            case "link": c.save(); c.rotate(-45, 12, 12); c.drawRoundRect(new RectF(3, 9, 12.5f, 15), 3, 3, s);
                c.drawRoundRect(new RectF(11.5f, 9, 21, 15), 3, 3, s); c.restore(); break;
            case "shuffle": poly(c, false, 16, 3, 21, 3, 21, 8); line(c, 4, 20, 21, 3); poly(c, false, 21, 16, 21, 21, 16, 21);
                line(c, 15, 15, 21, 21); line(c, 4, 4, 9, 9); break;
            case "repeat": case "repeat_one": poly(c, false, 17, 2, 21, 6, 17, 10); poly(c, false, 3, 11, 3, 9, 6, 6, 21, 6);
                poly(c, false, 7, 22, 3, 18, 7, 14); poly(c, false, 21, 13, 21, 15, 18, 18, 3, 18);
                if (name.equals("repeat_one")) { f.setTextSize(8); f.setFakeBoldText(true); c.drawText("1", 10, 15, f); } break;
            case "timer": c.drawCircle(12, 13, 8, s); poly(c, false, 12, 9, 12, 13, 14, 15); line(c, 9, 2, 15, 2); break;
            case "speed": arc(c, 12, 14, 9, 150, 240); line(c, 12, 14, 16, 10); break;
            case "eq": line(c, 4, 21, 4, 14); line(c, 4, 10, 4, 3); line(c, 12, 21, 12, 12); line(c, 12, 8, 12, 3);
                line(c, 20, 21, 20, 16); line(c, 20, 12, 20, 3); line(c, 1, 14, 7, 14); line(c, 9, 8, 15, 8); line(c, 17, 16, 23, 16); break;
            case "queue": line(c, 3, 6, 16, 6); line(c, 3, 12, 16, 12); line(c, 3, 18, 12, 18); line(c, 19, 15, 19, 21); line(c, 16, 18, 22, 18); break;
            case "playlist_add": line(c, 3, 6, 15, 6); line(c, 3, 12, 15, 12); line(c, 3, 18, 10, 18); line(c, 18, 13, 18, 21); line(c, 14, 17, 22, 17); break;
            case "camera": p.moveTo(4, 8); p.lineTo(4, 17); p.quadTo(4, 19, 6, 19); p.lineTo(18, 19); p.quadTo(20, 19, 20, 17); p.lineTo(20, 8);
                p.quadTo(20, 6, 18, 6); p.lineTo(16, 6); p.lineTo(14.5f, 4); p.lineTo(9.5f, 4); p.lineTo(8, 6); p.lineTo(6, 6); p.quadTo(4, 6, 4, 8);
                c.drawPath(p, s); c.drawCircle(12, 12.5f, 3.5f, s); break;
            case "rotate": c.save(); c.rotate(45, 12, 12); c.drawRoundRect(new RectF(8, 3, 16, 21), 2, 2, s); c.restore();
                arc(c, 12, 12, 10, 200, 50); arc(c, 12, 12, 10, 20, 50); break;
            case "lock": rr(c, 4, 11, 20, 21); arc(c, 12, 8, 4, 180, 180); line(c, 8, 8, 8, 11); line(c, 16, 8, 16, 11); break;
            case "pip": rr(c, 2, 4, 22, 20); c.drawRoundRect(new RectF(12, 12, 19, 17), 1, 1, f); break;
            case "check": poly(c, false, 5, 12, 10, 17, 19, 7); break;
            case "plus": line(c, 12, 5, 12, 19); line(c, 5, 12, 19, 12); break;
            case "minus": line(c, 5, 12, 19, 12); break;
            case "home": poly(c, true, 3, 11, 12, 3, 21, 11, 21, 21, 15, 21, 15, 15, 9, 15, 9, 21, 3, 21); break;
            case "download": line(c, 12, 4, 12, 15); poly(c, false, 7, 10, 12, 15, 17, 10); line(c, 5, 20, 19, 20); break;
            case "refresh": arc(c, 12, 12, 9, -30, 300); poly(c, false, 21, 3, 21, 8, 16, 8); break;
            case "info": c.drawCircle(12, 12, 9, s); line(c, 12, 11, 12, 17); c.drawCircle(12, 7.5f, 1.2f, f); break;
            case "edit": line(c, 12, 20, 21, 20); poly(c, true, 16.5f, 3.5f, 20.5f, 7.5f, 7, 21, 3, 21, 3, 17); break;
            case "mic_off": line(c, 2, 2, 22, 22); arc(c, 12, 7, 3, 180, 180); line(c, 9, 7, 9, 12); line(c, 15, 7, 15, 10);
                arc(c, 12, 11, 7, 0, 180); line(c, 12, 18, 12, 22); break;
            case "settings": c.drawCircle(12, 12, 3, s); for (int i = 0; i < 8; i++) { c.save(); c.rotate(i * 45, 12, 12); line(c, 12, 2.5f, 12, 5.5f); c.restore(); }
                c.drawCircle(12, 12, 7, s); break;
            case "expand": poly(c, false, 15, 3, 21, 3, 21, 9); poly(c, false, 9, 21, 3, 21, 3, 15); line(c, 21, 3, 14, 10); line(c, 3, 21, 10, 14); break;
            case "volume": poly(c, true, 11, 5, 6, 9, 2, 9, 2, 15, 6, 15, 11, 19); arc(c, 12, 12, 4, -50, 100); arc(c, 12, 12, 8, -50, 100); break;
            case "sun": c.drawCircle(12, 12, 4, s); for (int i = 0; i < 8; i++) { c.save(); c.rotate(i * 45, 12, 12); line(c, 12, 2, 12, 4); c.restore(); } break;
            case "moon": p.addCircle(12, 12, 9, Path.Direction.CW); Path q = new Path(); q.addCircle(17, 8, 7, Path.Direction.CW);
                p.op(q, Path.Op.DIFFERENCE); c.drawPath(p, s); break;
            case "headphones": arc(c, 12, 13, 9, 180, 180); rr(c, 2, 14, 7, 21); rr(c, 17, 14, 22, 21); break;
            case "zoom": poly(c, false, 15, 3, 21, 3, 21, 9); poly(c, false, 9, 21, 3, 21, 3, 15); break;
            default: c.drawCircle(12, 12, 8, s);
        }
        c.restore();
    }

    private void line(Canvas c, float a, float b, float x, float y) { c.drawLine(a, b, x, y, s); }

    private void rr(Canvas c, float l, float t, float r, float b) { c.drawRoundRect(new RectF(l, t, r, b), 2, 2, s); }

    private void arc(Canvas c, float cx, float cy, float r, float start, float sweep) {
        c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, false, s);
    }

    private static void tri(Path p, float a, float b, float c, float d, float e, float f) {
        p.moveTo(a, b); p.lineTo(c, d); p.lineTo(e, f); p.close();
    }

    private void poly(Canvas c, boolean close, float... pts) {
        Path p = new Path();
        p.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
        if (close) p.close();
        c.drawPath(p, s);
    }
}
