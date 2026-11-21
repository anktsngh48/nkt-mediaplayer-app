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
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
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

import java.util.ArrayList;
import java.util.List;

/**
 * A web page inside the app (the search results under the Library header, and the full-screen Browser). Every site
 * opens here, links that would open another app (Facebook, Instagram, WhatsApp, Play Store…) stay here too, and pages
 * that open a new window open in this same one. Downloads go to Download/NKT Media Player; the microphone and camera
 * can be used by a page once Android allows them; sign-ins are kept until cleared in Settings.
 */
final class WebPane {

    static final String HOME = "https://duckduckgo.com/";
    static final int REQ_PERMS = 4601, REQ_FILES = 4602;

    final WebView web;
    private final Activity a;
    private PermissionRequest pendingPerm;
    private ValueCallback<Uri[]> pendingFiles;
    private View custom;                                // a page's own full-screen video
    private WebChromeClient.CustomViewCallback customDone;
    private View.OnScrollChangeListener scroll;

    static String searchUrl(String q) {
        try { return "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(q, "UTF-8"); }
        catch (Exception e) { return HOME; }
    }

    @SuppressLint("SetJavaScriptEnabled")
    WebPane(Activity a) {
        this.a = a;
        web = new WebView(a);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);                 // "new window" links open in this same page
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // the ordinary phone browser's name, so sites (and their sign-in pages) treat this like Chrome
        String ua = s.getUserAgentString();
        if (ua != null) s.setUserAgentString(ua.replace("; wv)", ")").replaceAll(" Version/[\\d.]+", ""));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return route(r.getUrl().toString()); }

            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return route(url); }

            @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                try { v.reload(); } catch (Throwable ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest r) { a.runOnUiThread(() -> askFor(r)); }

            @Override public void onPermissionRequestCanceled(PermissionRequest r) { if (pendingPerm == r) pendingPerm = null; }

            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) { cb.invoke(origin, false, false); }

            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = cb;
                try {
                    Intent i = p.createIntent();
                    if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    a.startActivityForResult(i, REQ_FILES);
                } catch (Throwable t) { pendingFiles = null; return false; }
                return true;
            }

            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                if (custom != null) { cb.onCustomViewHidden(); return; }
                custom = view;
                customDone = cb;
                ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
                FrameLayout holder = new FrameLayout(a);
                holder.setBackgroundColor(Color.BLACK);
                holder.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                holder.setTag("nkt_web_full");
                decor.addView(holder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                Ui.fullScreen(a);
            }

            @Override public void onHideCustomView() { leaveCustom(); }

            @Override public Bitmap getDefaultVideoPoster() { return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); }
        });
        web.setDownloadListener((url, agent, disposition, mime, length) -> download(url, agent, disposition, mime));
        web.setOnScrollChangeListener((v, x, y, ox, oy) -> { if (scroll != null) scroll.onScrollChange(v, x, y, ox, oy); });
    }

    void setOnScroll(View.OnScrollChangeListener l) { scroll = l; }

    void load(String url) {
        if (Net.off()) { Ui.toast(a, R.string.net_off_toast); return; }
        web.loadUrl(url);
    }

    /** Back inside the page: its full-screen video, then its history. False when there is nowhere to go back to. */
    boolean back() {
        if (custom != null) { leaveCustom(); return true; }
        if (web.canGoBack()) { web.goBack(); return true; }
        return false;
    }

    private void leaveCustom() {
        if (custom == null) return;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        View holder = decor.findViewWithTag("nkt_web_full");
        if (holder != null) decor.removeView(holder);
        custom = null;
        if (customDone != null) { try { customDone.onCustomViewHidden(); } catch (Throwable ignored) { } }
        customDone = null;
        if (a instanceof BrowserActivity) ((BrowserActivity) a).immersive();
        else Ui.showBars(a);
    }

    void onResume() { web.onResume(); }

    void onPause() { web.onPause(); CookieManager.getInstance().flush(); }

    void destroy() {
        try { leaveCustom(); } catch (Throwable ignored) { }
        try { ((ViewGroup) web.getParent()).removeView(web); } catch (Throwable ignored) { }
        try { web.stopLoading(); web.destroy(); } catch (Throwable ignored) { }
    }

    // ── Links: everything opens here ──

    /** True when the link was taken care of here (not loaded as it is). */
    private boolean route(String url) {
        if (url == null) return true;
        Uri u = Uri.parse(url);
        String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (sc.equals("http") || sc.equals("https") || sc.equals("about") || sc.equals("data") || sc.equals("blob") || sc.equals("javascript")) {
            if (Net.off() && (sc.equals("http") || sc.equals("https"))) { Ui.toast(a, R.string.net_off_toast); return true; }
            return false;
        }
        String web2 = null;
        if (sc.equals("intent")) {                          // an app link: its web address, opened here
            try {
                Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                web2 = i.getStringExtra("browser_fallback_url");
                if (web2 == null && i.getData() != null && ("http".equals(i.getData().getScheme()) || "https".equals(i.getData().getScheme())))
                    web2 = i.getDataString();
            } catch (Throwable ignored) { }
        } else if (sc.equals("market")) {
            String id = u.getQueryParameter("id");
            if (id != null) web2 = "https://play.google.com/store/apps/details?id=" + id;
        }
        if (web2 != null) web.loadUrl(web2);
        return true;                                        // other apps are never opened from here
    }

    // ── Microphone and camera for the page ──

    private void askFor(PermissionRequest r) {
        List<String> need = new ArrayList<>();
        List<String> grant = new ArrayList<>();
        for (String res : r.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) { grant.add(res); need.add(Manifest.permission.RECORD_AUDIO); }
            else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) { grant.add(res); need.add(Manifest.permission.CAMERA); }
            else if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) grant.add(res);
        }
        if (grant.isEmpty()) { r.deny(); return; }
        List<String> missing = new ArrayList<>();
        for (String p : need) if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (missing.isEmpty()) { r.grant(grant.toArray(new String[0])); return; }
        if (pendingPerm != null) { try { pendingPerm.deny(); } catch (Throwable ignored) { } }
        pendingPerm = r;
        a.requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    /** The answer to Android's microphone / camera question. True when it was this page's. */
    boolean onPermissionsResult(int code) {
        if (code != REQ_PERMS) return false;
        PermissionRequest r = pendingPerm;
        pendingPerm = null;
        if (r == null) return true;
        List<String> ok = new ArrayList<>();
        for (String res : r.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res) && a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ok.add(res);
            else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res) && a.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) ok.add(res);
            else if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) ok.add(res);
        }
        try { if (ok.isEmpty()) r.deny(); else r.grant(ok.toArray(new String[0])); } catch (Throwable ignored) { }
        return true;
    }

    /** Files picked for a page's upload button. True when it was this page's. */
    boolean onActivityResult(int code, int result, Intent data) {
        if (code != REQ_FILES) return false;
        ValueCallback<Uri[]> cb = pendingFiles;
        pendingFiles = null;
        if (cb == null) return true;
        Uri[] got = null;
        if (result == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                got = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < got.length; i++) got[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) got = new Uri[]{data.getData()};
        }
        cb.onReceiveValue(got);
        return true;
    }

    // ── Downloads: Download/NKT Media Player ──

    private void download(String url, String agent, String disposition, String mime) {
        String name = URLUtil.guessFileName(url, disposition, mime).replaceAll("[\\\\/:*?\"<>|]", "_");
        if (url.startsWith("data:")) { saveData(url, name, mime); return; }
        if (!url.startsWith("http://") && !url.startsWith("https://")) { Ui.toast(a, R.string.download_failed); return; }
        if (Net.off()) { Ui.toast(a, R.string.net_off_toast); return; }
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                    .setTitle(name)
                    .setDescription(a.getString(R.string.app_name))
                    .setMimeType(mime)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "NKT Media Player/" + name);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) r.addRequestHeader("Cookie", cookies);
            if (agent != null) r.addRequestHeader("User-Agent", agent);
            r.addRequestHeader("Referer", web.getUrl() == null ? url : web.getUrl());
            ((DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Ui.toast(a, a.getString(R.string.download_started, name));
        } catch (Throwable t) {
            Ui.toast(a, R.string.download_failed);
        }
    }

    /** A file the page made itself (a data: link), saved straight away. */
    private void saveData(String url, String name, String mime) {
        new Thread(() -> {
            boolean ok = false;
            try {
                int comma = url.indexOf(',');
                String meta = url.substring(5, comma);
                byte[] bytes = meta.endsWith(";base64") ? android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT)
                        : Uri.decode(url.substring(comma + 1)).getBytes("UTF-8");
                String type = meta.split(";")[0];
                if (type.isEmpty()) type = mime == null ? "application/octet-stream" : mime;
                java.io.OutputStream os;
                Uri pending = null;
                if (Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, type);
                    cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NKT Media Player");
                    cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                    pending = a.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (pending == null) throw new java.io.IOException("no place");
                    os = a.getContentResolver().openOutputStream(pending);
                } else {
                    java.io.File dir = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NKT Media Player");
                    if (!dir.isDirectory()) dir.mkdirs();
                    os = new java.io.FileOutputStream(new java.io.File(dir, name));
                }
                try (java.io.OutputStream o = os) { o.write(bytes); }
                if (pending != null) {
                    android.content.ContentValues done = new android.content.ContentValues();
                    done.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                    a.getContentResolver().update(pending, done, null, null);
                }
                ok = true;
            } catch (Throwable ignored) { }
            final boolean fine = ok;
            a.runOnUiThread(() -> Ui.toast(a, fine ? a.getString(R.string.listen_saved, name) : a.getString(R.string.download_failed)));
        }, "nkt-web-save").start();
    }

    // ── Settings: clear the browser ──

    /** Cache, cookies, sign-ins, saved form data and site storage of every page in the app. Main thread. */
    @SuppressWarnings("deprecation")
    static void clearAll(Context c) {
        try { WebStorage.getInstance().deleteAllData(); } catch (Throwable ignored) { }
        try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        try { GeolocationPermissions.getInstance().clearAll(); } catch (Throwable ignored) { }
        try {
            WebViewDatabase db = WebViewDatabase.getInstance(c);
            db.clearHttpAuthUsernamePassword();
            db.clearFormData();
        } catch (Throwable ignored) { }
        try { WebView w = new WebView(c); w.clearCache(true); w.clearHistory(); w.clearFormData(); w.destroy(); } catch (Throwable ignored) { }
    }
}
