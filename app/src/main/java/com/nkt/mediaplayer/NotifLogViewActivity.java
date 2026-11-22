package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One app's Notifications Log, read like a text file: a date line for each day, then one line per notification
 * (time · chat › name: text), newest first. Tap a name to see only that person; search; export as a .txt file.
 */
public class NotifLogViewActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String pkg, label;
    private final List<NotifLog.Entry> all = new ArrayList<>();       // newest first
    private final List<NotifLog.Entry> shown = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();               // a day line (String) or an entry
    private String person = null, query = "";
    private TextView title, info, personText, found;
    private View personBar, titleBox, searchBox;
    private EditText search;
    private RecyclerView list;
    private Adapter adapter;
    private int indent = 0;
    private long loadedLen = -1;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pkg = getIntent().getStringExtra("pkg");
        if (pkg == null) { finish(); return; }
        label = NotifLog.label(this, pkg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setPadding(dp(12), 0, dp(12), 0);
        setContentView(root);
        Ui.padForSystemBars(root);

        // the bar: back · name · search · export · more   (or, searching: back · the search box · clear)
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, dp(6));
        ImageButton back = roundButton(R.drawable.ic_back, R.string.cd_back);
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout tb = new LinearLayout(this);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        title = text(label.toUpperCase(Locale.getDefault()), 13, R.color.accent, false);
        title.setTypeface(Ui.orbitron(this));
        title.setLetterSpacing(0.1f);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ttl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        ttl.setMarginStart(dp(12));
        tb.addView(title, ttl);
        ImageButton sb = iconButton(R.drawable.ic_search, R.string.nlog_cd_search);
        sb.setOnClickListener(v -> openSearch());
        tb.addView(sb, new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageButton eb = iconButton(R.drawable.ic_export, R.string.nlog_cd_export);
        eb.setOnClickListener(v -> exportDialog());
        tb.addView(eb, new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageButton mb = iconButton(R.drawable.ic_more, R.string.cd_more);
        mb.setOnClickListener(this::more);
        tb.addView(mb, new LinearLayout.LayoutParams(dp(44), dp(44)));
        titleBox = tb;
        bar.addView(tb, new LinearLayout.LayoutParams(0, WRAP, 1f));

        LinearLayout sbx = new LinearLayout(this);
        sbx.setGravity(Gravity.CENTER_VERTICAL);
        search = new EditText(this);
        search.setHint(R.string.nlog_search_hint);
        search.setHintTextColor(getColor(R.color.muted));
        search.setTextColor(getColor(R.color.text));
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackgroundResource(R.drawable.bg_input);
        search.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(42), 1f);
        sl.setMarginStart(dp(10));
        sbx.addView(search, sl);
        ImageButton clear = iconButton(R.drawable.ic_close, R.string.nlog_cd_clear);
        clear.setOnClickListener(v -> { if (search.length() > 0) search.setText(""); else closeSearch(); });
        sbx.addView(clear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        searchBox = sbx;
        sbx.setVisibility(View.GONE);
        bar.addView(sbx, new LinearLayout.LayoutParams(0, WRAP, 1f));
        root.addView(bar);

        info = text("", 12, R.color.muted, false);
        info.setPadding(dp(4), dp(4), dp(4), dp(6));
        root.addView(info);

        // only one person: "From Rahul · 23 notifications  ✕"
        LinearLayout pb = new LinearLayout(this);
        pb.setGravity(Gravity.CENTER_VERTICAL);
        pb.setBackgroundResource(R.drawable.bg_segment);
        pb.setSelected(true);
        pb.setPadding(dp(14), 0, dp(2), 0);
        personText = text("", 13, R.color.text, false);
        pb.addView(personText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        ImageButton px = iconButton(R.drawable.ic_close, R.string.nlog_cd_everyone);
        px.setOnClickListener(v -> setPerson(null));
        pb.addView(px, new LinearLayout.LayoutParams(dp(40), dp(40)));
        personBar = pb;
        pb.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(MATCH, WRAP);
        pbl.bottomMargin = dp(8);
        root.addView(pb, pbl);

        found = text("", 12, R.color.accent, true);
        found.setPadding(dp(4), 0, dp(4), dp(6));
        found.setVisibility(View.GONE);
        root.addView(found);

        list = new RecyclerView(this);
        list.setBackgroundResource(R.drawable.bg_editor);
        list.setPadding(dp(12), dp(6), dp(12), dp(12));
        list.setClipToPadding(false);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        ll.bottomMargin = dp(12);
        root.addView(list, ll);

        Paint p = new Paint();
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12.5f, getResources().getDisplayMetrics()));
        indent = Math.round(p.measureText("00:00  "));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                ui.removeCallbacks(applySearch);
                ui.postDelayed(applySearch, 150);
            }
        });
    }

    private final Runnable applySearch = () -> { query = search.getText().toString().trim(); rebuild(); };

    @Override
    protected void onResume() {
        super.onResume();
        load();                                                // new notifications since it was last open
    }

    private void load() {
        new Thread(() -> {
            File f = new File(NotifLog.dir(this), pkg + ".log");
            long len = f.length();
            if (len == loadedLen) return;
            List<NotifLog.Entry> got = NotifLog.read(this, pkg);
            Collections.reverse(got);
            ui.post(() -> {
                if (isFinishing()) return;
                loadedLen = len;
                all.clear();
                all.addAll(got);
                String since = all.isEmpty() ? "" : DateUtils.formatDateTime(this, all.get(all.size() - 1).time,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
                info.setText(all.isEmpty() ? getString(R.string.nlog_view_empty)
                        : getString(R.string.nlog_view_info, label + ".txt",
                        getResources().getQuantityString(R.plurals.nlog_count, all.size(), all.size()), since));
                rebuild();
            });
        }, "nkt-nlog-read").start();
    }

    /** The lines on screen: the person picked (if any), then the search (if any). */
    private void rebuild() {
        shown.clear();
        String q = query.toLowerCase(Locale.getDefault());
        int forPerson = 0;
        for (NotifLog.Entry e : all) {
            if (person != null && !person.equalsIgnoreCase(e.sender)) continue;
            forPerson++;
            if (!q.isEmpty() && !(e.chat + "\n" + e.sender + "\n" + e.text).toLowerCase(Locale.getDefault()).contains(q)) continue;
            shown.add(e);
        }
        rows.clear();
        String day = null;
        for (NotifLog.Entry e : shown) {
            String d = NotifLog.dayLine(e.time);
            if (!d.equals(day)) { rows.add(d); day = d; }
            rows.add(e);
        }
        personBar.setVisibility(person == null ? View.GONE : View.VISIBLE);
        if (person != null) {
            SpannableStringBuilder s = new SpannableStringBuilder(getString(R.string.nlog_from) + " ");
            int st = s.length();
            s.append(person);
            s.setSpan(new ForegroundColorSpan(getColor(R.color.accent)), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.append(" · ").append(getResources().getQuantityString(R.plurals.nlog_count, forPerson, forPerson));
            personText.setText(s);
        }
        boolean searching = !query.isEmpty();
        found.setVisibility(searching ? View.VISIBLE : View.GONE);
        found.setText(getResources().getQuantityString(R.plurals.nlog_found, shown.size(), shown.size()));
        adapter.notifyDataSetChanged();
    }

    private void setPerson(String p) {
        person = p;
        rebuild();
        list.scrollToPosition(0);
    }

    // ── Search ──

    private void openSearch() {
        titleBox.setVisibility(View.GONE);
        searchBox.setVisibility(View.VISIBLE);
        search.requestFocus();
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(search, 0);
    }

    private void closeSearch() {
        search.setText("");
        query = "";
        searchBox.setVisibility(View.GONE);
        titleBox.setVisibility(View.VISIBLE);
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
        rebuild();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (searchBox.getVisibility() == View.VISIBLE) { closeSearch(); return; }
        if (person != null) { setPerson(null); return; }
        super.onBackPressed();
    }

    // ── The lines ──

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return rows.size(); }
        @Override public int getItemViewType(int p) { return rows.get(p) instanceof String ? 0 : 1; }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            TextView t = new TextView(NotifLogViewActivity.this);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, type == 0 ? 12 : 12.5f);
            t.setLineSpacing(0, 1.2f);
            t.setTextColor(type == 0 ? getColor(R.color.accent) : 0xFFC9D4E5);
            t.setPadding(0, type == 0 ? dp(12) : dp(3), 0, type == 0 ? dp(4) : dp(3));
            t.setTextIsSelectable(false);
            if (type == 1) {
                t.setMovementMethod(LinkMovementMethod.getInstance());
                t.setHighlightColor(Color.TRANSPARENT);
            }
            t.setLayoutParams(new RecyclerView.LayoutParams(MATCH, WRAP));
            return new RecyclerView.ViewHolder(t) { };
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            TextView t = (TextView) h.itemView;
            Object r = rows.get(pos);
            if (r instanceof String) { t.setText((String) r); return; }
            t.setText(line((NotifLog.Entry) r));
        }
    }

    /** "10:42  Family Group › Mummy: Lunch at 1?" with the name tappable and the search highlighted. */
    private CharSequence line(NotifLog.Entry e) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        int muted = getColor(R.color.muted), accent = getColor(R.color.accent);
        s.append(NotifLog.timeOf(e.time)).append("  ");
        s.setSpan(new ForegroundColorSpan(muted), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!e.chat.isEmpty()) {
            int st = s.length();
            s.append(e.chat).append(" › ");
            s.setSpan(new ForegroundColorSpan(muted), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!e.sender.isEmpty()) {
            int st = s.length();
            s.append(e.sender);
            final String who = e.sender;
            final boolean picked = person != null;
            s.setSpan(new ClickableSpan() {
                @Override public void onClick(View v) { if (!who.equalsIgnoreCase(person)) setPerson(who); }
                @Override public void updateDrawState(TextPaint ds) { ds.setColor(accent); ds.setUnderlineText(!picked); }
            }, st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (picked) s.setSpan(new BackgroundColorSpan(0x2E00E5FF), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.append(": ");
        }
        s.append(e.text);
        s.setSpan(new LeadingMarginSpan.Standard(0, indent), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!query.isEmpty()) {                                // the search, highlighted (not in the time)
            String low = s.toString().toLowerCase(Locale.getDefault()), q = query.toLowerCase(Locale.getDefault());
            int from = 7;
            while (true) {
                int i = low.indexOf(q, from);
                if (i < 0) break;
                s.setSpan(new BackgroundColorSpan(0x4D00E5FF), i, i + q.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.setSpan(new ForegroundColorSpan(getColor(R.color.text)), i, i + q.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = i + q.length();
            }
        }
        return s;
    }

    // ── Export ──

    private void exportDialog() {
        if (all.isEmpty()) { Ui.toast(this, R.string.nlog_view_empty); return; }
        boolean part = person != null || !query.isEmpty();
        final String fileName = (label + " " + getString(R.string.nlog_file_word) + " "
                + new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()) + ".txt").replaceAll("[\\\\/:*?\"<>|]", "_");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);
        RadioGroup rg = new RadioGroup(this);
        RadioButton whole = radio(getResources().getQuantityString(R.plurals.nlog_export_whole, all.size(), all.size()));
        whole.setId(View.generateViewId());
        rg.addView(whole);
        RadioButton now = null;
        if (part) {
            String what = person != null ? person : "“" + query + "”";
            now = radio(getString(R.string.nlog_export_shown, what, shown.size()));
            now.setId(View.generateViewId());
            rg.addView(now);
        }
        rg.check(part ? now.getId() : whole.getId());
        box.addView(rg);
        TextView where = text(getString(R.string.nlog_export_where, fileName), 12, R.color.muted, false);
        where.setPadding(0, dp(10), 0, 0);
        box.addView(where);
        final RadioButton nowF = now;
        AlertDialog d = Dialogs.builder(this)
                .setTitle(R.string.nlog_export_title)
                .setView(box)
                .setPositiveButton(R.string.save, (x, w) -> export(fileName, nowF != null && nowF.isChecked(), false))
                .setNeutralButton(R.string.nlog_share, (x, w) -> export(fileName, nowF != null && nowF.isChecked(), true))
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.show();
    }

    private void export(String fileName, boolean onlyShown, boolean share) {
        final List<NotifLog.Entry> which = new ArrayList<>(onlyShown ? shown : all);
        String head = label + " · " + getString(R.string.nlog_caps_plain)
                + (onlyShown && person != null ? " · " + getString(R.string.nlog_from) + " " + person : "")
                + (onlyShown && !query.isEmpty() ? " · “" + query + "”" : "");
        new Thread(() -> {
            String text = NotifLog.asText(head, which);
            String msg;
            try {
                if (share) {
                    File dir = new File(getCacheDir(), "notif_export");
                    dir.mkdirs();
                    File[] old = dir.listFiles();
                    if (old != null) for (File o : old) o.delete();
                    File f = new File(dir, fileName);
                    try (OutputStream os = new FileOutputStream(f)) { os.write(text.getBytes(StandardCharsets.UTF_8)); }
                    Uri u = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", f);
                    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, u)
                            .putExtra(Intent.EXTRA_SUBJECT, fileName).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    ui.post(() -> {
                        try { startActivity(Intent.createChooser(send, getString(R.string.nlog_share))); }
                        catch (Exception e) { Ui.toast(this, R.string.nlog_export_failed); }
                    });
                    return;
                }
                save(fileName, text);
                msg = getString(R.string.nlog_saved, fileName);
            } catch (Throwable t) {
                msg = getString(R.string.nlog_export_failed);
            }
            final String m = msg;
            ui.post(() -> Ui.toast(this, m));
        }, "nkt-nlog-export").start();
    }

    /** Download / NKT Media Player / the file. */
    private void save(String name, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NKT Media Player");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (u == null) throw new java.io.IOException("no place to save");
            try (OutputStream os = getContentResolver().openOutputStream(u)) {
                if (os == null) throw new java.io.IOException("no place to save");
                os.write(bytes);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(u, done, null, null);
        } else {
            @SuppressWarnings("deprecation")
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NKT Media Player");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no folder");
            try (OutputStream os = new FileOutputStream(new File(dir, name))) { os.write(bytes); }
        }
    }

    // ── More ──

    private void more(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.nlog_delete_log);
        m.setOnMenuItemClickListener(it -> {
            Dialogs.builder(this)
                    .setTitle(getString(R.string.nlog_delete_title, label))
                    .setMessage(R.string.nlog_delete_msg)
                    .setPositiveButton(R.string.nlog_delete, (d, w) -> { NotifLog.delete(this, pkg); finish(); })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        });
        m.show();
    }

    // ── Bits ──

    private RadioButton radio(String s) {
        RadioButton r = new RadioButton(this);
        r.setText(s);
        r.setTextColor(getColor(R.color.text));
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        r.setButtonTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        return r;
    }

    private ImageButton roundButton(int res, int desc) {
        ImageButton b = new ImageButton(this);
        b.setBackgroundResource(R.drawable.bg_round_btn);
        b.setImageResource(res);
        b.setColorFilter(getColor(R.color.accent));
        b.setContentDescription(getString(desc));
        return b;
    }

    private ImageButton iconButton(int res, int desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setColorFilter(getColor(R.color.text));
        b.setContentDescription(getString(desc));
        return b;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }
}
