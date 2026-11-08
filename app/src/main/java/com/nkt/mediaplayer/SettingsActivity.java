package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {
    private LinearLayout col;
    private String pendingSwitch;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        col = Ui.col(this);
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 24));
        sv.addView(col);
        setContentView(sv);
        Ui.edgeToEdge(this, sv);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingSwitch != null && Perms.allFiles(this)) {
            Store.put(this, pendingSwitch, true);
            pendingSwitch = null;
            Ui.msg(this, "Looking for hidden files…");
            Library.refresh(this);
        }
        build();
    }

    private void build() {
        col.removeAllViews();
        LinearLayout top = Ui.row(this);
        ImageButton back = Ui.roundBtn(this, "back", 44, Ui.TEXT, true);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = Ui.label(this, "Settings", 13, Ui.TEXT, 0.2f);
        t.setPadding(Ui.dp(this, 12), 0, 0, 0);
        top.addView(t);
        col.addView(top);

        col.addView(Ui.section(this, "General"));
        String[] pages = {"Songs", "Videos", "Gallery", "Folders", "Playlists"};
        col.addView(choiceCard("Open app on", "The page you see when NKT MP starts", pages, Store.num(this, "start_page", 0),
                w -> Store.put(this, "start_page", w)));

        col.addView(Ui.section(this, "Playback"));
        String[] ends = {"Play next video", "Go back", "Stop on last frame"};
        String[] hints = {"The next video in the list starts by itself", "Closes the player and returns to where you opened it",
                "Pauses on the final picture so you can replay or save it"};
        int e = Store.num(this, "video_end", 0);
        col.addView(choiceCard("When a video ends", hints[e], ends, e, w -> { Store.put(this, "video_end", w); build(); }));
        col.addView(action("Equalizer & karaoke", "Presets, bass, surround, remove vocals", () -> startActivity(new Intent(this, EqualizerActivity.class))));
        String[] rot = {"Auto rotate", "Lock portrait", "Lock landscape"};
        col.addView(action("Video rotation", rot[Store.num(this, "video_orient", 0)], () ->
                new AlertDialog.Builder(this).setTitle("Video rotation").setSingleChoiceItems(rot, Store.num(this, "video_orient", 0), (d, w) -> {
                    Store.put(this, "video_orient", w); d.dismiss(); build();
                }).show()));

        col.addView(Ui.section(this, "Library"));
        col.addView(fileSwitch("show_hidden", "Show hidden files & folders", "Folders starting with a dot, or with a .nomedia file (not .thumbnails)"));
        col.addView(fileSwitch("show_thumbs", "Show .thumbnails folders", "Small preview pictures your phone makes. Separate from the switch above."));

        col.addView(Ui.section(this, "History"));
        Switch hs = Ui.toggle(this, Store.bool(this, "history_on", true));
        hs.setOnCheckedChangeListener((x, on) -> Store.put(this, "history_on", on));
        col.addView(Ui.infoRow(this, "Keep history", "Songs, videos and photos you open, newest first (Playlists → History)", hs));
        col.addView(action("Clear history", Store.history(this).size() + " items", () -> new AlertDialog.Builder(this).setTitle("Clear history?")
                .setMessage("Clear the list of opened files? The files themselves stay.")
                .setPositiveButton("Clear", (d, w) -> { Store.clearHistory(this); build(); }).setNegativeButton("Cancel", null).show()));

        col.addView(Ui.section(this, "Sharing"));
        col.addView(action("NKT Share", "Share files with a computer or other devices on Wi-Fi", () -> startActivity(new Intent(this, NktShareActivity.class))));
        col.addView(action("Other devices", "Open files on phones and computers on Wi-Fi", () -> startActivity(new Intent(this, DevicesActivity.class))));

        col.addView(Ui.section(this, "About"));
        col.addView(Ui.infoRow(this, "NKT MP", "Version 1.0", null));
    }

    interface Pick { void picked(int w); }

    private LinearLayout choiceCard(String title, String sub, String[] options, int cur, Pick p) {
        LinearLayout c = Ui.col(this);
        c.setBackground(Ui.rect(this, Ui.CARD, 12, Ui.BORDER, 1));
        c.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        c.addView(Ui.text(this, title, 14, Ui.TEXT));
        TextView s = Ui.text(this, sub, 12, Ui.MUTED);
        s.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 10));
        c.addView(s);
        LinearLayout row = Ui.row(this);
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            final int w = i;
            TextView ch = Ui.chip(this, options[i], i == cur);
            chips.add(ch);
            ch.setOnClickListener(v -> {
                for (int k = 0; k < chips.size(); k++) Ui.setChip(this, chips.get(k), k == w);
                p.picked(w);
            });
            row.addView(ch, Ui.lpm(this, Ui.WRAP, Ui.dp(this, 36), 0, 0, 6, 0));
        }
        c.addView(Ui.chipRow(this, row));
        c.setLayoutParams(Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        return c;
    }

    private LinearLayout action(String title, String sub, Runnable r) {
        TextView arrow = Ui.text(this, "›", 22, Ui.MUTED);
        arrow.setGravity(Gravity.CENTER);
        LinearLayout row = Ui.infoRow(this, title, sub, arrow);
        row.setOnClickListener(v -> r.run());
        return row;
    }

    private LinearLayout fileSwitch(String key, String title, String sub) {
        boolean on = Store.bool(this, key, false) && Perms.allFiles(this);
        Switch s = Ui.toggle(this, on);
        s.setOnCheckedChangeListener((x, v) -> {
            if (v && !Perms.allFiles(this)) {
                s.setChecked(false);
                pendingSwitch = key;
                Perms.askAllFiles(this, "To show files Android hides, NKT MP needs access to all files.");
                return;
            }
            Store.put(this, key, v);
            if (v) Ui.msg(this, "Looking for hidden files…");
            Library.refresh(this);
        });
        return Ui.infoRow(this, title, sub, s);
    }
}
