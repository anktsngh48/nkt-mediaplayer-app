package com.nkt.mediaplayer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Web pages inside the app.
 * <ul>
 * <li>Framed ("Search on internet"): under the NKT MEDIA PLAYER bar, which hides as you scroll down and comes back as you scroll up.</li>
 * <li>Full screen (the Browser in the menu, and links opened from other apps): only the page, no text and no buttons.</li>
 * </ul>
 * Every link, Facebook and Instagram ones included, stays here in the same tab; downloads go to
 * Download/NKT Media Player; sites may use the microphone and camera once Android allows it.
 */
public class WebActivity extends Activity {

    public static final String EXTRA_QUERY = "query";        // search this on duckduckgo.com, framed
    public static final String EXTRA_FULL = "full";          // the full-screen Browser
    private static final String HOME = "https://duckduckgo.com/";
    private static final int REQ_FILE = 41, REQ_PERMS = 42;

    private WebView web;
    private View header;
    private TextView site;
    private ProgressBar progress;
    private boolean full;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private FrameLayout root;

    private int dp(float v) { return Ui.dp(this, v); }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        boolean fromLink = in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null;
        full = fromLink || (in != null && in.getBooleanExtra(EXTRA_FULL, false));

        if (Prefs.noInternet(this)) {
            Ui.toastApp(this, getString(R.string.net_off_toast));
            finish();
            return;
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(full ? Color.WHITE : getColor(R.color.bg));
        setContentView(root);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (!full) {
            header = buildHeader();
            col.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
            progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x2600E5FF));
            col.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
            Ui.padForSystemBars(root);
        } else {
            Ui.fullScreen(this);
        }

        web = new WebView(this);
        col.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setUp(web);

        if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) return;
        web.loadUrl(startUrl(in, fromLink));
    }

    private String startUrl(Intent in, boolean fromLink) {
        if (fromLink) return in.getData().toString();
        String q = in == null ? null : in.getStringExtra(EXTRA_QUERY);
        if (q != null && !q.trim().isEmpty()) {
            try { return HOME + "?q=" + URLEncoder.encode(q.trim(), "UTF-8"); } catch (Exception e) { return HOME; }
        }
        return HOME;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null && web != null)
            web.loadUrl(intent.getData().toString());                 // another link from another app: same tab
    }

    // ── The bar (framed pages only) ──

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getColor(R.color.bg));
        bar.setElevation(dp(4));
        bar.setPadding(dp(6), dp(8), dp(8), dp(6));
        ImageButton back = new ImageButton(this);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(getColor(R.color.accent));
        back.setContentDescription(getString(R.string.cd_back));
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout heads = new LinearLayout(this);
        heads.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.header);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(getColor(R.color.accent));
        title.setTypeface(Ui.orbitronBlack(this));
        title.setLetterSpacing(0.08f);
        title.setSingleLine(true);
        Ui.gradient(title);
        heads.addView(title);
        site = new TextView(this);
        site.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        site.setTextColor(getColor(R.color.muted));
        site.setLetterSpacing(0.16f);
        site.setSingleLine(true);
        site.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heads.addView(site);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hl.setMarginStart(dp(6));
        bar.addView(heads, hl);
        ImageButton close = new ImageButton(this);
        close.setBackgroundResource(R.drawable.bg_round_btn);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(getColor(R.color.accent));
        close.setContentDescription(getString(R.string.web_close));
        close.setOnClickListener(v -> finish());
        bar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return bar;
    }

    private int lastY = 0;

    /** The bar slides away when you scroll down and comes back when you scroll up (or reach the top). */
    private void onScrolled(int y) {
        if (header == null) return;
        int dy = y - lastY;
        lastY = y;
        if (Math.abs(dy) < dp(4)) return;
        boolean show = dy < 0 || y < dp(8);
        if (show && header.getVisibility() != View.VISIBLE) header.setVisibility(View.VISIBLE);
        else if (!show && header.getVisibility() == View.VISIBLE) header.setVisibility(View.GONE);
    }

    // ── The page ──

    @SuppressLint("SetJavaScriptEnabled")
    private void setUp(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);               // "open in new tab" comes to onCreateWindow: it opens here instead
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);          // logins (Facebook, Google…) need these

        w.setOnScrollChangeListener((v, x, y, ox, oy) -> onScrolled(y));

        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return route(view, req.getUrl());
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) { showSite(url); }
            @Override public void onPageFinished(WebView view, String url) { showSite(url); CookieManager.getInstance().flush(); }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                if (progress == null) return;
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean user, Message msg) {
                // a new tab or window: catch its address and open it in this same tab
                WebView probe = new WebView(WebActivity.this);
                probe.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        if (!route(web, r.getUrl())) web.loadUrl(r.getUrl().toString());
                        v.destroy();
                        return true;
                    }
                });
                WebView.WebViewTransport t = (WebView.WebViewTransport) msg.obj;
                t.setWebView(probe);
                msg.sendToTarget();
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) { askPermissions(request); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Throwable t) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {        // a video in full screen
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCallback = cb;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                Ui.fullScreen(WebActivity.this);
            }
            @Override public void onHideCustomView() { hideCustom(); }
        });

        w.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String ua, String disposition, String mime, long length) {
                download(url, ua, disposition, mime);
            }
        });
    }

    private void hideCustom() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        if (!full) Ui.exitFullScreen(this);
    }

    private void showSite(String url) {
        if (site == null || url == null) return;
        String h = Uri.parse(url).getHost();
        if (h != null && h.startsWith("www.")) h = h.substring(4);
        site.setText(h == null ? "" : h.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Where a tapped link goes. Web addresses (Facebook, Instagram and every other site too) stay in this tab.
     * App links (intent:, fb:, instagram:…) open their web address here when they have one, and are otherwise ignored.
     * Phone numbers, e-mail and text messages go to the phone's own apps, because a web page can't place a call.
     */
    private boolean route(WebView view, Uri u) {
        String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (sc.equals("http") || sc.equals("https") || sc.equals("about") || sc.equals("data") || sc.equals("blob")) return false;
        if (sc.equals("tel") || sc.equals("mailto") || sc.equals("sms") || sc.equals("smsto")) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Throwable t) { Ui.toastApp(this, getString(R.string.web_no_app)); }
            return true;
        }
        if (sc.equals("intent")) {
            try {
                Intent i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                String fb = i.getStringExtra("browser_fallback_url");
                if (fb != null && fb.startsWith("http")) { view.loadUrl(fb); return true; }
                Uri d = i.getData();
                if (d != null && d.getScheme() != null && d.getScheme().startsWith("http")) { view.loadUrl(d.toString()); return true; }
            } catch (Throwable ignored) { }
        }
        return true;                                    // never handed to another app
    }

    // ── Downloads: Download/NKT Media Player ──

    private void download(String url, String ua, String disposition, String mime) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            Ui.toastApp(this, getString(R.string.web_no_app));
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, disposition, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (mime != null) r.setMimeType(mime);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "NKT Media Player/" + name);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(r);
            Ui.toastApp(this, getString(R.string.web_saving, name));
        } catch (Throwable t) {
            Ui.toastApp(this, getString(R.string.web_no_app));
        }
    }

    // ── Microphone and camera for sites ──

    private void askPermissions(PermissionRequest request) {
        List<String> need = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.RECORD_AUDIO);
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.CAMERA);
        }
        if (need.isEmpty()) { request.grant(request.getResources()); return; }
        pendingPermission = request;
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS || pendingPermission == null) return;
        List<String> ok = new ArrayList<>();
        for (String r : pendingPermission.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ok.add(r);
            else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) ok.add(r);
            else if (!PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) ok.add(r);
        }
        if (ok.isEmpty()) pendingPermission.deny(); else pendingPermission.grant(ok.toArray(new String[0]));
        pendingPermission = null;
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_FILE || fileCallback == null) return;
        fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
        fileCallback = null;
    }

    // ── Back, pause, keep the page ──

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) { hideCustom(); return; }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        if (full) Ui.fullScreen(this);
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }

    /** Settings → Clear browser data: cache, cookies (logins), saved form data and site storage. */
    @SuppressWarnings("deprecation")
    public static void clearData(Context c) {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            WebStorage.getInstance().deleteAllData();
            WebViewDatabase db = WebViewDatabase.getInstance(c);
            db.clearHttpAuthUsernamePassword();
            db.clearFormData();
            WebView w = new WebView(c.getApplicationContext());
            w.clearCache(true);
            w.clearFormData();
            w.clearHistory();
            w.destroy();
        } catch (Throwable ignored) { }
    }
}
