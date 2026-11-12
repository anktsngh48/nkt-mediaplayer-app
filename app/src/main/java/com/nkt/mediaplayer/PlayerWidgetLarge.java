package com.nkt.mediaplayer;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Home-screen widget 4×4. */
public class PlayerWidgetLarge extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { WidgetUpdater.update(c); }
}
