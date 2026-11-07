package com.nkt.mediaplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * YouTube links play in YouTube's own official player (the embed player), as YouTube requires.
 * Full screen, rotation follows the phone. Links inside the player don't open anything else.
 */
public class YouTubeActivity extends Activity {

    private static final String EXTRA_URL = "url";

    public static void open(Context c, String url) {
        c.startActivity(new Intent(c, YouTubeActivity.class).putExtra(EXTRA_URL, url));
    }

    private WebView web;
    private FrameLayout root;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        Ui.fullScreen(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(0xFF000000);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true;            // stay here: no jumping out to the YouTube app or browser
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                fullView = view;
                fullCallback = cb;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                Ui.fullScreen(YouTubeActivity.this);
            }
            @Override public void onHideCustomView() {
                if (fullView != null) root.removeView(fullView);
                fullView = null;
                web.setVisibility(View.VISIBLE);
                if (fullCallback != null) fullCallback.onCustomViewHidden();
                fullCallback = null;
                Ui.fullScreen(YouTubeActivity.this);
            }
        });

        String url = getIntent().getStringExtra(EXTRA_URL);
        String id = YouTube.videoId(url), list = YouTube.playlistId(url);
        if (id == null && list == null) {
            Ui.toast(getApplicationContext(), getString(R.string.youtube_bad_link));
            finish();
            return;
        }
        String src = "https://www.youtube.com/embed/" + (id != null ? id + "?" : "videoseries?")
                + (list != null ? "list=" + Uri.encode(list) + "&" : "")
                + "autoplay=1&playsinline=1&rel=0&fs=1&start=" + YouTube.startSeconds(url);
        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                + "iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head><body>"
                + "<iframe src='" + src + "' referrerpolicy='strict-origin-when-cross-origin'"
                + " allow='autoplay; encrypted-media; picture-in-picture; fullscreen' allowfullscreen></iframe>"
                + "</body></html>";
        // YouTube's embed player needs to know which app it is in (sent as the page's origin).
        web.loadDataWithBaseURL("https://com.nkt.mediaplayer/", html, "text/html", "utf-8", null);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.fullScreen(this);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (fullView != null) { if (fullCallback != null) fullCallback.onCustomViewHidden(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();      // YouTube doesn't allow playing in the background
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.loadUrl("about:blank"); web.destroy(); web = null; }
        super.onDestroy();
    }
}
