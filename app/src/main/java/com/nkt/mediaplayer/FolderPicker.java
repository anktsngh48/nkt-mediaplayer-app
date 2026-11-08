package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Browse folders on the phone (and SD card) and pick any one. */
public final class FolderPicker {

    public interface Picked { void done(String path); }

    private FolderPicker() { }

    /** Top-level places: phone storage and any SD cards / USB drives. */
    @SuppressWarnings("deprecation")
    static List<File> roots(Context c) {
        List<File> out = new ArrayList<>();
        out.add(Environment.getExternalStorageDirectory());
        File[] dirs = c.getExternalFilesDirs(null);
        if (dirs != null) for (File d : dirs) {
            if (d == null) continue;
            String p = d.getAbsolutePath();
            int i = p.indexOf("/Android/");
            if (i > 0) {
                File r = new File(p.substring(0, i));
                if (!out.contains(r)) out.add(r);
            }
        }
        return out;
    }

    public static void show(Activity a, String start, Picked cb) {
        final File[] cur = {new File(start)};
        final List<File> roots = roots(a);
        float d = a.getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView path = new TextView(a);
        path.setTextColor(a.getColor(R.color.accent));
        path.setTextSize(13);
        path.setPadding((int) (22 * d), (int) (6 * d), (int) (22 * d), (int) (8 * d));
        ListView list = new ListView(a);
        list.setDivider(null);
        box.addView(path);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (360 * d)));

        final List<File> shown = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(a, android.R.layout.simple_list_item_1, names) {
            @Override public View getView(int pos, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, v, parent);
                t.setTextColor(a.getColor(R.color.text));
                t.setTextSize(15);
                t.setCompoundDrawablesRelativeWithIntrinsicBounds(pos == 0 && cur[0] != null && !isRoot(roots, cur[0])
                        ? R.drawable.ic_arrow_up : R.drawable.ic_folder, 0, 0, 0);
                t.setCompoundDrawablePadding((int) (14 * d));
                t.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.accent)));
                t.setGravity(Gravity.CENTER_VERTICAL);
                return t;
            }
        };
        list.setAdapter(adapter);

        Runnable fill = new Runnable() {
            @Override public void run() {
                shown.clear();
                names.clear();
                File dir = cur[0];
                if (dir == null) {                       // list of storages
                    path.setText(R.string.fp_places);
                    for (int i = 0; i < roots.size(); i++) {
                        shown.add(roots.get(i));
                        names.add(i == 0 ? a.getString(R.string.fp_phone) : a.getString(R.string.fp_card, roots.get(i).getName()));
                    }
                } else {
                    path.setText(pretty(a, roots, dir));
                    shown.add(isRoot(roots, dir) ? null : dir.getParentFile());
                    names.add(isRoot(roots, dir) ? a.getString(R.string.fp_all_places) : a.getString(R.string.fp_up));
                    File[] subs = dir.listFiles(File::isDirectory);
                    if (subs != null) {
                        Arrays.sort(subs, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
                        for (File f : subs) { shown.add(f); names.add(f.getName()); }
                    }
                }
                adapter.notifyDataSetChanged();
                list.setSelection(0);
            }
        };
        list.setOnItemClickListener((p, v, pos, id) -> { cur[0] = shown.get(pos); fill.run(); });
        fill.run();

        AlertDialog dlg = Dialogs.builder(a)
                .setTitle(R.string.fp_title)
                .setView(box)
                .setPositiveButton(R.string.fp_use, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (cur[0] == null) { Ui.toast(a, R.string.fp_pick_one); return; }
            cb.done(cur[0].getAbsolutePath());
            dlg.dismiss();
        }));
        dlg.show();
    }

    private static boolean isRoot(List<File> roots, File f) {
        for (File r : roots) if (r.getAbsolutePath().equals(f.getAbsolutePath())) return true;
        return false;
    }

    static String pretty(Context c, List<File> roots, File f) {
        String p = f.getAbsolutePath();
        for (int i = 0; i < roots.size(); i++) {
            String r = roots.get(i).getAbsolutePath();
            if (p.startsWith(r)) {
                String label = i == 0 ? c.getString(R.string.fp_phone) : c.getString(R.string.fp_card, roots.get(i).getName());
                return label + p.substring(r.length());
            }
        }
        return p;
    }
}
