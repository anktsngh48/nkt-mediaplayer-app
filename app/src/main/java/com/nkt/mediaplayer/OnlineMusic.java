package com.nkt.mediaplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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
 * and listens right on the page (the phone's speech recognizer, with no pop-up). Sound starts without an extra tap.
 */
public final class OnlineMusic {

    public static final int REQ_MIC = 4502;
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
    private SpeechRecognizer rec;
    private boolean listening = false;
    private long lastLevel = 0;

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
        if (Net.off()) {                                            // Settings: "Do not use internet"
            reset();
            android.widget.TextView t = new android.widget.TextView(a);
            t.setText(R.string.net_off_online);
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(Ui.dp(a, 32), Ui.dp(a, 32), Ui.dp(a, 32), Ui.dp(a, 32));
            t.setTextColor(a.getColor(R.color.muted));
            t.setTextSize(14f);
            box.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        if (web == null) box.removeAllViews();                     // (the message, if it was showing)
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

    /** Throws the page away (internet turned off); it is made again the next time it is shown. */
    public void reset() {
        pause();
        if (web != null) { try { web.loadUrl("about:blank"); web.destroy(); } catch (Throwable ignored) { } web = null; }
        box.removeAllViews();
    }

    /** Leaving the page: YouTube songs can't keep playing in the background. */
    public void hide() {
        pause();
        box.setVisibility(View.GONE);
    }

    public void pause() {
        if (web != null) web.evaluateJavascript("window.NKTpause && NKTpause()", null);
    }

    public void onActivityPause() { cancelListening(); pause(); if (web != null) web.onPause(); }

    public void onActivityResume(boolean visible) { if (web != null && visible) web.onResume(); }

    public void destroy() {
        pool.shutdownNow();
        if (rec != null) { rec.destroy(); rec = null; }
        unmuteBeep();
        if (web != null) { web.destroy(); web = null; }
    }

    // ── Voice ────────────────────────────────────────────────────────────────

    /** The microphone permission answer comes back through the activity. Returns true when it was ours. */
    public boolean onRequestPermissionsResult(int requestCode, int[] results) {
        if (requestCode != REQ_MIC) return false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startListening();
        else if (a.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) voiceError(R.string.online_mic_needed);
        else voiceError(R.string.online_mic_blocked);
        return true;
    }

    private void startListening() {
        if (web == null) return;
        if (!SpeechRecognizer.isRecognitionAvailable(a)) { voiceError(R.string.online_no_voice); return; }
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(a);
            rec.setRecognitionListener(new Listener());
        }
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, a.getPackageName());
        listening = true;
        muteBeep();
        try { rec.startListening(i); }
        catch (Exception e) { listening = false; unmuteBeep(); voiceError(R.string.online_no_voice); }
    }

    // ── No beep when listening starts ──

    private final java.util.List<Integer> muted = new java.util.ArrayList<>();
    private final Runnable unmute = this::unmuteBeep;

    /** The phone's speech service plays a beep as it starts; those sounds are muted for that moment only. */
    private void muteBeep() {
        unmuteBeep();
        android.media.AudioManager am = (android.media.AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE);
        if (am == null) return;
        java.util.List<Integer> streams = new java.util.ArrayList<>(java.util.Arrays.asList(
                android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.STREAM_SYSTEM));
        if (!am.isMusicActive()) streams.add(android.media.AudioManager.STREAM_MUSIC);     // never cut into a song that is playing
        for (int st : streams) {
            try {
                if (am.isStreamMute(st)) continue;
                am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_MUTE, 0);
                muted.add(st);
            } catch (Throwable ignored) { }                   // Do Not Disturb may not allow it: then there is nothing to do
        }
        ui.postDelayed(unmute, 4000);                         // the sound always comes back, even if the service never answers
    }

    private void unmuteBeep() {
        ui.removeCallbacks(unmute);
        if (muted.isEmpty()) return;
        android.media.AudioManager am = (android.media.AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE);
        for (int st : muted) {
            try { if (am != null) am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_UNMUTE, 0); } catch (Throwable ignored) { }
        }
        muted.clear();
    }

    /** "Done speaking": the recognizer turns what it heard so far into a result. */
    private void stopListening() {
        if (rec != null && listening) rec.stopListening();
    }

    /** Leaving the page or the app: drop the mic quietly. */
    private void cancelListening() {
        if (rec != null && listening) {
            listening = false;
            rec.cancel();
            unmuteBeep();
            js("window.NKTvoiceStopped && NKTvoiceStopped()");
        }
    }

    private void voiceError(int msg) {
        listening = false;
        js("NKTvoiceError(" + JSONObject.quote(a.getString(msg)) + ")");
    }

    private final class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle b) {
            js("NKTlistening()");
            ui.removeCallbacks(unmute);
            ui.postDelayed(unmute, 700);                      // the beep plays just as it gets ready
        }
        @Override public void onBeginningOfSpeech() { unmuteBeep(); }
        @Override public void onRmsChanged(float rms) {
            long now = System.currentTimeMillis();
            if (now - lastLevel < 70) return;                 // about 14 updates a second is plenty for the bars
            lastLevel = now;
            float v = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
            js("NKTlevel(" + String.format(Locale.ROOT, "%.2f", v) + ")");
        }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { js("NKTlevel(0)"); }
        @Override public void onError(int error) {
            unmuteBeep();
            if (!listening) return;                           // we cancelled it ourselves
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: voiceError(R.string.online_no_speech); break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: voiceError(R.string.online_mic_needed); break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER: voiceError(R.string.online_voice_offline); break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    if (rec != null) { rec.destroy(); rec = null; }   // a stuck recognizer: start fresh next time
                    voiceError(R.string.online_voice_busy); break;
                default: voiceError(R.string.online_no_speech);
            }
        }
        @Override public void onResults(Bundle b) {
            unmuteBeep();
            listening = false;
            String text = first(b);
            if (text == null) js("NKTvoiceError(" + JSONObject.quote(a.getString(R.string.online_no_speech)) + ")");
            else js("NKTvoice(" + JSONObject.quote(text) + ")");
        }
        @Override public void onPartialResults(Bundle b) {
            String text = first(b);
            if (text != null) js("NKTpartial(" + JSONObject.quote(text) + ")");
        }
        @Override public void onEvent(int type, Bundle b) { }

        private String first(Bundle b) {
            List<String> l = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l == null || l.isEmpty() || l.get(0) == null || l.get(0).trim().isEmpty()) return null;
            return l.get(0).trim();
        }
    }

    private void js(String code) { ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    final class Bridge {
        @JavascriptInterface
        public void listen() { ui.post(OnlineMusic.this::startListening); }

        @JavascriptInterface
        public void stopListening() { ui.post(OnlineMusic.this::stopListening); }

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

        /** The playlist of the video that just started: YouTube's own mix for it (or its up-next list). */
        @JavascriptInterface
        public void related(String videoId, String title, int seq) {
            if (videoId == null || !videoId.matches("[\\w-]{11}")) return;
            pool.execute(() -> {
                String json = cache.get("related:" + videoId);
                if (json == null) json = relatedAll(videoId);
                if (json == null && title != null && !title.trim().isEmpty()) json = searchAll(title);    // last resort: songs like it
                if (json != null) {
                    cache.put("related:" + videoId, json);
                    js("NKTrelated(" + seq + "," + JSONObject.quote(videoId) + "," + JSONObject.quote(json) + ")");
                } else js("NKTrelatedFailed(" + seq + ")");
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

    // ── The playing video's playlist: YouTube's mix, its up-next list, or a Piped server's related videos ──

    private String relatedAll(String id) {
        ExecutorCompletionService<JSONArray> race = new ExecutorCompletionService<>(pool);
        List<Future<JSONArray>> jobs = new ArrayList<>();
        jobs.add(race.submit(() -> nextApi(id, true)));
        jobs.add(race.submit(() -> nextApi(id, false)));
        jobs.add(race.submit(() -> mixYouTube(id)));
        jobs.add(race.submit(() -> upNextYouTube(id)));
        for (String api : PIPED) jobs.add(race.submit(() -> relatedPiped(api, id)));
        long until = System.currentTimeMillis() + 12000;
        JSONArray second = null;
        try {
            for (int i = 0; i < jobs.size(); i++) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                Future<JSONArray> f = race.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) break;
                try {
                    JSONArray r = f.get();
                    if (r == null || r.length() == 0) continue;
                    if (r.optJSONObject(0) != null && r.optJSONObject(0).optBoolean("mix")) return strip(r).toString();   // the mix comes first
                    if (second == null) second = r;
                    if (jobs.get(0).isDone() && jobs.get(2).isDone()) return strip(r).toString();
                } catch (Exception ignored) { }
            }
        } catch (InterruptedException ignored) {
        } finally {
            for (Future<JSONArray> f : jobs) f.cancel(true);
        }
        return second == null ? null : strip(second).toString();
    }

    private static JSONArray strip(JSONArray a) {
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) o.remove("mix"); }
        return a;
    }

    private static JSONObject ytData(String html) throws Exception {
        Matcher m = Pattern.compile("ytInitialData\\s*=\\s*(\\{.+?\\});\\s*</script>", Pattern.DOTALL).matcher(html);
        if (!m.find()) throw new Exception("no data");
        return new JSONObject(m.group(1));
    }

    private static String text(JSONObject o) {
        if (o == null) return "";
        if (o.has("simpleText")) return o.optString("simpleText");
        JSONArray runs = o.optJSONArray("runs");
        StringBuilder b = new StringBuilder();
        for (int k = 0; runs != null && k < runs.length(); k++) b.append(runs.optJSONObject(k) == null ? "" : runs.optJSONObject(k).optString("text"));
        return b.toString();
    }

    /**
     * YouTube's own "up next" service (what its website asks): the video's Mix (withMix) or the videos beside it.
     * Reads both the older and the newer ways YouTube describes a video.
     */
    private static JSONArray nextApi(String id, boolean withMix) throws Exception {
        JSONObject body = new JSONObject()
                .put("context", new JSONObject().put("client", new JSONObject().put("clientName", "WEB").put("clientVersion", "2.20251101.01.00")
                        .put("hl", "en").put("gl", "IN")))
                .put("videoId", id);
        if (withMix) body.put("playlistId", "RD" + id);
        JSONObject data = new JSONObject(post("https://www.youtube.com/youtubei/v1/next?prettyPrint=false", body.toString(), 10000));
        JSONObject two = data.getJSONObject("contents").getJSONObject("twoColumnWatchNextResults");
        JSONArray out = new JSONArray();
        if (withMix) {
            JSONObject pl = two.optJSONObject("playlist");
            JSONArray items = pl == null || pl.optJSONObject("playlist") == null ? null : pl.getJSONObject("playlist").optJSONArray("contents");
            for (int i = 0; items != null && i < items.length() && out.length() < 30; i++) {
                JSONObject r = items.getJSONObject(i).optJSONObject("playlistPanelVideoRenderer");
                if (r == null || r.optString("videoId").isEmpty()) continue;
                String dur = text(r.optJSONObject("lengthText"));
                String ch = text(r.optJSONObject("shortBylineText"));
                if (ch.isEmpty()) ch = text(r.optJSONObject("longBylineText"));
                out.put(video(r.getString("videoId"), text(r.optJSONObject("title")), ch, dur).put("mix", true));
            }
            if (out.length() == 0) throw new Exception("no mix");
            return out;
        }
        out.put(new JSONObject().put("videoId", id).put("self", true));
        JSONObject sr = two.optJSONObject("secondaryResults");
        JSONArray items = sr == null || sr.optJSONObject("secondaryResults") == null ? null : sr.getJSONObject("secondaryResults").optJSONArray("results");
        for (int i = 0; items != null && i < items.length() && out.length() < 25; i++) {
            JSONObject it = items.getJSONObject(i);
            JSONObject c = it.optJSONObject("compactVideoRenderer");
            if (c != null && !c.optString("videoId").isEmpty()) {
                String dur = text(c.optJSONObject("lengthText"));
                if (!dur.isEmpty()) out.put(video(c.getString("videoId"), text(c.optJSONObject("title")), text(c.optJSONObject("longBylineText")), dur));
                continue;
            }
            JSONObject l = it.optJSONObject("lockupViewModel");                    // the newer way
            if (l == null || !"LOCKUP_CONTENT_TYPE_VIDEO".equals(l.optString("contentType")) || l.optString("contentId").length() != 11) continue;
            JSONObject md = l.optJSONObject("metadata") == null ? null : l.getJSONObject("metadata").optJSONObject("lockupMetadataViewModel");
            String title = md == null || md.optJSONObject("title") == null ? "" : md.getJSONObject("title").optString("content");
            String ch = "";
            try {
                ch = md.getJSONObject("metadata").getJSONObject("contentMetadataViewModel").getJSONArray("metadataRows")
                        .getJSONObject(0).getJSONArray("metadataParts").getJSONObject(0).getJSONObject("text").optString("content");
            } catch (Exception ignored) { }
            String dur = findDuration(l.optJSONObject("contentImage"));
            if (dur.isEmpty()) continue;                                         // live streams and Shorts: not songs
            out.put(video(l.getString("contentId"), title, ch, dur));
        }
        if (out.length() <= 1) throw new Exception("no up next");
        return out;
    }

    /** The "3:45" badge somewhere inside a video's picture description. */
    private static String findDuration(Object o) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String t = j.optString("text", "");
            if (t.matches("\\d{1,2}(:\\d{2}){1,2}")) return t;
            JSONArray names = j.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String r = findDuration(j.opt(names.optString(i)));
                if (!r.isEmpty()) return r;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) { String r = findDuration(a.opt(i)); if (!r.isEmpty()) return r; }
        }
        return "";
    }

    private static String post(String url, String json, int timeoutMs) throws Exception {
        if (Net.off()) throw new java.io.IOException("Internet is turned off");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        c.setRequestProperty("Origin", "https://www.youtube.com");
        c.setRequestProperty("X-YouTube-Client-Name", "1");
        c.setRequestProperty("X-YouTube-Client-Version", "2.20251101.01.00");
        c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI");
        try {
            try (java.io.OutputStream os = c.getOutputStream()) { os.write(json.getBytes("UTF-8")); }
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    /** YouTube's own "Mix" playlist for the video (the list that plays next on YouTube). */
    private static JSONArray mixYouTube(String id) throws Exception {
        JSONObject data = ytData(get("https://www.youtube.com/watch?v=" + id + "&list=RD" + id + "&hl=en&gl=IN", 10000));
        JSONArray items = data.getJSONObject("contents").getJSONObject("twoColumnWatchNextResults")
                .getJSONObject("playlist").getJSONObject("playlist").getJSONArray("contents");
        JSONArray out = new JSONArray();
        for (int i = 0; i < items.length() && out.length() < 30; i++) {
            JSONObject r = items.getJSONObject(i).optJSONObject("playlistPanelVideoRenderer");
            if (r == null || r.optString("videoId").isEmpty()) continue;
            String dur = text(r.optJSONObject("lengthText"));
            String ch = text(r.optJSONObject("shortBylineText"));
            if (ch.isEmpty()) ch = text(r.optJSONObject("longBylineText"));
            out.put(video(r.getString("videoId"), text(r.optJSONObject("title")), ch, dur).put("mix", true));
        }
        if (out.length() == 0) throw new Exception("no mix");
        return out;
    }

    /** The videos YouTube lists beside the video ("Up next"). */
    private static JSONArray upNextYouTube(String id) throws Exception {
        JSONObject data = ytData(get("https://www.youtube.com/watch?v=" + id + "&hl=en&gl=IN", 10000));
        JSONArray items = data.getJSONObject("contents").getJSONObject("twoColumnWatchNextResults")
                .getJSONObject("secondaryResults").getJSONObject("secondaryResults").getJSONArray("results");
        JSONArray out = new JSONArray();
        out.put(new JSONObject().put("videoId", id).put("self", true));
        for (int i = 0; i < items.length() && out.length() < 25; i++) {
            JSONObject r = items.getJSONObject(i).optJSONObject("compactVideoRenderer");
            if (r == null || r.optString("videoId").isEmpty()) continue;
            String dur = text(r.optJSONObject("lengthText"));
            if (dur.isEmpty()) continue;
            out.put(video(r.getString("videoId"), text(r.optJSONObject("title")), text(r.optJSONObject("longBylineText")), dur));
        }
        if (out.length() <= 1) throw new Exception("no up next");
        return out;
    }

    /** A free Piped server's related videos. */
    private static JSONArray relatedPiped(String api, String id) throws Exception {
        JSONObject j = new JSONObject(get(api + "/streams/" + id, 8000));
        JSONArray items = j.optJSONArray("relatedStreams");
        if (items == null) throw new Exception("no related");
        JSONArray out = new JSONArray();
        out.put(new JSONObject().put("videoId", id).put("self", true));
        for (int i = 0; i < items.length() && out.length() < 25; i++) {
            JSONObject it = items.getJSONObject(i);
            if (!"stream".equals(it.optString("type")) || it.optBoolean("isShort")) continue;
            Matcher m = Pattern.compile("[?&]v=([\\w-]{11})").matcher(it.optString("url"));
            if (!m.find()) continue;
            long d = it.optLong("duration", -1);
            if (d <= 0) continue;
            out.put(video(m.group(1), it.optString("title"), it.optString("uploaderName"), fmt(d)));
        }
        if (out.length() <= 1) throw new Exception("no related");
        return out;
    }

    private static String get(String url, int timeoutMs) throws Exception {
        if (Net.off()) throw new java.io.IOException("Internet is turned off");
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
