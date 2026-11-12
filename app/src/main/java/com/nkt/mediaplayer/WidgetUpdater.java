package com.nkt.mediaplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Draws the home-screen widgets (4×4 and 4×2): the windscreen picture, the song, and previous / play-pause / next. */
public final class WidgetUpdater {

    private WidgetUpdater() { }

    public static final String ACTION_PREV = "com.nkt.mediaplayer.WIDGET_PREV";
    public static final String ACTION_PLAY = "com.nkt.mediaplayer.WIDGET_PLAY";
    public static final String ACTION_NEXT = "com.nkt.mediaplayer.WIDGET_NEXT";

    /** What the widgets show. */
    public static final class Info {
        public String title = "", artist = "";
        public boolean playing;
        public long posMs, durMs;
        public MediaInfo cur;                 // the song, for its album art and lyrics
    }

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    public static void update(Context c) {
        final Context app = c.getApplicationContext();
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        final int[] big = mgr.getAppWidgetIds(new ComponentName(app, PlayerWidgetLarge.class));
        final int[] small = mgr.getAppWidgetIds(new ComponentName(app, PlayerWidgetCompact.class));
        if (big.length == 0 && small.length == 0) return;
        final Info info = PlaybackService.instance != null ? PlaybackService.instance.widgetInfo() : fromSaved(app);
        final int mode = Prefs.npNow(app);
        // pictures (album art, lyrics) can take a moment to get: not on the main thread
        WORK.execute(() -> {
            try {
                if (big.length > 0) {
                    RemoteViews v = build(app, info, true, mode);
                    for (int id : big) mgr.updateAppWidget(id, v);
                }
                if (small.length > 0) {
                    RemoteViews v = build(app, info, false, mode);
                    for (int id : small) mgr.updateAppWidget(id, v);
                }
            } catch (Throwable ignored) { }
        });
    }

    /** Nothing is playing yet (just after the phone started): the song that will carry on. */
    private static Info fromSaved(Context c) {
        Info i = new Info();
        try {
            String json = Prefs.sp(c).getString("last_queue", null);
            if (json != null) {
                JSONObject o = new JSONObject(json);
                JSONArray a = o.getJSONArray("items");
                int idx = Math.max(0, Math.min(a.length() - 1, o.optInt("index")));
                JSONObject it = a.getJSONObject(idx);
                i.title = it.optString("title");
                i.artist = it.optString("artist");
                i.posMs = o.optLong("pos");
                i.durMs = it.optLong("dur");
                i.cur = MediaInfo.fromJson(it);
            }
        } catch (Throwable ignored) { }
        return i;
    }

