package com.nkt.mediaplayer;

import android.content.Context;

/** Settings → INTERNET & PERMISSIONS → "Do not use internet": one switch every online feature checks. */
final class Net {

    private Net() { }

    static volatile Context app;

    static void init(Context c) { app = c.getApplicationContext(); }

    /** True when the user turned the internet off for this app. */
    static boolean off() {
        Context c = app;
        return c != null && Prefs.noInternet(c);
    }
}
