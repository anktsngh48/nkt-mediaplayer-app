package com.nkt.mediaplayer;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Home-screen widget 4×3. */
public class PlayerWidgetLarge extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { WidgetUpdater.update(c); }

    /** The launcher gave the widget a new size: draw the picture to fit it. */
    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, android.os.Bundle options) { WidgetUpdater.update(c); }
}
