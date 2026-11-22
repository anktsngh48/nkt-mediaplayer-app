package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Notifications Log (menu): the switch, which apps, and the log of each app (newest first). Tap an app to read its
 * log; long-press to delete it. Switching on the first time asks for Android's Notification access, then for the apps.
 */
public class NotifLogActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Switch sw;
    private TextView status, appsLine, listHead, empty;
    private View appsRow;
    private LinearLayout logs;
    private boolean askingAccess = false, quiet = false;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setPadding(dp(16), 0, dp(16), 0);
        setContentView(root);
        Ui.padForSystemBars(root);
        root.addView(Ui.appBar(this, getString(R.string.nlog_caps), this::finish));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(8), 0, dp(24));
        sv.addView(col, new ViewGroup.LayoutParams(MATCH, WRAP));
        root.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // the switch, and which apps
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_options_bar);
        card.setPadding(dp(14), dp(12), dp(10), dp(12));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.addView(text(getString(R.string.nlog_switch), 15, R.color.text, false));
        status = text("", 12, R.color.muted, false);
        status.setPadding(0, dp(3), 0, 0);
        tt.addView(status);
        top.addView(tt, new LinearLayout.LayoutParams(0, WRAP, 1f));
        sw = new Switch(this);
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        top.addView(sw);
        top.setOnClickListener(v -> sw.toggle());
        card.addView(top);
        LinearLayout ar = new LinearLayout(this);
        ar.setGravity(Gravity.CENTER_VERTICAL);
        ar.setPadding(0, dp(10), 0, 0);
        appsLine = text("", 13, R.color.muted, false);
        ar.addView(appsLine, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView change = pill(getString(R.string.nlog_change_caps));
        change.setOnClickListener(v -> pickApps(false));
        ar.addView(change);
        appsRow = ar;
        card.addView(ar);
        col.addView(card, new LinearLayout.LayoutParams(MATCH, WRAP));

        listHead = text("", 10, R.color.muted, false);
        listHead.setLetterSpacing(0.18f);
        listHead.setTypeface(Ui.orbitron(this));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(MATCH, WRAP);
        hl.topMargin = dp(22);
        hl.bottomMargin = dp(6);
        col.addView(listHead, hl);
        logs = new LinearLayout(this);
        logs.setOrientation(LinearLayout.VERTICAL);
        col.addView(logs, new LinearLayout.LayoutParams(MATCH, WRAP));
        empty = text(getString(R.string.nlog_empty_off), 14, R.color.muted, false);
        empty.setGravity(Gravity.CENTER);
        empty.setCompoundDrawables(null, bell(56), null, null);
        empty.setCompoundDrawablePadding(dp(12));
        empty.setPadding(dp(20), dp(60), dp(20), 0);
        col.addView(empty, new LinearLayout.LayoutParams(MATCH, WRAP));

        sw.setOnCheckedChangeListener((b, on) -> {
            if (quiet) return;
            if (!on) { NotifLog.setOn(this, false); update(); return; }
            if (!NotifLog.hasAccess(this)) { setSwitch(false); askAccess(); return; }
            pickApps(true);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (askingAccess) {                                    // back from Android's Notification access page
            askingAccess = false;
            if (NotifLog.hasAccess(this)) pickApps(true);
        }
        update();
        loadLogs();
    }

    private void setSwitch(boolean on) { quiet = true; sw.setChecked(on); quiet = false; }

    private void update() {
        boolean on = NotifLog.on(this);
        setSwitch(on && NotifLog.hasAccess(this));
        boolean[] green = new boolean[1];
        String s = NotifLog.status(this, green);
        status.setText(green[0] ? "● " + s : s);
        status.setTextColor(getColor(green[0] ? R.color.success : R.color.muted));
        status.setTypeface(null, green[0] ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        appsRow.setVisibility(on ? View.VISIBLE : View.GONE);
        int n = NotifLog.apps(this).size();
        appsLine.setText(NotifLog.all(this) ? getString(R.string.nlog_apps_all)
                : getResources().getQuantityString(R.plurals.nlog_apps_n, n, n));
    }

    private void askAccess() {
        Dialogs.builder(this)
                .setTitle(R.string.nlog_access_title)
                .setMessage(R.string.nlog_access_msg)
                .setPositiveButton(R.string.nlog_open_settings, (d, w) -> {
                    askingAccess = true;
                    try { startActivity(NotifLog.accessIntent(this)); }
                    catch (Exception e) { askingAccess = false; Ui.toast(this, R.string.nlog_no_settings); }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── The logs ──

    private void loadLogs() {
        new Thread(() -> {
            List<NotifLog.AppLog> list = NotifLog.list(this);
            ui.post(() -> { if (!isFinishing()) showLogs(list); });
        }, "nkt-nlog-list").start();
    }

    private void showLogs(List<NotifLog.AppLog> list) {
        logs.removeAllViews();
        listHead.setText(getResources().getQuantityString(R.plurals.nlog_logs_head, list.size(), list.size()).toUpperCase(Locale.getDefault()));
        listHead.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(NotifLog.on(this) ? R.string.nlog_empty_on : R.string.nlog_empty_off);
        PackageManager pm = getPackageManager();
        for (NotifLog.AppLog a : list) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackgroundResource(R.drawable.bg_options_bar);
            r.setPadding(dp(12), dp(10), dp(8), dp(10));
            ImageView ic = new ImageView(this);
            Drawable d = null;
            try { d = pm.getApplicationIcon(a.pkg); } catch (Throwable ignored) { }
            if (d != null) ic.setImageDrawable(d); else { ic.setImageResource(R.drawable.ic_bell); ic.setColorFilter(getColor(R.color.muted)); }
            r.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.addView(text(a.label, 14, R.color.text, false));
            TextView sub = text(getResources().getQuantityString(R.plurals.nlog_count, a.count, a.count) + " · "
                    + getString(R.string.nlog_last, when(a.last)), 12, R.color.muted, false);
            t.addView(sub);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, WRAP, 1f);
            tl.setMarginStart(dp(12));
            r.addView(t, tl);
            ImageView ch = new ImageView(this);
            ch.setImageResource(R.drawable.ic_chevron_right);
            ch.setColorFilter(getColor(R.color.muted));
            r.addView(ch, new LinearLayout.LayoutParams(dp(20), dp(20)));
            r.setOnClickListener(v -> startActivity(new Intent(this, NotifLogViewActivity.class).putExtra("pkg", a.pkg)));
            r.setOnLongClickListener(v -> { confirmDelete(a); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
            lp.bottomMargin = dp(8);
            logs.addView(r, lp);
        }
        TextView hint = text(getString(R.string.nlog_list_hint), 11.5f, R.color.muted, false);
        hint.setPadding(dp(2), dp(4), dp(2), 0);
        if (!list.isEmpty()) logs.addView(hint);
    }

    private String when(long t) {
        if (DateUtils.isToday(t)) return NotifLog.timeOf(t);
        if (DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS)) return getString(R.string.nlog_yesterday);
        return DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
    }

    private void confirmDelete(NotifLog.AppLog a) {
        Dialogs.builder(this)
                .setTitle(getString(R.string.nlog_delete_title, a.label))
                .setMessage(R.string.nlog_delete_msg)
                .setPositiveButton(R.string.nlog_delete, (d, w) -> { NotifLog.delete(this, a.pkg); ui.postDelayed(this::loadLogs, 200); })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Choose apps ──

    private static final class App { String pkg, label; Drawable icon; }

    /** All apps (ticked by default), a search, and every app with a tick box. first = switching on. */
    private void pickApps(boolean first) {
        final boolean[] all = {NotifLog.all(this)};
        final Set<String> chosen = NotifLog.apps(this);
        final List<App> apps = new ArrayList<>(), shown = new ArrayList<>();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), 0);
        LinearLayout allRow = new LinearLayout(this);
        allRow.setGravity(Gravity.CENTER_VERTICAL);
        allRow.setBackgroundResource(R.drawable.bg_segment);
        allRow.setPadding(dp(12), dp(10), dp(6), dp(10));
        LinearLayout at = new LinearLayout(this);
        at.setOrientation(LinearLayout.VERTICAL);
        at.addView(text(getString(R.string.nlog_all_apps), 15, R.color.text, true));
        at.addView(text(getString(R.string.nlog_all_apps_sub), 12, R.color.muted, false));
        allRow.addView(at, new LinearLayout.LayoutParams(0, WRAP, 1f));
        CheckBox allBox = new CheckBox(this);
        allBox.setButtonTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        allBox.setClickable(false);
        allRow.addView(allBox);
        box.addView(allRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        EditText search = new EditText(this);
        search.setHint(R.string.nlog_search_apps);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, WRAP);
        sl.topMargin = dp(8);
        box.addView(search, sl);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        TextView loading = text(getString(R.string.nlog_loading_apps), 13, R.color.muted, false);
        loading.setPadding(0, dp(16), 0, dp(16));
        box.addView(loading);
        box.addView(rv, new LinearLayout.LayoutParams(MATCH, dp(320)));
        TextView note = text(getString(R.string.nlog_untick_note), 11.5f, R.color.muted, false);
        note.setPadding(0, dp(6), 0, 0);
        box.addView(note);

        final RecyclerView.Adapter<RecyclerView.ViewHolder> ad = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return shown.size(); }
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
                LinearLayout r = new LinearLayout(NotifLogActivity.this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(4), dp(6), 0, dp(6));
                r.setLayoutParams(new RecyclerView.LayoutParams(MATCH, WRAP));
                ImageView ic = new ImageView(NotifLogActivity.this);
                r.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));
                TextView n = text("", 14, R.color.text, false);
                n.setSingleLine(true);
                n.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, WRAP, 1f);
                nl.setMarginStart(dp(12));
                r.addView(n, nl);
                CheckBox cb = new CheckBox(NotifLogActivity.this);
                cb.setButtonTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
                cb.setClickable(false);
                r.addView(cb);
                return new RecyclerView.ViewHolder(r) { };
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                App a = shown.get(pos);
                LinearLayout r = (LinearLayout) h.itemView;
                ((ImageView) r.getChildAt(0)).setImageDrawable(a.icon);
                ((TextView) r.getChildAt(1)).setText(a.label);
                ((CheckBox) r.getChildAt(2)).setChecked(all[0] || chosen.contains(a.pkg));
                r.setOnClickListener(v -> {
                    if (all[0]) {                               // untick one: "All apps" goes off, everything else stays ticked
                        all[0] = false;
                        chosen.clear();
                        for (App x : apps) chosen.add(x.pkg);
                        chosen.remove(a.pkg);
                    } else if (!chosen.remove(a.pkg)) chosen.add(a.pkg);
                    allBox.setChecked(all[0]);
                    notifyDataSetChanged();
                });
            }
        };
        rv.setAdapter(ad);
        allBox.setChecked(all[0]);
        allRow.setOnClickListener(v -> {
            all[0] = !all[0];
            if (!all[0]) chosen.clear();
            allBox.setChecked(all[0]);
            ad.notifyDataSetChanged();
        });
        final Runnable filter = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
            shown.clear();
            for (App a : apps) if (q.isEmpty() || a.label.toLowerCase(Locale.getDefault()).contains(q)) shown.add(a);
            ad.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { filter.run(); }
        });

        AlertDialog d = Dialogs.builder(this)
                .setTitle(R.string.nlog_choose_apps)
                .setView(box)
                .setPositiveButton(first ? R.string.nlog_start_caps : R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!all[0] && chosen.isEmpty()) { Ui.toast(this, R.string.nlog_pick_one); return; }
            NotifLog.setApps(this, all[0], chosen);
            NotifLog.setOn(this, true);
            d.dismiss();
            update();
            loadLogs();
        }));
        d.setOnDismissListener(x -> update());
        d.show();

        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
            Set<String> seen = new HashSet<>();
            List<App> got = new ArrayList<>();
            for (ResolveInfo ri : ris) {
                String p = ri.activityInfo == null ? null : ri.activityInfo.packageName;
                if (p == null || !seen.add(p)) continue;
                App a = new App();
                a.pkg = p;
                try { a.label = pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString(); } catch (Throwable t) { a.label = ri.loadLabel(pm).toString(); }
                try { a.icon = pm.getApplicationIcon(p); } catch (Throwable ignored) { }
                got.add(a);
            }
            for (String p : chosen) {                           // a chosen app with no launcher icon still shows
                if (seen.contains(p)) continue;
                App a = new App();
                a.pkg = p;
                a.label = NotifLog.label(this, p);
                got.add(a);
            }
            Collections.sort(got, (x, y) -> x.label.compareToIgnoreCase(y.label));
            ui.post(() -> {
                apps.clear();
                apps.addAll(got);
                loading.setVisibility(View.GONE);
                filter.run();
            });
        }, "nkt-nlog-apps").start();
    }

    // ── Bits ──

    private Drawable bell(int size) {
        Drawable b = getDrawable(R.drawable.ic_bell);
        if (b == null) return null;
        b = b.mutate();
        b.setTint(getColor(R.color.dot_off));
        b.setBounds(0, 0, dp(size), dp(size));
        return b;
    }

    private TextView pill(String s) {
        TextView t = text(s, 11, R.color.accent, true);
        t.setLetterSpacing(0.1f);
        t.setBackgroundResource(R.drawable.bg_pill_btn);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        return t;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }
}
