package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Every text box in the app stops editing (the cursor goes, the keyboard closes) when the keyboard is put away
 * or when the user taps outside the box. What was typed stays. Pages in the Browser keep their own behaviour:
 * only the app's own text boxes are touched.
 */
final class TypeStop {
    private TypeStop() { }

    /** Once, from the Application: every screen of the app. */
    static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { watch(a.getWindow()); }     // after the screen has made its window
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    /** The app's dialogs (rename, new playlist, search again…). */
    static void watch(Dialog d) {
        if (d != null) d.setOnShowListener(x -> watch(d.getWindow()));
    }

    static void watch(Window w) {
        if (w == null) return;
        Window.Callback orig = w.getCallback();
        if (orig == null || Proxy.isProxyClass(orig.getClass())) return;
        final float slop = ViewConfiguration.get(w.getContext()).getScaledTouchSlop();
        final float[] down = new float[2];
        final View[] downFocus = new View[1];
        InvocationHandler h = (proxy, m, args) -> {
            if ("dispatchTouchEvent".equals(m.getName()) && args != null && args.length == 1 && args[0] instanceof MotionEvent) {
                MotionEvent e = (MotionEvent) args[0];
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    down[0] = e.getRawX(); down[1] = e.getRawY();
                    downFocus[0] = w.getCurrentFocus();
                }
                Object r = call(orig, m, args);
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    try { afterTap(w, e, down, downFocus[0], slop); } catch (Throwable ignored) { }
                    downFocus[0] = null;
                }
                return r;
            }
            return call(orig, m, args);
        };
        w.setCallback((Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class}, h));
        watchKeyboard(w);
    }

    private static Object call(Object target, java.lang.reflect.Method m, Object[] args) throws Throwable {
        try { return m.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause() == null ? e : e.getCause(); }
    }

    /** A tap (not a scroll) that ended outside the box being typed in. */
    private static void afterTap(Window w, MotionEvent e, float[] down, View before, float slop) {
        if (Math.abs(e.getRawX() - down[0]) > slop || Math.abs(e.getRawY() - down[1]) > slop) return;
        View f = w.getCurrentFocus();
        if (!(f instanceof EditText) || f != before) return;       // the tap moved the cursor to another box: leave it
        if (inside(f, e) || inside(bar(f), e)) return;
        stop(w, f);
    }

    /** A search bar or similar strip around the box (its clear and mic buttons count as part of it). */
    private static View bar(View f) {
        ViewParent p = f.getParent();
        if (!(p instanceof ViewGroup)) return null;
        View g = (View) p;
        return g.getHeight() > 0 && g.getHeight() <= f.getHeight() * 2 ? g : null;
    }

    private static boolean inside(View v, MotionEvent e) {
        if (v == null || !v.isShown()) return false;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        float x = e.getRawX(), y = e.getRawY();
        return x >= at[0] && x < at[0] + v.getWidth() && y >= at[1] && y < at[1] + v.getHeight();
    }

    /** The cursor goes and the keyboard closes. Focus moves to the screen itself, so it does not jump to another box. */
    static void stop(Window w, View f) {
        InputMethodManager im = (InputMethodManager) w.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(f.getWindowToken(), 0);
        View root = w.getDecorView().findViewById(android.R.id.content);
        if (root != null) {
            if (Build.VERSION.SDK_INT >= 26) root.setDefaultFocusHighlightEnabled(false);
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            if (!root.requestFocus()) f.clearFocus();
        } else f.clearFocus();
    }

    /** The keyboard went away (Back, the ⌄ key, swiping it down): the box stops editing too. */
    private static void watchKeyboard(Window w) {
        final View decor = w.getDecorView();
        final boolean[] shown = {false};
        final Runnable check = () -> {
            boolean now = keyboardShown(decor);
            if (shown[0] && !now) {
                View f = w.getCurrentFocus();
                if (f instanceof EditText) stop(w, f);
            }
            shown[0] = now;
        };
        decor.getViewTreeObserver().addOnGlobalLayoutListener(check::run);
        if (Build.VERSION.SDK_INT >= 30) {
            decor.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                @Override public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> running) { return insets; }
                @Override public void onEnd(WindowInsetsAnimation animation) {
                    if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) decor.post(check);
                }
            });
        }
    }

    private static boolean keyboardShown(View decor) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets in = decor.getRootWindowInsets();
            return in != null && in.isVisible(WindowInsets.Type.ime());
        }
        Rect r = new Rect();
        decor.getWindowVisibleDisplayFrame(r);
        int full = decor.getRootView().getHeight();
        return full > 0 && full - r.bottom > full * 0.15f;
    }
}
