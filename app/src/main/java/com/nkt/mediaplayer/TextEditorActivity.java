package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/** Simple text editor for .txt, .md, .json, .srt, .lrc and other plain-text files. */
public class TextEditorActivity extends Activity {
    private EditText edit;
    private TextView name, status, info, save, wrapChip;
    private File file;
    private Uri uri;
    private boolean dirty, loading, wrap = true;
    private float size = 14;
    private final Deque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private String lastText = "";
    private LinearLayout editorBox;
    private ScrollView vScroll;
    private HorizontalScrollView hScroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String p = getIntent().getStringExtra("path");
        if (p != null) file = new File(p);
        else if (getIntent().getData() != null) {
            uri = getIntent().getData();
            if ("file".equals(uri.getScheme())) file = new File(uri.getPath());
        }
        if (file == null && uri == null) { finish(); return; }

        LinearLayout col = Ui.col(this);
        col.setBackgroundColor(Ui.BG);
        col.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout top = Ui.row(this);
        ImageButton back = Ui.roundBtn(this, "back", 44, Ui.TEXT, true);
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back);
        LinearLayout tt = Ui.col(this);
        tt.setPadding(Ui.dp(this, 10), 0, 0, 0);
        name = Ui.single(Ui.text(this, file != null ? file.getName() : Media.fromUri(this, uri, -1).name, 15, Ui.TEXT));
        name.setTypeface(null, Typeface.BOLD);
        status = Ui.single(Ui.text(this, "", 11, Ui.MUTED));
        tt.addView(name);
        tt.addView(status);
        top.addView(tt, Ui.lpw(0, Ui.WRAP, 1));
        ImageButton u = Ui.iconBtn(this, "back", 40, Ui.TEXT);
        u.setContentDescription("Undo");
        u.setOnClickListener(v -> doUndo());
        ImageButton r = Ui.iconBtn(this, "forward", 40, Ui.TEXT);
        r.setContentDescription("Redo");
        r.setOnClickListener(v -> doRedo());
        ImageButton find = Ui.iconBtn(this, "search", 40, Ui.ACCENT);
        find.setOnClickListener(v -> findReplace());
        top.addView(u);
        top.addView(r);
        top.addView(find);
        save = Ui.pill(this, "SAVE", false, Ui.MUTED);
        save.setOnClickListener(v -> save(null));
        top.addView(save, Ui.lpm(this, Ui.WRAP, Ui.dp(this, 40), 4, 0, 0, 0));
        col.addView(top);

        edit = new EditText(this);
        edit.setTypeface(Typeface.MONOSPACE);
        edit.setTextColor(Ui.TEXT);
        edit.setTextSize(size);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setBackground(null);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        edit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(Editable e) {
                if (loading) return;
                undo.push(lastText);
                if (undo.size() > 200) undo.removeLast();
                redo.clear();
                lastText = e.toString();
                setDirty(true);
            }
        });
        editorBox = Ui.col(this);
        editorBox.setBackground(Ui.rect(this, Ui.FILL, 14, Ui.BORDER, 1));
        vScroll = new ScrollView(this);
        vScroll.setFillViewport(true);
        hScroll = new HorizontalScrollView(this);
        hScroll.setFillViewport(true);
        layoutEditor();
        col.addView(editorBox, Ui.lpm(this, Ui.MATCH, 0, 0, 12, 0, 0));
        ((LinearLayout.LayoutParams) editorBox.getLayoutParams()).weight = 1;

        LinearLayout bottom = Ui.row(this);
        info = Ui.single(Ui.text(this, "", 11, Ui.MUTED));
        bottom.addView(info, Ui.lpw(0, Ui.WRAP, 1));
        wrapChip = Ui.chip(this, "WRAP", true);
        wrapChip.setOnClickListener(v -> { wrap = !wrap; Ui.setChip(this, wrapChip, wrap); layoutEditor(); });
        TextView smaller = Ui.chip(this, "A−", false), bigger = Ui.chip(this, "A+", false);
        smaller.setOnClickListener(v -> { size = Math.max(10, size - 1); edit.setTextSize(size); });
        bigger.setOnClickListener(v -> { size = Math.min(24, size + 1); edit.setTextSize(size); });
        bottom.addView(wrapChip, Ui.lpm(this, Ui.WRAP, Ui.dp(this, 36), 0, 0, 6, 0));
        bottom.addView(smaller, Ui.lpm(this, Ui.WRAP, Ui.dp(this, 36), 0, 0, 6, 0));
        bottom.addView(bigger, Ui.lp(Ui.WRAP, Ui.dp(this, 36)));
        col.addView(bottom, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        setContentView(col);
        Ui.edgeToEdge(this, col);
        load();
    }

    private void layoutEditor() {
        if (edit.getParent() != null) ((android.view.ViewGroup) edit.getParent()).removeView(edit);
        vScroll.removeAllViews();
        hScroll.removeAllViews();
        editorBox.removeAllViews();
        edit.setHorizontallyScrolling(!wrap);
        if (wrap) {
            vScroll.addView(edit, new android.widget.FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        } else {
            hScroll.addView(edit, new android.widget.FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
            vScroll.addView(hScroll, new android.widget.FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        }
        editorBox.addView(vScroll, Ui.lp(Ui.MATCH, Ui.MATCH));
    }

    private void load() {
        status.setText("Opening…");
        new Thread(() -> {
            try {
                long len = file != null ? file.length() : 0;
                if (len > 5L * 1024 * 1024) throw new Exception("big");
                byte[] data;
                try (InputStream in = file != null ? new FileInputStream(file) : getContentResolver().openInputStream(uri)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 5 * 1024 * 1024) throw new Exception("big"); }
                    data = bo.toByteArray();
                }
                String text = new String(data, StandardCharsets.UTF_8);
                runOnUiThread(() -> {
                    loading = true;
                    edit.setText(text);
                    lastText = text;
                    loading = false;
                    setDirty(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Ui.msg(this, e.getMessage() != null && e.getMessage().equals("big") ? "This file is too big to edit here (over 5 MB)" : "Couldn't open this file");
                    finish();
                });
            }
        }).start();
    }

    private void setDirty(boolean d) {
        dirty = d;
        status.setText(d ? "Not saved" : "Saved");
        status.setTextColor(d ? Ui.ACCENT : Ui.MUTED);
        save.setTextColor(d ? Ui.BG : Ui.MUTED);
        save.setBackground(d ? Ui.rect(this, Ui.ACCENT, 27, 0, 0) : Ui.rect(this, 0, 27, 0x4000E5FF, 1));
        String t = edit.getText().toString();
        int lines = t.isEmpty() ? 1 : t.split("\n", -1).length;
        info.setText(String.format(Locale.US, "%d lines · %,d characters · UTF-8", lines, t.length()));
    }

    private void doUndo() {
        if (undo.isEmpty()) return;
        redo.push(edit.getText().toString());
        String s = undo.pop();
        loading = true; edit.setText(s); lastText = s; loading = false;
        setDirty(true);
    }

    private void doRedo() {
        if (redo.isEmpty()) return;
        undo.push(edit.getText().toString());
        String s = redo.pop();
        loading = true; edit.setText(s); lastText = s; loading = false;
        setDirty(true);
    }

    private void findReplace() {
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        EditText f = new EditText(this); f.setHint("Find"); f.setTextColor(Ui.TEXT); f.setSingleLine(true);
        EditText r = new EditText(this); r.setHint("Replace with (optional)"); r.setTextColor(Ui.TEXT); r.setSingleLine(true);
        box.addView(f);
        box.addView(r);
        new AlertDialog.Builder(this).setTitle("Find & replace").setView(box)
                .setPositiveButton("Find next", (d, w) -> {
                    String q = f.getText().toString();
                    if (q.isEmpty()) return;
                    String t = edit.getText().toString();
                    int from = Math.max(edit.getSelectionEnd(), 0);
                    int i = t.toLowerCase(Locale.ROOT).indexOf(q.toLowerCase(Locale.ROOT), from);
                    if (i < 0) i = t.toLowerCase(Locale.ROOT).indexOf(q.toLowerCase(Locale.ROOT));
                    if (i < 0) { Ui.msg(this, "Not found"); return; }
                    edit.requestFocus();
                    edit.setSelection(i, i + q.length());
                })
                .setNeutralButton("Replace all", (d, w) -> {
                    String q = f.getText().toString();
                    if (q.isEmpty()) return;
                    String t = edit.getText().toString();
                    int count = t.split(java.util.regex.Pattern.quote(q), -1).length - 1;
                    edit.setText(t.replace(q, r.getText().toString()));
                    Ui.msg(this, count == 1 ? "Replaced 1 time" : "Replaced " + count + " times");
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void save(Runnable after) {
        String text = edit.getText().toString();
        new Thread(() -> {
            try {
                try (OutputStream o = file != null ? new FileOutputStream(file) : getContentResolver().openOutputStream(uri, "wt")) {
                    o.write(text.getBytes(StandardCharsets.UTF_8));
                }
                runOnUiThread(() -> { setDirty(false); Ui.msg(this, "Saved"); if (after != null) after.run(); });
            } catch (Exception e) {
                runOnUiThread(() -> Ui.msg(this, Perms.allFiles(this) ? "Couldn't save this file" : "Couldn't save — allow access to all files in Folders first"));
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (!dirty) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this).setTitle("Save changes?")
                .setPositiveButton("Save", (d, w) -> save(this::finish))
                .setNegativeButton("Don't save", (d, w) -> finish())
                .setNeutralButton("Cancel", null).show();
    }

}
