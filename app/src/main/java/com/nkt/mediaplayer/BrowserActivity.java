package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Browser: the whole screen is the web page, with no bar, text or buttons, also under the status bar and the camera
 * cut-out. Opened from ☰ → Browser, or for any link chosen in another app (NKT Media Player is in "Open with" for links).
 * A new link opens in the same page. Back goes back through the pages, then closes.
 */
public class BrowserActivity extends Activity {

    private WebPane pane;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        pane = new WebPane(this);
        root.addView(pane.web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        immersive();
        String url = linkOf(getIntent());
        if (savedInstanceState != null) pane.web.restoreState(savedInstanceState);
        if (savedInstanceState == null || pane.web.getUrl() == null) pane.load(url == null ? WebPane.HOME : url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = linkOf(intent);
        if (url != null) pane.load(url);                    // the same page, not a new tab
    }

    private static String linkOf(Intent in) {
        if (in == null) return null;
        Uri u = in.getData();
        if (u != null && u.getScheme() != null) {
            String s = u.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (s.equals("http") || s.equals("https")) return u.toString();
        }
        String text = in.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) return text.trim();
        return null;
    }

    /** No status bar and no navigation bar: the page uses the whole screen (a swipe from the edge shows them for a moment). */
    @SuppressWarnings("deprecation")
    void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (pane.back()) return;
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        pane.web.saveState(out);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        pane.onPermissionsResult(requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        pane.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pane.onResume();
    }

    @Override
    protected void onPause() {
        pane.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        pane.destroy();
        super.onDestroy();
    }
}
