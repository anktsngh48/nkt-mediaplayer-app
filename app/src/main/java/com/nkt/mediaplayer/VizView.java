package com.nkt.mediaplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * The visualiser: the Windscreen (a night drive that surges on every beat) or the Gamaka line (the sung note
 * against Sa Re Ga Ma Pa Dha Ni). It draws itself and asks {@link AudioAnalyzer} what the song is doing.
 */
public class VizView extends View {

    public static final int WINDSCREEN = 0, GAMAKA = 1;

    private static final int ACC = 0xFF00E5FF;
    private static final String[] SWARA = {"Sa", "Re", "Ga", "Ma", "Pa", "Dha", "Ni", "Sa'"};
    private static final String[] NOTE = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};
    private static final float[] DEGREE = {0, 2, 4, 5, 7, 9, 11, 12};      // semitones above Sa of each lane

    private int style = WINDSCREEN;
    private boolean running = false;
    private float density = 1f;

    // road
    private float off = 0f;
    private long lastFrame = 0;
    private int seenBeat = 0;
    private long beatAt = 0;
    private int beatIndex = 0;

    // sung note
    private static final int HIST = 160;
    private final long[] hT = new long[HIST];
    private final float[] hLane = new float[HIST];
    private int hN = 0;
    private long lastSample = 0;
    private float prevSemis = 0f;
    private boolean haveSemis = false;
    private float headLane = 0f;
    private long voicedAt = 0;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final WindscreenPainter painter = new WindscreenPainter();
    private android.graphics.Typeface orb;

    public VizView(Context c) { super(c); init(); }
    public VizView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        orb = Ui.orbitron(getContext());
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * density); }
        });
    }

    public void setStyle(int s) {
        style = s == GAMAKA ? GAMAKA : WINDSCREEN;
        hN = 0;
        invalidate();
    }

    public int getStyle() { return style; }

    /** 0..3: which beat of the bar the last hit fell on. */
    public int beatIndex() { return beatIndex; }

    /** The Sa in use: the listener's choice, else the one found by listening, else -1. */
    public static int saNow(Context c) {
        int s = Prefs.saNote(c);
        return s >= 0 ? s : AudioAnalyzer.autoSa;
    }

    public static String noteName(int pc) { return NOTE[((pc % 12) + 12) % 12]; }

    public static String[] noteNames() { return NOTE.clone(); }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopRunning();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        updateRunning();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateRunning();
    }

    private void updateRunning() {
        if (isAttachedToWindow() && getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE) {
            if (!running) { running = true; AudioAnalyzer.start(); lastFrame = 0; postInvalidateOnAnimation(); }
        } else stopRunning();
    }

    private void stopRunning() {
        if (running) { running = false; AudioAnalyzer.stop(); }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean playing = now - AudioAnalyzer.lastAudioMs < 500;

        if (AudioAnalyzer.beatCount != seenBeat) {
            seenBeat = AudioAnalyzer.beatCount;
            beatAt = AudioAnalyzer.lastBeatMs;
            beatIndex = seenBeat & 3;
        }
        if (style == WINDSCREEN) drawWindscreen(canvas, now, dt, playing);
        else drawGamaka(canvas, now, playing);
        if (running) postInvalidateOnAnimation();
    }

    // ── Windscreen ──────────────────────────────────────────────────────────

    private static final float TAU = 0.18f;

    private void drawWindscreen(Canvas cv, long now, float dt, boolean playing) {
        float u = getHeight() / 298f;
        float wu = getWidth() / u;
        float dx = (wu - 348f) / 2f;
        float since = beatAt == 0 ? 99f : (now - beatAt) / 1000f;
        float pulse = playing ? (float) Math.exp(-since / TAU) : 0f;
        off += (playing ? 6f + 12f * pulse : 0f) * dt;

        cv.save();
        cv.scale(u, u);
        cv.translate(dx, 0);
        painter.paint(cv, -dx, 348f + dx, off, pulse);
        cv.restore();
    }

    // ── Gamaka line ─────────────────────────────────────────────────────────

    private static final float LH = 21f, HX = 106f, PPS = 56f;

    private static float laneOf(float s) {
        if (s <= 0) return s / 2f;
        if (s >= 12) return 7 + (s - 12) / 2f;
        for (int i = 0; i < 7; i++) {
            if (s < DEGREE[i + 1]) return i + (s - DEGREE[i]) / (DEGREE[i + 1] - DEGREE[i]);
        }
        return 7;
    }

    /** Takes the latest sung note into the line. */
    private void sample(long now) {
        if (now - lastSample < 16) return;
        lastSample = now;
        int sa = saNow(getContext());
        float hz = AudioAnalyzer.pitchHz;
        float lane = Float.NaN;
        if (hz > 0 && sa >= 0) {
            float saHz = (float) (261.6256 * Math.pow(2.0, sa / 12.0));
            float s = (float) (12.0 * Math.log(hz / saHz) / Math.log(2.0));
            if (haveSemis) s -= 12f * Math.round((s - prevSemis) / 12f);          // stay near the last note
            if (s < -2.5f || s > 14.5f) s = ((s % 12f) + 12f) % 12f;               // far away: bring it into the octave
            prevSemis = s;
            haveSemis = true;
            lane = laneOf(s);
            headLane = lane;
            voicedAt = now;
        }
        if (hN == HIST) { System.arraycopy(hT, 1, hT, 0, HIST - 1); System.arraycopy(hLane, 1, hLane, 0, HIST - 1); hN--; }
        hT[hN] = now;
        hLane[hN++] = lane;
    }

    private void drawGamaka(Canvas cv, long now, boolean playing) {
        sample(now);
        float wu = getWidth() / density, hu = getHeight() / density;
        cv.save();
        cv.scale(density, density);

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B1222);
        cv.drawRect(0, 0, wu, hu, p);
        p.setShader(new RadialGradient(wu / 2, hu, wu * 0.9f, new int[]{0x337B61FF, 0x000B1222}, new float[]{0f, 0.7f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, wu, hu, p);
        p.setShader(null);

        boolean voiced = now - voicedAt < 600 && playing;
        int curLane = voiced ? Math.max(0, Math.min(7, Math.round(headLane))) : -1;

        // "NOW SINGING"
        p.setTypeface(orb);
        p.setTextSize(9f);
        p.setLetterSpacing(0.18f);
        p.setColor(0xFF8A9AB2);
        cv.drawText("NOW SINGING", 16, 12 + 9, p);
        p.setTextSize(30f);
        p.setLetterSpacing(0f);
        p.setFakeBoldText(true);
        p.setColor(ACC);
        cv.drawText(curLane >= 0 ? SWARA[curLane] : "—", 16, 12 + 12 + 30, p);
        p.setFakeBoldText(false);
        p.setTypeface(null);

        // lanes
        float rx = 40f, rw = wu - 52f, top = 64f;
        p.setTextSize(11f);
        for (int r = 0; r < 8; r++) {
            int lane = 7 - r;
            boolean act = lane == curLane;
            p.setColor(act ? ACC : 0xFF8A9AB2);
            p.setFakeBoldText(act);
            cv.drawText(SWARA[lane], 8, top + r * LH + LH / 2 + 4, p);
            p.setFakeBoldText(false);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            p.setColor(lane == 0 || lane == 7 ? 0x29E8F0FE : lane == 4 ? 0x1AE8F0FE : 0x0DE8F0FE);
            cv.drawLine(rx, top + r * LH + LH / 2, rx + rw, top + r * LH + LH / 2, p);
            p.setStyle(Paint.Style.FILL);
        }

        // the line: what was sung (solid) and a dotted guess ahead
        cv.save();
        cv.clipRect(rx, top, rx + rw, top + 168f);
        cv.translate(rx, top);
        float ph = HX;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        boolean pen = false;
        for (int i = 0; i < hN; i++) {
            float x = HX + (hT[i] - now) / 1000f * PPS;
            if (x < -4) { pen = false; continue; }
            if (Float.isNaN(hLane[i])) { pen = false; continue; }
            float y = (7 - hLane[i]) * LH + LH / 2;
            if (!pen) { path.moveTo(x, y); pen = true; } else path.lineTo(x, y);
        }
        p.setShader(new LinearGradient(0, 0, rw, 0, 0xFF7B61FF, ACC, Shader.TileMode.CLAMP));
        p.setStrokeWidth(9f);
        p.setAlpha(0x33);
        cv.drawPath(path, p);
        p.setStrokeWidth(3.5f);
        p.setAlpha(0xFF);
        cv.drawPath(path, p);
        p.setShader(null);

        float hy = (7 - headLane) * LH + LH / 2;
        if (voiced) {
            p.setStrokeWidth(2f);
            p.setColor(0x9900E5FF);
            p.setPathEffect(new DashPathEffect(new float[]{1f, 6f}, 0f));
            cv.drawLine(ph, hy, rw, hy, p);
            p.setPathEffect(null);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(voiced ? 0x4000E5FF : 0x1800E5FF);
        cv.drawCircle(ph, hy, 15f, p);
        p.setColor(voiced ? ACC : 0x7700E5FF);
        cv.drawCircle(ph, hy, 7f, p);
        cv.restore();
        p.setStrokeCap(Paint.Cap.BUTT);
        cv.restore();
        p.setStyle(Paint.Style.FILL);
        p.setLetterSpacing(0f);
    }
}
