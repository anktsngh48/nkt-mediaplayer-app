package com.nkt.mediaplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Online Music page: say or type a song, it plays from YouTube (the official embedded player, as YouTube requires).
 * The page lives in assets/online and is served from https://appassets.androidplatform.net so YouTube accepts it.
 * The app does the searching itself (YouTube results + free Piped servers, first answer wins: no API key, no proxies)
 * and the voice (Android's own voice typing). Sound starts without an extra tap.
 */
public final class OnlineMusic {

    public static final int REQ_VOICE = 4501;
    private static final String HOST = "appassets.androidplatform.net";
    private static final String PAGE = "https://" + HOST + "/online/index.html";

    public interface Host {
        /** A song started (true) or stopped (false) here: the app pauses its own player while one plays. */
        void onOnlinePlaying(boolean playing);
    }

    private final Activity a;
    private final Host host;
    private final FrameLayout box;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private WebView web;
    private boolean playing = false;

    public OnlineMusic(Activity a, FrameLayout box, Host host) {
        this.a = a;
        this.box = box;
        this.host = host;
    }

    public boolean isPlaying() { return playing; }

    /** Opens the page (made on first use and kept, so a song and its results stay while you look elsewhere). */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void show() {
        box.setVisibility(View.VISIBLE);
        if (web != null) { web.onResume(); return; }
        web = new WebView(a);
        web.setBackgroundColor(a.getColor(R.color.bg));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);        // sound starts at once: no "tap to unmute" step
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost()) || u.getPath() == null) return null;
                String p = u.getPath();
                String asset = p.startsWith("/fonts/") ? "fonts/" + p.substring(7) : p.startsWith("/online/") ? "online/" + p.substring(8) : null;
                if (asset == null) return null;
                try {
                    String type = asset.endsWith(".html") ? "text/html" : asset.endsWith(".ttf") ? "font/ttf" : "application/octet-stream";
                    return new WebResourceResponse(type, "utf-8", a.getAssets().open(asset));
                } catch (Exception e) { return null; }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !HOST.equals(r.getUrl().getHost());     // stay on this page (no YouTube site pages inside the app)
            }
        });
        web.addJavascriptInterface(new Bridge(), "NKT");
        box.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(PAGE);
    }

    /** Leaving the page: YouTube songs can't keep playing in the background. */
    public void hide() {
        pause();
        box.setVisibility(View.GONE);
    }

    public void pause() {
        if (web != null) web.evaluateJavascript("window.NKTpause && NKTpause()", null);
    }

    public void onActivityPause() { pause(); if (web != null) web.onPause(); }

    public void onActivityResume(boolean visible) { if (web != null && visible) web.onResume(); }

    public void destroy() {
        pool.shutdownNow();
        if (web != null) { web.destroy(); web = null; }
    }

    // ── Voice ────────────────────────────────────────────────────────────────

    /** Returns true when the result was ours. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_VOICE) return false;
        String text = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            List<String> l = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (l != null && !l.isEmpty()) text = l.get(0);
        }
        if (web == null) return true;
        if (text == null || text.trim().isEmpty()) js("NKTvoiceError(" + JSONObject.quote(a.getString(R.string.online_no_speech)) + ")");
        else js("NKTvoice(" + JSONObject.quote(text.trim()) + ")");
        return true;
    }

    private void js(String code) { ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    final class Bridge {
        @JavascriptInterface
        public void listen() {
            ui.post(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, a.getString(R.string.online_prompt))
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                try {
                    a.startActivityForResult(i, REQ_VOICE);
                } catch (ActivityNotFoundException e) {
                    js("NKTvoiceError(" + JSONObject.quote(a.getString(R.string.online_no_voice)) + ")");
                }
            });
        }

        @JavascriptInterface
        public void search(String q, int seq) {
            pool.execute(() -> {
                String key = q.toLowerCase(Locale.ROOT).trim();
                String json = cache.get(key);
                if (json == null) json = searchAll(q);
                if (json != null) {
                    cache.put(key, json);
                    js("NKTresults(" + seq + "," + JSONObject.quote(json) + ")");
                } else {
                    js("NKTsearchFailed(" + seq + "," + JSONObject.quote(a.getString(R.string.online_search_failed)) + ")");
                }
            });
        }

        @JavascriptInterface
        public void playing(boolean on) {
            ui.post(() -> {
                playing = on;
                host.onOnlinePlaying(on);
            });
        }
    }

    // ── Search: every source at once, first one with results wins ─────────────

    private static final String[] PIPED = {
            "https://api.piped.private.coffee", "https://pipedapi.ducks.party", "https://pipedapi.kavin.rocks",
            "https://pipedapi.leptons.xyz", "https://pipedapi.adminforge.de", "https://pipedapi.reallyaweso.me"};

    private String searchAll(String q) {
        ExecutorCompletionService<JSONArray> race = new ExecutorCompletionService<>(pool);
        List<Future<JSONArray>> jobs = new ArrayList<>();
        jobs.add(race.submit(() -> searchYouTube(q)));
        for (String api : PIPED) jobs.add(race.submit(() -> searchPiped(api, q)));
        long until = System.currentTimeMillis() + 12000;
        try {
            for (int i = 0; i < jobs.size(); i++) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                Future<JSONArray> f = race.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) break;
                try {
                    JSONArray r = f.get();
                    if (r != null && r.length() > 0) return r.toString();
                } catch (Exception ignored) { }
            }
        } catch (InterruptedException ignored) {
        } finally {
            for (Future<JSONArray> f : jobs) f.cancel(true);
        }
        return null;
    }

    private static String get(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        if (url.contains("youtube.com")) c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI");
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static JSONObject video(String id, String title, String channel, String duration) throws Exception {
        return new JSONObject().put("videoId", id).put("title", title.isEmpty() ? "Unknown" : title).put("channel", channel)
                .put("duration", duration).put("thumb", "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg");
    }

    /** YouTube's own results page (videos only), read straight from the app. */
    private static JSONArray searchYouTube(String q) throws Exception {
        String html = get("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
                + "&sp=EgIQAQ%253D%253D&hl=en&gl=IN", 10000);
        Matcher m = Pattern.compile("ytInitialData\\s*=\\s*(\\{.+?\\});\\s*</script>", Pattern.DOTALL).matcher(html);
        if (!m.find()) throw new Exception("no data");
        JSONObject data = new JSONObject(m.group(1));
        JSONArray sections = data.getJSONObject("contents").getJSONObject("twoColumnSearchResultsRenderer")
                .getJSONObject("primaryContents").getJSONObject("sectionListRenderer").getJSONArray("contents");
        JSONArray out = new JSONArray();
        for (int i = 0; i < sections.length() && out.length() < 8; i++) {
            JSONObject isr = sections.getJSONObject(i).optJSONObject("itemSectionRenderer");
            if (isr == null) continue;
            JSONArray items = isr.optJSONArray("contents");
            if (items == null) continue;
            for (int j = 0; j < items.length() && out.length() < 8; j++) {
                JSONObject vr = items.getJSONObject(j).optJSONObject("videoRenderer");
                if (vr == null || vr.optString("videoId").isEmpty()) continue;
                StringBuilder title = new StringBuilder();
                JSONArray runs = vr.optJSONObject("title") == null ? null : vr.getJSONObject("title").optJSONArray("runs");
                if (runs != null) for (int k = 0; k < runs.length(); k++) title.append(runs.getJSONObject(k).optString("text"));
                String channel = "";
                JSONObject owner = vr.optJSONObject("ownerText");
                if (owner != null && owner.optJSONArray("runs") != null && owner.getJSONArray("runs").length() > 0)
                    channel = owner.getJSONArray("runs").getJSONObject(0).optString("text");
                String dur = vr.optJSONObject("lengthText") == null ? "" : vr.getJSONObject("lengthText").optString("simpleText");
                if (dur.isEmpty()) continue;                     // live streams and Shorts have no length here
                out.put(video(vr.getString("videoId"), title.toString(), channel, dur));
            }
        }
        if (out.length() == 0) throw new Exception("no results");
        return out;
    }

    /** A free Piped server. */
    private static JSONArray searchPiped(String api, String q) throws Exception {
        JSONObject j = new JSONObject(get(api + "/search?q=" + URLEncoder.encode(q, "UTF-8") + "&filter=videos", 8000));
        JSONArray items = j.optJSONArray("items");
        JSONArray out = new JSONArray();
        if (items == null) throw new Exception("no items");
        for (int i = 0; i < items.length() && out.length() < 8; i++) {
            JSONObject it = items.getJSONObject(i);
            if (!"stream".equals(it.optString("type")) || it.optBoolean("isShort")) continue;
            Matcher m = Pattern.compile("[?&]v=([\\w-]{11})").matcher(it.optString("url"));
            if (!m.find()) continue;
            long d = it.optLong("duration", -1);
            out.put(video(m.group(1), it.optString("title"), it.optString("uploaderName"), d > 0 ? fmt(d) : ""));
        }
        if (out.length() == 0) throw new Exception("no results");
        return out;
    }

    private static String fmt(long t) {
        long h = t / 3600, m = t % 3600 / 60, s = t % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
