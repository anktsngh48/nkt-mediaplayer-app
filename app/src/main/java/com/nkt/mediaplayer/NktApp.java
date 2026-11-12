package com.nkt.mediaplayer;

import android.app.Application;

/** Starts first in every part of the app (screens, player service, widgets). */
public class NktApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Net.init(this);
    }
}
