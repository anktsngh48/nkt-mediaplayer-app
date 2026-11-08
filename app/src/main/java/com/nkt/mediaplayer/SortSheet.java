package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * SORT & VIEW sheet. Every page starts with the same four: A–Z, Z–A, Newest first, Oldest first;
 * then the extras for that page, an optional "Folders first" switch and the view buttons.
 */
public final class SortSheet {

    private SortSheet() { }

    public static final String[] SHARED = {"name", "name_desc", "new", "old"};

    public interface Listener {
        void onSort(String key);
        void onView(String view);
        default void onFoldersFirst(boolean on) { }
    }

    /** Extras per page, after the shared four. */
    public static String[] extras(String page) {
        switch (page) {
            case "songs": return new String[]{"artist", "album", "long", "short", "large", "plays"};
            case "videos": return new String[]{"long", "short", "large", "small"};
            case "gallery": return new String[]{"large", "small"};
            case "folders": return new String[]{"large", "small"};
            case "videofolders": case "musicfolders": return new String[]{"most", "large"};
            case "playlists": return new String[]{"most"};
            case "playlist": return new String[]{"order", "artist", "long", "short"};
            case "remote": return new String[]{"large", "small"};
        }
        return new String[0];
    }

    /** View choices per page. */
    public static String[] views(String page) {
        switch (page) {
            case "songs": return new String[]{"list", "grid", "squares"};
            case "videos": case "videofolders": return new String[]{"list", "grid", "folders"};
            case "folders": case "playlists": case "remote": case "playlist": return new String[]{"list", "grid"};
        }
        return new String[0];
    }

    public static String label(Activity a, String k) {
        switch (k) {
            case "name": return a.getString(R.string.sort_az);
            case "name_desc": return a.getString(R.string.sort_za);
            case "new": return a.getString(R.string.sort_new);
            case "old": return a.getString(R.string.sort_old);
            case "artist": return a.getString(R.string.sort_artist);
            case "album": return a.getString(R.string.sort_album);
            case "long": return a.getString(R.string.sort_long);
            case "short": return a.getString(R.string.sort_short);
            case "large": return a.getString(R.string.sort_large);
            case "small": return a.getString(R.string.sort_small);
            case "plays": return a.getString(R.string.sort_plays);
            case "most": return a.getString(R.string.sort_most);
            case "order": return a.getString(R.string.sort_order_added);
        }
        return k;
    }

    /** Short label for the chip on the page bar. */
    public static String chip(Activity a, String k) {
        switch (k) {
            case "name": return "A–Z";
            case "name_desc": return "Z–A";
            case "artist": return a.getString(R.string.sort_s_artist);
            case "album": return a.getString(R.string.sort_s_album);
            case "new": return a.getString(R.string.sort_s_new);
            case "old": return a.getString(R.string.sort_s_old);
            case "long": case "short": return a.getString(R.string.sort_s_length);
            case "large": case "small": return a.getString(R.string.sort_s_size);
            case "plays": return a.getString(R.string.sort_s_plays);
            case "most": return a.getString(R.string.sort_s_most);
            case "order": return a.getString(R.string.sort_s_order);
        }
        return "";
    }

    public static int viewLabel(String v) {
        switch (v) {
            case "grid": return R.string.view_grid;
            case "squares": return R.string.view_squares;
            case "folders": return R.string.view_folders;
        }
        return R.string.view_list;
    }

    public static int viewIcon(String v) {
        switch (v) {
            case "grid": return R.drawable.ic_grid;
            case "squares": return R.drawable.ic_music;
            case "folders": return R.drawable.ic_folder;
        }
        return R.drawable.ic_list;
    }

    /**
     * @param foldersFirst null hides the switch.
     */
    public static void show(Activity a, String page, String currentSort, String currentView, Boolean foldersFirst, Listener l) {
        int dp = Ui.dp(a, 1);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_sheet);
        box.setPadding(18 * dp, 10 * dp, 18 * dp, 18 * dp);