    private static RemoteViews build(Context c, Info info, boolean large, int mode) {
        RemoteViews v = new RemoteViews(c.getPackageName(), large ? R.layout.widget_large : R.layout.widget_compact);
        boolean idle = info.title.isEmpty();
        v.setTextViewText(R.id.w_title, idle ? c.getString(R.string.widget_idle) : info.title);
        v.setTextViewText(R.id.w_artist, idle ? c.getString(R.string.widget_idle_sub) : info.artist);
        v.setImageViewResource(R.id.w_play, info.playing ? R.drawable.wg_pause : R.drawable.wg_play);
        v.setContentDescription(R.id.w_play, c.getString(info.playing ? R.string.cd_pause : R.string.cd_play));
        if (large) v.setProgressBar(R.id.w_progress, 1000, info.durMs > 0 ? (int) (info.posMs * 1000 / info.durMs) : 0, false);
        v.setImageViewBitmap(R.id.w_scene, scene(c, large, info, mode));

        v.setOnClickPendingIntent(R.id.w_prev, command(c, ACTION_PREV));
        v.setOnClickPendingIntent(R.id.w_play, command(c, ACTION_PLAY));
        v.setOnClickPendingIntent(R.id.w_next, command(c, ACTION_NEXT));
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, NowPlayingActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_open, open);
        v.setOnClickPendingIntent(R.id.w_scene, open);
        return v;
    }

    private static PendingIntent command(Context c, String action) {
        Intent i = new Intent(c, WidgetReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** What the player shows right now, as a still picture: the visualiser, the lyrics or the album art. */
    private static Bitmap scene(Context c, boolean large, Info info, int mode) {
        int w = large ? 480 : 264, h = large ? 314 : 264;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);

        // rounded corners that match the card: top for 4×4, left for 4×2
        float r = (large ? 28f / 358f * w : 28f / 132f * w);
        Path clip = new Path();
        float[] radii = large ? new float[]{r, r, r, r, 0, 0, 0, 0} : new float[]{r, r, 0, 0, 0, 0, r, r};
        clip.addRoundRect(new RectF(0, 0, w, h), radii, Path.Direction.CW);
        cv.clipPath(clip);

        if (mode == 2) art(c, cv, w, h, info);
        else if (mode == 1) lyrics(c, cv, w, h, info);
        else if (Prefs.vizStyle(c) == VizView.GAMAKA) gamaka(c, cv, w, h);
        else windscreen(cv, w, h, large, info);
        return b;
    }

    private static void windscreen(Canvas cv, int w, int h, boolean large, Info info) {
        float vx = large ? 0 : 92, vy = large ? 70 : 100, vw = large ? 348 : 164, vh = large ? 228 : 198;
        float s = Math.max(w / vw, h / vh);                       // fill the area, keep the shape (the sides are cropped)
        float tx = (w - vw * s) / 2f - vx * s, ty = (h - vh * s) - vy * s;
        cv.translate(tx, ty);
        cv.scale(s, s);
        float left = -tx / s, right = (w - tx) / s;
        float off = 1.3f + info.posMs / 1000f * 6f;
        new WindscreenPainter().paint(cv, left, right, off, info.playing ? 0.6f : 0f);
    }

    /** The Gamaka lanes (a still picture cannot show the moving line). */
    private static void gamaka(Context c, Canvas cv, int w, int h) {
        VizView v = new VizView(c);
        v.setStyle(VizView.GAMAKA);
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        v.draw(cv);
    }

    /** Album art (or the app logo when there is none), the way Now Playing shows it. */
    private static void art(Context c, Canvas cv, int w, int h, Info info) {
        cv.drawColor(0xFF0B1222);
        Bitmap a = info.cur == null ? AppLogo.bitmap(c, 512) : NotifArt.bitmapFor(c, info.cur, 512);
        if (a == null) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        boolean wide = w > h;
        if (wide) {                                              // 4×4: the picture sits in the middle, whole
            float side = h * 0.92f, x = (w - side) / 2f, y = (h - side) / 2f;
            Path rr = new Path();
            rr.addRoundRect(new RectF(x, y, x + side, y + side), 22f, 22f, Path.Direction.CW);
            cv.save();
            cv.clipPath(rr);
            cv.drawBitmap(a, null, new RectF(x, y, x + side, y + side), p);
            cv.restore();
        } else {
            cv.drawBitmap(a, null, new RectF(0, 0, w, h), p);   // 4×2: the picture fills its square
        }
    }

    // the lyrics of the song in view are kept so they are not looked up again at every refresh
    private static String lyricKey = "";
    private static Lyrics.Result lyricVal = new Lyrics.Result();

    private static void lyrics(Context c, Canvas cv, int w, int h, Info info) {
        cv.drawColor(0xFF0B1222);
        Lyrics.Result res = null;
        if (info.cur != null) {
            String key = info.cur.key();
            synchronized (WidgetUpdater.class) {
                if (!key.equals(lyricKey)) {
                    lyricVal = Lyrics.find(c, info.cur, info.cur.title, info.cur.artist, info.cur.album, info.durMs);
                    lyricKey = key;
                }
                res = lyricVal;
            }
        }
        TextPaint dim = new TextPaint(Paint.ANTI_ALIAS_FLAG), hot = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        float u = w / 360f;
        dim.setColor(0xFF7F8CA6); dim.setTextSize(15 * u);
        hot.setColor(0xFF00E5FF); hot.setTextSize(21 * u); hot.setTypeface(Typeface.DEFAULT_BOLD);
        int tw = (int) (w - 36 * u);

        if (res == null || res.lines.isEmpty()) {
            TextPaint t = new TextPaint(dim);
            StaticLayout sl = layout(c.getString(R.string.lyrics_none_widget), t, tw);
            cv.save(); cv.translate(18 * u, (h - sl.getHeight()) / 2f); sl.draw(cv); cv.restore();
            return;
        }
        List<Lyrics.Line> L = res.lines;
        int cur = 0;
        if (res.synced) {
            long t = info.posMs + Lyrics.offset(c, info.cur);
            for (int i = 0; i < L.size(); i++) if (L.get(i).ms >= 0 && L.get(i).ms <= t) cur = i;
        }
        // the line being sung in the middle; two lines before it and two after it (as far as they fit)
        int from = Math.max(0, cur - 2), to = Math.min(L.size() - 1, cur + 2);
        if (!res.synced) { from = 0; to = Math.min(L.size() - 1, 6); cur = -1; }
        List<StaticLayout> ls = new java.util.ArrayList<>();
        float total = 0, gap = 8 * u;
        for (int i = from; i <= to; i++) {
            StaticLayout sl = layout(L.get(i).text, i == cur ? hot : dim, tw);
            ls.add(sl); total += sl.getHeight() + gap;
        }
        float y = (h - (total - gap)) / 2f;
        if (y < 6 * u) y = 6 * u;
        for (StaticLayout sl : ls) {
            if (y + sl.getHeight() > h) break;
            cv.save(); cv.translate(18 * u, y); sl.draw(cv); cv.restore();
            y += sl.getHeight() + gap;
        }
    }

    private static StaticLayout layout(CharSequence t, TextPaint p, int width) {
        return StaticLayout.Builder.obtain(t, 0, t.length(), p, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build();
    }
}
