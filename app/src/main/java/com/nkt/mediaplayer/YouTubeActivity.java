package com.nkt.mediaplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plays a YouTube link in YouTube's own embedded player. No downloading, no background play. */
public class YouTubeActivity extends Activity {
    private WebView web;
    private FrameLayout root;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCb;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String url = getIntent().getStringExtra("url");
        String id = url == null ? null : LinkDialog.youTubeId(url);
        String list = match(url, "[?&]list=([A-Za-z0-9_-]+)");
        if (id == null && list == null) { Ui.msg(this, "That YouTube link has no video in it"); finish(); return; }
        int start = parseStart(match(url, "[?&](?:t|start)=([0-9hms]+)"));
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                fullView = view; fullCb = cb;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            @Override public void onHideCustomView() { exitFull(); }
        });
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        String src = id != null
                ? "https://www.youtube.com/embed/" + id + "?autoplay=1&playsinline=1&rel=0&fs=1&start=" + start + (list != null ? "&list=" + list : "")
                : "https://www.youtube.com/embed/videoseries?list=" + list + "&autoplay=1&playsinline=1&rel=0&fs=1";
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000}iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style></head><body>"
                + "<iframe src='" + src + "' referrerpolicy='strict-origin-when-cross-origin' "
                + "allow='autoplay; encrypted-media; picture-in-picture; fullscreen' allowfullscreen></iframe></body></html>";
        web.loadDataWithBaseURL("https://com.nkt.mediaplayer/", html, "text/html", "utf-8", null);
    }

    private void exitFull() {
        if (fullView != null) { root.removeView(fullView); fullView = null; }
        if (fullCb != null) { fullCb.onCustomViewHidden(); fullCb = null; }
    }

    @Override public void onBackPressed() { if (fullView != null) exitFull(); else super.onBackPressed(); }
    @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }

    static String match(String s, String re) {
        if (s == null) return null;
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    static int parseStart(String t) {
        if (t == null) return 0;
        try {
            if (t.matches("\\d+")) return Integer.parseInt(t);
            int total = 0;
            Matcher m = Pattern.compile("(\\d+)([hms])").matcher(t);
            while (m.find()) {
                int v = Integer.parseInt(m.group(1));
                total += m.group(2).equals("h") ? v * 3600 : m.group(2).equals("m") ? v * 60 : v;
            }
            return total;
        } catch (Exception e) { return 0; }
    }
}