        View grip = new View(a);
        grip.setBackgroundResource(R.drawable.bg_handle);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(40 * dp, 4 * dp);
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = 12 * dp;
        box.addView(grip, gp);

        box.addView(title(a, R.string.sort_view_title, 13, R.color.accent));

        final Dialog[] dlg = new Dialog[1];
        box.addView(title(a, R.string.sort_by_caps, 10, R.color.muted));
        for (String k : SHARED) {
            if ("playlist".equals(page) && ("new".equals(k) || "old".equals(k))) continue;
            box.addView(option(a, label(a, k), k.equals(currentSort), () -> { dlg[0].dismiss(); l.onSort(k); }));
        }
        String[] more = extras(page);
        if (more.length > 0) {
            box.addView(title(a, R.string.sort_more_caps, 10, R.color.muted));
            for (String k : more) box.addView(option(a, label(a, k), k.equals(currentSort), () -> { dlg[0].dismiss(); l.onSort(k); }));
        }
        if (foldersFirst != null) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(10 * dp, 6 * dp, 4 * dp, 6 * dp);
            TextView t = new TextView(a);
            t.setText(R.string.sort_folders_first);
            t.setTextColor(a.getColor(R.color.text));
            t.setTextSize(14);
            Switch s = new Switch(a);
            s.setChecked(foldersFirst);
            s.setThumbTintList(ColorStateList.valueOf(a.getColor(R.color.accent)));
            s.setTrackTintList(ColorStateList.valueOf(0x5900E5FF));
            s.setOnCheckedChangeListener((b, on) -> l.onFoldersFirst(on));
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(s);
            row.setOnClickListener(v -> s.toggle());
            box.addView(row);
        }
        String[] views = views(page);
        if (views.length > 1 && currentView != null) {
            box.addView(title(a, R.string.view_caps, 10, R.color.muted));
            LinearLayout seg = new LinearLayout(a);
            seg.setBackgroundResource(R.drawable.bg_segment_box);
            seg.setPadding(2 * dp, 2 * dp, 2 * dp, 2 * dp);
            for (String v : views) {
                TextView b = new TextView(a);
                b.setText(viewLabel(v));
                b.setTypeface(Ui.orbitron(a));
                b.setTextSize(10);
                b.setLetterSpacing(0.1f);
                b.setGravity(Gravity.CENTER);
                b.setCompoundDrawablesRelativeWithIntrinsicBounds(viewIcon(v), 0, 0, 0);
                b.setCompoundDrawablePadding(6 * dp);
                boolean on = v.equals(currentView);
                int color = a.getColor(on ? R.color.accent : R.color.muted);
                b.setTextColor(color);
                b.setCompoundDrawableTintList(ColorStateList.valueOf(color));
                b.setBackgroundResource(R.drawable.bg_segment);
                b.setSelected(on);
                b.setPadding(8 * dp, 10 * dp, 8 * dp, 10 * dp);
                b.setOnClickListener(x -> { dlg[0].dismiss(); l.onView(v); });
                seg.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            box.addView(seg);
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);
        dlg[0] = SpeedSheet.bottom(a, scroll);
    }

    private static TextView title(Activity a, int text, float size, int color) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTypeface(Ui.orbitron(a));
        t.setTextSize(size);
        t.setLetterSpacing(0.14f);
        t.setTextColor(a.getColor(color));
        int dp = Ui.dp(a, 1);
        t.setPadding(4 * dp, 12 * dp, 4 * dp, 6 * dp);
        return t;
    }

    private static TextView option(Activity a, String text, boolean on, Runnable r) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(a.getColor(on ? R.color.accent : R.color.text));
        if (on) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, on ? R.drawable.ic_check : 0, 0);
        t.setCompoundDrawableTintList(ColorStateList.valueOf(a.getColor(R.color.accent)));
        int dp = Ui.dp(a, 1);
        t.setPadding(10 * dp, 11 * dp, 10 * dp, 11 * dp);
        t.setBackgroundResource(on ? R.drawable.bg_row : 0);
        t.setSelected(on);
        t.setOnClickListener(v -> r.run());
        return t;
    }
}
