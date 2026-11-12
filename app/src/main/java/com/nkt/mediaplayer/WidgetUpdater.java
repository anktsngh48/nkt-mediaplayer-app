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

/** Draws the home-screen widgets (4×3 and 4×2): the album art or lyrics, the song, and previous / play-pause / next. */
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
                for (int id : big) {                                   // the large one is drawn to its own size
                    int[] sz = sizeDp(mgr, id, 288, 276);
                    mgr.updateAppWidget(id, build(app, info, true, mode, sz[0], sz[1]));
                }
                if (small.length > 0) {
                    RemoteViews v = build(app, info, false, mode, 288, 150);
                    for (int id : small) mgr.updateAppWidget(id, v);
                }
            } catch (Throwable ignored) { }
        });
    }

    /** The widget's size on the home screen in dp (what the launcher gave it), or the usual 4-wide size. */
    private static int[] sizeDp(AppWidgetManager mgr, int id, int defW, int defH) {
        int w = defW, h = defH;
        try {
            android.os.Bundle o = mgr.getAppWidgetOptions(id);
            int ow = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            int oh = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            if (ow >= 120) w = ow;
            if (oh >= 120) h = oh;
        } catch (Throwable ignored) { }
        return new int[]{w, h};
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

    private static RemoteViews build(Context c, Info info, boolean large, int mode, int wDp, int hDp) {
        RemoteViews v = new RemoteViews(c.getPackageName(), large ? R.layout.widget_large : R.layout.widget_compact);
        boolean idle = info.title.isEmpty();
        v.setTextViewText(R.id.w_title, idle ? c.getString(R.string.widget_idle) : info.title);
        String sub = idle ? c.getString(R.string.widget_idle_sub) : info.artist;
        v.setTextViewText(R.id.w_artist, large && sub != null && !sub.isEmpty() ? "\u00B7 " + sub : sub);   // 4×3: title and artist share one row
        v.setImageViewResource(R.id.w_play, info.playing ? R.drawable.wg_pause : R.drawable.wg_play);
        v.setContentDescription(R.id.w_play, c.getString(info.playing ? R.string.cd_pause : R.string.cd_play));
        v.setProgressBar(R.id.w_progress, 1000, info.durMs > 0 ? (int) (info.posMs * 1000 / info.durMs) : 0, false);
        v.setImageViewBitmap(R.id.w_scene, scene(c, large, info, mode, wDp, hDp));

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

    /** What the widget shows: the lyrics, or the album art (the app logo if there is none). */
    private static Bitmap scene(Context c, boolean large, Info info, int mode, int wDp, int hDp) {
        float px = Math.min(c.getResources().getDisplayMetrics().density, 3f);        // bitmap pixels per dp
        int w, h;
        float[] radii;
        if (large) {                                                  // edge to edge across the top; the rows below take about 98 dp
            w = Math.round(wDp * px);
            h = Math.round(Math.max(90, hDp - 98) * px);
            float r = 27 * px;
            radii = new float[]{r, r, r, r, 0, 0, 0, 0};
        } else {                                                      // a square beside the song
            w = h = Math.round(104 * px);
            float r = 18 * px;
            radii = new float[]{r, r, r, r, r, r, r, r};
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, w, h), radii, Path.Direction.CW);
        cv.clipPath(clip);

        if (mode == 1) lyrics(c, cv, w, h, info, large);          // lyrics, or the album art / app logo; never a visualiser
        else art(c, cv, w, h, info);
        return b;
    }

    /** Album art filling the area (cropped to fit), or the app logo on dark blue when the song has none. */
    private static void art(Context c, Canvas cv, int w, int h, Info info) {
        Bitmap a = info.cur == null ? null : NotifArt.artFor(c, info.cur);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (a == null) {
            cv.drawColor(0xFF0B1222);
            Bitmap logo = AppLogo.bitmap(c, 512);
            float s = Math.min(w, h) * 0.78f;
            cv.drawBitmap(logo, null, new RectF((w - s) / 2f, (h - s) / 2f, (w + s) / 2f, (h + s) / 2f), p);
            return;
        }
        cv.drawColor(0xFF0B1222);
        float aw = a.getWidth(), ah = a.getHeight();
        float sc = Math.max(w / aw, h / ah);                       // fill, keep the shape, crop the overflow
        float sw = w / sc, sh = h / sc;
        android.graphics.Rect src = new android.graphics.Rect((int) ((aw - sw) / 2), (int) ((ah - sh) / 2), (int) ((aw + sw) / 2), (int) ((ah + sh) / 2));
        cv.drawBitmap(a, src, new RectF(0, 0, w, h), p);
    }

    // the lyrics of the song in view are kept so they are not looked up again at every refresh
    private static String lyricKey = "";
    private static Lyrics.Result lyricVal = new Lyrics.Result();

    private static void lyrics(Context c, Canvas cv, int w, int h, Info info, boolean large) {
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
        float u = Math.min(c.getResources().getDisplayMetrics().density, 3f);      // one dp in bitmap pixels
        float side = (large ? 16 : 8) * u;
        dim.setColor(0xFF7F8CA6); dim.setTextSize((large ? 14 : 10.5f) * u);
        hot.setColor(0xFF00E5FF); hot.setTextSize((large ? 18 : 12.5f) * u); hot.setTypeface(Typeface.DEFAULT_BOLD);
        int tw = (int) (w - 2 * side);

        if (res == null || res.lines.isEmpty()) {
            TextPaint t = new TextPaint(dim);
            StaticLayout sl = layout(c.getString(R.string.lyrics_none_widget), t, tw);
            cv.save(); cv.translate(side, (h - sl.getHeight()) / 2f); sl.draw(cv); cv.restore();
            return;
        }
        List<Lyrics.Line> L = res.lines;
        int cur = 0;
        if (res.synced) {
            long t = info.posMs + Lyrics.offset(c, info.cur);
            for (int i = 0; i < L.size(); i++) if (L.get(i).ms >= 0 && L.get(i).ms <= t) cur = i;
        }
        // the line being sung in the middle, one before it and one after it (as far as they fit)
        int from = Math.max(0, cur - 1), to = Math.min(L.size() - 1, cur + 1);
        if (!res.synced) { from = 0; to = Math.min(L.size() - 1, 6); cur = -1; }
        List<StaticLayout> ls = new java.util.ArrayList<>();
        float total = 0, gap = (large ? 8 : 4) * u;
        for (int i = from; i <= to; i++) {
            StaticLayout sl = layout(L.get(i).text, i == cur ? hot : dim, tw);
            ls.add(sl); total += sl.getHeight() + gap;
        }
        float y = (h - (total - gap)) / 2f;
        if (y < 4 * u) y = 4 * u;
        for (StaticLayout sl : ls) {
            if (y + sl.getHeight() > h) break;
            cv.save(); cv.translate(side, y); sl.draw(cv); cv.restore();
            y += sl.getHeight() + gap;
        }
    }

    private static StaticLayout layout(CharSequence t, TextPaint p, int width) {
        return StaticLayout.Builder.obtain(t, 0, t.length(), p, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build();
    }
}
