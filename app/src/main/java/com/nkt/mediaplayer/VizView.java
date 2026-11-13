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

    public static final int WINDSCREEN = 0, GAMAKA = 1, STEADY = 2;

    private static final int ACC = 0xFF00E5FF;
    private static final String[] SWARA = {"Sa", "Re", "Ga", "Ma", "Pa", "Dha", "Ni", "Sa'"};
    private static final String[] NOTE = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};
    private static final float[] DEGREE = {0, 2, 4, 5, 7, 9, 11, 12};      // semitones above Sa of each lane

    private int style = WINDSCREEN;
    private boolean running = false;
    private float density = 1f;

    // road
    private float off = 0f;
    private float speedNow = 0f;          // eased speed of the road, so it never jumps
    private float pulseS = 0f;            // eased beat pulse
    private float steadySpeed = 40 * 0.3f;
    private long lastFrame = 0;
    private int seenBeat = 0;
    private long beatAt = 0;
    private int beatIndex = 0;

    // sung note
    private static final int HIST = 200;
    private final long[] hT = new long[HIST];
    private final float[] hAbs = new float[HIST];     // the sung note, in semitones above middle C; the lane is worked out when drawing
    private int hN = 0;
    private long lastSample = 0;
    private boolean haveAbs = false;
    private float absRaw = 0f;                         // the latest note heard
    private float absHead = 0f;                        // the same, lightly smoothed: where the line's head is
    private float candA = 0f;                          // a big leap, waiting to prove itself
    private int candN = 0;
    private float headLane = 0f;
    private long voicedAt = 0;
    private float saF = 0f;                            // the Sa the lanes are drawn against; glides when the Sa changes
    private int saTarget = -1;
    private boolean haveSaF = false;
    private float octShift = 0f;
    private boolean octInit = false, snapBase = false;
    private int autoSeen = -2;
    private long autoSince = 0;

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
        style = s == GAMAKA ? GAMAKA : s == STEADY ? STEADY : WINDSCREEN;
        resetLine();
        invalidate();
    }

    /** Steady windscreen: the speed options are 20, 40, 60, 80; the road runs this many × SPEED_UNIT per second. */
    public static final float SPEED_UNIT = 0.3f;
    public void setSteadySpeed(int s) { steadySpeed = Math.max(1, s) * SPEED_UNIT; }

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
        if (style == GAMAKA) drawGamaka(canvas, now, playing);
        else drawWindscreen(canvas, now, dt, playing);
        if (running) postInvalidateOnAnimation();
    }

    // ── Windscreen ──────────────────────────────────────────────────────────

    private static final float TAU = 0.18f;

    private void drawWindscreen(Canvas cv, long now, float dt, boolean playing) {
        float u = getHeight() / 298f;
        float wu = getWidth() / u;
        float dx = (wu - 348f) / 2f;
        float pulse, target;
        if (style == STEADY) {
            pulse = 0f;                                              // the road does not follow the beat
            target = playing ? steadySpeed : 0f;
        } else {
            float since = beatAt == 0 ? 99f : (now - beatAt) / 1000f;
            float raw = playing ? (float) Math.exp(-since / TAU) : 0f;
            // the pulse rises over a few frames and fades away: no sudden step
            pulseS += (raw - pulseS) * (1f - (float) Math.exp(-dt / (raw > pulseS ? 0.07f : 0.14f)));
            pulse = pulseS;
            target = playing ? 7f + 7f * pulse : 0f;
        }
        // the speed glides towards its target (starting, stopping and beats alike)
        speedNow += (target - speedNow) * (1f - (float) Math.exp(-dt / 0.45f));
        off += speedNow * dt;

        cv.save();
        cv.scale(u, u);
        cv.translate(dx, 0);
        painter.paint(cv, -dx, 348f + dx, off, pulse);
        cv.restore();
    }

    // ── Gamaka line ─────────────────────────────────────────────────────────

    private static final float LH = 21f, HX = 106f, PPS = 56f;

    /** Name of a lane: 0..6 are Sa..Ni, 7 is the upper Sa; further up the next octave (marked '), further down the lower one (marked .). */
    private static String laneName(int k) {
        int oct = Math.floorDiv(k, 7), idx = Math.floorMod(k, 7);
        return SWARA[idx] + (oct > 0 ? "'" : oct < 0 ? "." : "");
    }

    private float baseNow = 0f;            // the lane at the bottom of the screen; moves when the line needs more room
    private long lastGam = 0;

    private static float laneOf(float s) {
        if (s <= 0) return s / 2f;
        if (s >= 12) return 7 + (s - 12) / 2f;
        for (int i = 0; i < 7; i++) {
            if (s < DEGREE[i + 1]) return i + (s - DEGREE[i]) / (DEGREE[i + 1] - DEGREE[i]);
        }
        return 7;
    }

    private void resetLine() {
        hN = 0; haveAbs = false; candN = 0; octInit = false; haveSaF = false; saTarget = -1;
        autoSeen = -2; snapBase = true;
    }

    /**
     * Takes the latest sung note into the line. The line does not restart in the middle of a song: when the Sa changes,
     * everything glides to its new place. Notes are not changed, only smoothed a little; between notes the line holds its height.
     */
    private void sample(long now) {
        if (now - lastSample < 16) return;
        float dt = lastSample == 0 ? 0.016f : Math.min(0.1f, (now - lastSample) / 1000f);
        lastSample = now;
        if (seenSong != AudioAnalyzer.songNo) {                      // another song: its own key, its own line
            seenSong = AudioAnalyzer.songNo;
            resetLine();
        }
        float hz = AudioAnalyzer.pitchHz;

        // which note is Sa: the listener's choice; else the first note heard, then what the song settles on
        int user = Prefs.saNote(getContext());
        int auto = AudioAnalyzer.autoSa;
        if (user >= 0) saTarget = user;
        else {
            if (auto != autoSeen) { autoSeen = auto; autoSince = now; }
            if (auto >= 0 && now - autoSince > 2500) saTarget = auto;              // steady for a while: believe it
            else if (saTarget < 0 && hz > 0) saTarget = (int) (((Math.round(12.0 * Math.log(hz / 261.6256) / Math.log(2.0)) % 12) + 12) % 12);
        }
        if (saTarget >= 0) {
            if (!haveSaF) { saF = saTarget; haveSaF = true; }
            float d = saTarget - saF;
            d -= 12f * Math.round(d / 12f);                          // the short way round
            saF += d * (1f - (float) Math.exp(-dt / 0.3f));
        }

        if (hz > 0) {
            float a = (float) (12.0 * Math.log(hz / 261.6256) / Math.log(2.0));
            boolean take = true;
            if (haveAbs) {
                a -= 12f * Math.round((a - absRaw) / 12f);                           // stay near the last note
                if (Math.abs(a - absRaw) > 3f) {                                     // a big leap: only if it holds for a moment
                    if (candN > 0 && Math.abs(a - candA) < 1f) candN++; else { candN = 1; candA = a; }
                    take = candN >= 3;
                } else candN = 0;
            }
            if (take) {
                absRaw = a;
                candN = 0;
                if (!haveAbs) { absHead = a; haveAbs = true; }
                voicedAt = now;
            }
        }
        if (!haveAbs || !haveSaF) return;                             // nothing sung yet
        absHead += (absRaw - absHead) * (1f - (float) Math.exp(-dt / 0.035f));     // a little smoothing

        float s = absHead - saF + octShift;
        if (!octInit || s < -4f || s > 16f) {                          // bring the line into the lanes (rare)
            octShift += -12f * (float) Math.floor(s / 12f);
            octInit = true;
            snapBase = true;
        }
        headLane = laneOf(absHead - saF + octShift);
        if (hN == HIST) { System.arraycopy(hT, 1, hT, 0, HIST - 1); System.arraycopy(hAbs, 1, hAbs, 0, HIST - 1); hN--; }
        hT[hN] = now;
        hAbs[hN++] = absHead;
    }

    private int seenSong = 0;

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

        boolean voiced = now - voicedAt < 900 && playing;
        int curLane = voiced ? Math.round(headLane) : Integer.MIN_VALUE;

        // the swara column moves when the note goes above or below the eight lanes
        float dtg = lastGam == 0 ? 0f : Math.min(0.1f, (now - lastGam) / 1000f);
        lastGam = now;
        float want = baseNow;
        if (haveAbs && haveSaF) {
            if (headLane > want + 6.2f) want = headLane - 6.2f;
            if (headLane < want + 0.8f) want = headLane - 0.8f;
        }
        if (snapBase) { baseNow = want; snapBase = false; }
        else baseNow += (want - baseNow) * (1f - (float) Math.exp(-dtg / 0.12f));

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
        cv.drawText(curLane != Integer.MIN_VALUE ? laneName(curLane) : "—", 16, 12 + 12 + 30, p);
        p.setFakeBoldText(false);
        p.setTypeface(null);

        // lanes (inside a clip, so lanes that slide in and out are cut cleanly)
        float rx = 40f, rw = wu - 52f, top = 64f;
        p.setTextSize(11f);
        cv.save();
        cv.clipRect(0, top, wu, top + 168f);
        int k0 = (int) Math.floor(baseNow) - 1;
        for (int k = k0; k <= k0 + 10; k++) {
            float cy = top + (7 - (k - baseNow)) * LH + LH / 2;
            if (cy < top - LH || cy > top + 168f + LH) continue;
            boolean act = k == curLane;
            p.setStyle(Paint.Style.FILL);
            p.setColor(act ? ACC : 0xFF8A9AB2);
            p.setFakeBoldText(act);
            cv.drawText(laneName(k), 4, cy + 4, p);
            p.setFakeBoldText(false);
            int idx = Math.floorMod(k, 7);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            p.setColor(idx == 0 ? 0x29E8F0FE : idx == 4 ? 0x1AE8F0FE : 0x0DE8F0FE);
            cv.drawLine(rx, cy, rx + rw, cy, p);
        }
        cv.restore();
        p.setStyle(Paint.Style.FILL);

        // the line: what was sung (solid) and a dotted guess ahead
        cv.save();
        cv.clipRect(rx, top, rx + rw, top + 168f);
        cv.translate(rx, top);
        float ph = HX;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        float px = 0, py = 0;
        boolean pen = false;
        for (int i = 0; i < hN; i++) {
            float x = HX + (hT[i] - now) / 1000f * PPS;
            if (x < -4) continue;                                     // already scrolled out on the left
            float y = (7 - (laneOf(hAbs[i] - saF + octShift) - baseNow)) * LH + LH / 2;
            if (!pen) { path.moveTo(x, y); pen = true; }
            else path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);   // every point is joined, with soft corners
            px = x; py = y;
        }
        if (pen) path.lineTo(px, py);
        p.setShader(new LinearGradient(0, 0, rw, 0, 0xFF7B61FF, ACC, Shader.TileMode.CLAMP));
        p.setStrokeWidth(9f);
        p.setAlpha(0x33);
        cv.drawPath(path, p);
        p.setStrokeWidth(3.5f);
        p.setAlpha(0xFF);
        cv.drawPath(path, p);
        p.setShader(null);

        float hy = (7 - (headLane - baseNow)) * LH + LH / 2;
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
