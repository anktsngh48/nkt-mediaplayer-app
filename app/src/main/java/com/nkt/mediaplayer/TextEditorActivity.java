package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text editor for .txt, .srt, .lrc, .json… : line numbers, undo/redo, find & replace, wrap, text size,
 * status line, keeps the file's encoding, asks before leaving with unsaved changes, keeps a draft.
 */
public class TextEditorActivity extends Activity {

    private static final int EDIT_LIMIT = 1024 * 1024, VIEW_LIMIT = 8 * 1024 * 1024;

    public static void open(Context c, Uri uri) {
        c.startActivity(new Intent(c, TextEditorActivity.class).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Uri uri;
    private String name = "";
    private Charset charset = StandardCharsets.UTF_8;
    private String encodingLabel = "UTF-8";
    private boolean bom = false, readOnly = false, dirty = false, applying = false;
    private CodeEdit edit;
    private TextView titleView, stateView, statusView, matchView;
    private View findBar;
    private EditText findInput, replaceInput;
    private ImageButton undoBtn, redoBtn, wrapBtn;
    private long fileTime = 0;

    // undo / redo
    private static final class Change { int start; CharSequence before, after; long at; }
    private final List<Change> undo = new ArrayList<>(), redo = new ArrayList<>();
    private Change typing;

    // find
    private final List<Integer> matches = new ArrayList<>();
    private int matchAt = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uri = getIntent().getData();
        if (uri == null) { finish(); return; }
        build();
        load();
    }

    // ── Screen ───────────────────────────────────────────────────────────────

    private void build() {
        int dp = Ui.dp(this, 1);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(4 * dp, 6 * dp, 4 * dp, 4 * dp);
        top.addView(iconBtn(R.drawable.ic_back, R.string.cd_back, v -> onBackPressed()));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(6 * dp, 0, 4 * dp, 0);
        titleView = new TextView(this);
        titleView.setTextColor(getColor(R.color.text));
        titleView.setTextSize(15);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        stateView = new TextView(this);
        stateView.setTextColor(getColor(R.color.muted));
        stateView.setTextSize(11.5f);
        titles.addView(titleView);
        titles.addView(stateView);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        undoBtn = iconBtn(R.drawable.ic_undo, R.string.cd_undo, v -> doUndo());
        redoBtn = iconBtn(R.drawable.ic_redo, R.string.cd_redo, v -> doRedo());
        top.addView(undoBtn);
        top.addView(redoBtn);
        top.addView(iconBtn(R.drawable.ic_search, R.string.cd_find, v -> toggleFind()));
        top.addView(iconBtn(R.drawable.ic_check, R.string.save_caps, v -> save(null)));
        root.addView(top);

        // find & replace
        LinearLayout fb = new LinearLayout(this);
        fb.setOrientation(LinearLayout.VERTICAL);
        fb.setPadding(12 * dp, 2 * dp, 12 * dp, 6 * dp);
        fb.setVisibility(View.GONE);
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        findInput = input(R.string.find_hint);
        findInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        findInput.addTextChangedListener(new SimpleWatcher(this::findAll));
        matchView = new TextView(this);
        matchView.setTextColor(getColor(R.color.muted));
        matchView.setTextSize(12);
        matchView.setPadding(8 * dp, 0, 4 * dp, 0);
        r1.addView(findInput, new LinearLayout.LayoutParams(0, 40 * dp, 1));
        r1.addView(matchView);
        r1.addView(iconBtn(R.drawable.ic_chevron_left, R.string.cd_prev, v -> step(-1)));
        r1.addView(iconBtn(R.drawable.ic_chevron_right, R.string.cd_next, v -> step(1)));
        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        r2.setPadding(0, 6 * dp, 0, 0);
        replaceInput = input(R.string.replace_hint);
        r2.addView(replaceInput, new LinearLayout.LayoutParams(0, 40 * dp, 1));
        r2.addView(textBtn(R.string.replace_caps, v -> replaceOne()));
        r2.addView(textBtn(R.string.all_caps, v -> replaceAll()));
        fb.addView(r1);
        fb.addView(r2);
        findBar = fb;
        root.addView(fb);

        edit = new CodeEdit(this);
        edit.setBackgroundResource(R.drawable.bg_editor);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setTextColor(getColor(R.color.text));
        edit.setTypeface(Typeface.MONOSPACE);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        edit.setTextSize(Prefs.editorSize(this));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        ep.setMargins(8 * dp, 0, 8 * dp, 0);
        root.addView(edit, ep);
        applyWrap();
        edit.addTextChangedListener(new TextWatcher() {
            CharSequence before;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!applying) before = s.subSequence(start, start + count).toString();
            }
            @Override public void onTextChanged(CharSequence s, int start, int count, int after) {
                if (applying) return;
                record(start, before, s.subSequence(start, start + after).toString());
            }
            @Override public void afterTextChanged(Editable s) {
                if (applying) return;
                setDirty(true);
                if (findBar.getVisibility() == View.VISIBLE) { ui.removeCallbacks(refind); ui.postDelayed(refind, 300); }
            }
        });
        edit.setOnSelectionChanged(this::updateStatus);

        // bottom: A− A+ WRAP · status
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(8 * dp, 4 * dp, 12 * dp, 6 * dp);
        bottom.addView(textBtn("A−", R.string.cd_smaller, v -> size(-1)));
        bottom.addView(textBtn("A+", R.string.cd_bigger, v -> size(1)));
        wrapBtn = iconBtn(R.drawable.ic_wrap, R.string.wrap_caps, v -> { Prefs.setEditorWrap(this, !Prefs.editorWrap(this)); applyWrap(); });
        bottom.addView(wrapBtn);
        statusView = new TextView(this);
        statusView.setTextColor(getColor(R.color.muted));
        statusView.setTextSize(11);
        statusView.setGravity(Gravity.END);
        statusView.setSingleLine(true);
        bottom.addView(statusView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bottom);

        setContentView(root);
        Ui.padForSystemBars(root);
        updateUndoButtons();
    }

    private ImageButton iconBtn(int icon, int desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        b.setBackgroundResource(android.R.color.transparent);
        b.setContentDescription(getString(desc));
        b.setOnClickListener(l);
        int s = Ui.dp(this, 42);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return b;
    }

    private TextView textBtn(int text, View.OnClickListener l) { return textBtn(getString(text), text, l); }

    private TextView textBtn(String text, int desc, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setContentDescription(getString(desc));
        b.setTypeface(Ui.orbitron(this));
        b.setTextSize(10);
        b.setTextColor(getColor(R.color.accent));
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.bg_segment_box);
        int dp = Ui.dp(this, 1);
        b.setPadding(10 * dp, 0, 10 * dp, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 34 * dp);
        lp.setMarginStart(6 * dp);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private EditText input(int hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setTextColor(getColor(R.color.text));
        e.setHintTextColor(getColor(R.color.muted));
        e.setBackgroundResource(R.drawable.bg_input);
        int dp = Ui.dp(this, 1);
        e.setPadding(10 * dp, 0, 10 * dp, 0);
        return e;
    }

    private void applyWrap() {
        boolean wrap = Prefs.editorWrap(this);
        edit.setHorizontallyScrolling(!wrap);
        if (wrapBtn != null) wrapBtn.setImageTintList(ColorStateList.valueOf(getColor(wrap ? R.color.accent : R.color.muted)));
    }

    private void size(int d) {
        int s = Math.max(9, Math.min(28, Prefs.editorSize(this) + d));
        Prefs.setEditorSize(this, s);
        edit.setTextSize(s);
    }

    // ── Loading & saving ─────────────────────────────────────────────────────

    private File draftFile() { return new File(new File(getFilesDir(), "drafts"), Lyrics.idOf(uri.toString()) + ".txt"); }

    private void load() {
        name = Ui.displayName(this, uri);
        String full = fullName();
        titleView.setText(full);
        stateView.setText(R.string.loading);
        edit.setEnabled(false);
        new Thread(() -> {
            byte[] data = null;
            boolean tooBig = false;
            try {
                if ("file".equals(uri.getScheme())) fileTime = new File(uri.getPath()).lastModified();
                try (InputStream in = open()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > VIEW_LIMIT) { tooBig = true; break; }
                    }
                    data = out.toByteArray();
                }
            } catch (Exception ignored) { }
            final boolean big = tooBig;
            final String text = data == null || big ? null : decode(data);
            final boolean ro = data != null && data.length > EDIT_LIMIT;
            // a draft from last time, for this same version of the file
            String draft = null;
            File df = draftFile();
            if (text != null && !ro && df.exists()) {
                try {
                    byte[] d = readAll(new FileInputStream(df));
                    String all = new String(d, StandardCharsets.UTF_8);
                    int nl = all.indexOf('\n');
                    if (nl > 0 && all.substring(0, nl).equals(String.valueOf(fileTime))) draft = all.substring(nl + 1);
                } catch (Exception ignored) { }
            }
            final String restored = draft;
            ui.post(() -> {
                if (big) { Ui.toast(this, R.string.editor_too_big); finish(); return; }
                if (text == null) { Ui.toast(this, R.string.cant_open_file); finish(); return; }
                readOnly = ro;
                applying = true;
                edit.setText(restored != null ? restored : text);
                applying = false;
                edit.setEnabled(true);
                if (readOnly) { edit.setKeyListener(null); edit.setTextIsSelectable(true); }
                setDirty(restored != null);
                updateStatus();
            });
        }).start();
    }

    private String fullName() {
        if ("file".equals(uri.getScheme()) && uri.getLastPathSegment() != null) return uri.getLastPathSegment();
        return name;
    }

    private InputStream open() throws Exception {
        if ("file".equals(uri.getScheme())) return new FileInputStream(uri.getPath());
        return getContentResolver().openInputStream(uri);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** UTF-8 (with or without BOM), UTF-16 with BOM, otherwise Windows-1252. */
    private String decode(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF) {
            bom = true; charset = StandardCharsets.UTF_8; encodingLabel = "UTF-8 BOM";
            return new String(d, 3, d.length - 3, StandardCharsets.UTF_8);
        }
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) {
            bom = true; charset = StandardCharsets.UTF_16LE; encodingLabel = "UTF-16 LE";
            return new String(d, 2, d.length - 2, StandardCharsets.UTF_16LE);
        }
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) {
            bom = true; charset = StandardCharsets.UTF_16BE; encodingLabel = "UTF-16 BE";
            return new String(d, 2, d.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer cb = dec.decode(ByteBuffer.wrap(d));
            charset = StandardCharsets.UTF_8; encodingLabel = "UTF-8";
            return cb.toString();
        } catch (CharacterCodingException e) {
            Charset w = Charset.forName("windows-1252");
            charset = w; encodingLabel = "Windows-1252";
            return new String(d, w);
        }
    }

    private void save(Runnable after) {
        if (readOnly) { Ui.toast(this, R.string.editor_read_only); return; }
        final String text = edit.getText().toString();
        new Thread(() -> {
            boolean ok;
            try {
                byte[] body = text.getBytes(charset);
                try (OutputStream out = "file".equals(uri.getScheme()) ? new FileOutputStream(uri.getPath())
                        : getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new Exception("no output");
                    if (bom) {
                        if (charset == StandardCharsets.UTF_8) out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                        else if (charset == StandardCharsets.UTF_16LE) out.write(new byte[]{(byte) 0xFF, (byte) 0xFE});
                        else if (charset == StandardCharsets.UTF_16BE) out.write(new byte[]{(byte) 0xFE, (byte) 0xFF});
                    }
                    out.write(body);
                }
                if ("file".equals(uri.getScheme())) {
                    fileTime = new File(uri.getPath()).lastModified();
                    android.media.MediaScannerConnection.scanFile(getApplicationContext(), new String[]{uri.getPath()}, null, null);
                }
                ok = true;
            } catch (Exception e) { ok = false; }
            final boolean done = ok;
            ui.post(() -> {
                Ui.toast(this, done ? R.string.saved_file : R.string.save_failed);
                if (done) {
                    setDirty(false);
                    //noinspection ResultOfMethodCallIgnored
                    draftFile().delete();
                    if (after != null) after.run();
                }
            });
        }).start();
    }

    private void setDirty(boolean d) {
        dirty = d;
        stateView.setText(readOnly ? getString(R.string.editor_read_only) : getString(d ? R.string.editor_edited : R.string.editor_saved));
        stateView.setTextColor(getColor(d ? R.color.accent : R.color.muted));
    }

    private void updateStatus() {
        if (edit == null || statusView == null) return;
        int pos = Math.max(0, edit.getSelectionStart());
        CharSequence t = edit.getText();
        int line = 1, col = 1;
        for (int i = 0; i < pos && i < t.length(); i++) { if (t.charAt(i) == '\n') { line++; col = 1; } else col++; }
        statusView.setText(getString(R.string.editor_status, line, col, encodingLabel, t.length()));
    }

    // ── Undo / redo ──────────────────────────────────────────────────────────

    private void record(int start, CharSequence before, CharSequence after) {
        long now = System.currentTimeMillis();
        // typing letters close together joins into one step
        if (typing != null && before.length() == 0 && after.length() == 1 && now - typing.at < 1200
                && typing.before.length() == 0 && start == typing.start + typing.after.length() && after.charAt(0) != '\n') {
            typing.after = typing.after.toString() + after;
            typing.at = now;
        } else {
            Change c = new Change();
            c.start = start; c.before = before; c.after = after; c.at = now;
            undo.add(c);
            if (undo.size() > 300) undo.remove(0);
            typing = c;
        }
        redo.clear();
        updateUndoButtons();
    }

    private void doUndo() {
        if (undo.isEmpty()) return;
        Change c = undo.remove(undo.size() - 1);
        apply(c.start, c.after.length(), c.before);
        redo.add(c);
        typing = null;
        updateUndoButtons();
    }

    private void doRedo() {
        if (redo.isEmpty()) return;
        Change c = redo.remove(redo.size() - 1);
        apply(c.start, c.before.length(), c.after);
        undo.add(c);
        typing = null;
        updateUndoButtons();
    }

    private void apply(int start, int len, CharSequence text) {
        Editable e = edit.getText();
        int s = Math.min(start, e.length()), end = Math.min(e.length(), start + len);
        applying = true;
        e.replace(s, end, text);
        applying = false;
        edit.setSelection(Math.min(e.length(), s + text.length()));
        setDirty(true);
    }

    private void updateUndoButtons() {
        undoBtn.setAlpha(undo.isEmpty() ? 0.35f : 1f);
        redoBtn.setAlpha(redo.isEmpty() ? 0.35f : 1f);
    }

    // ── Find & replace ───────────────────────────────────────────────────────

    private void toggleFind() {
        boolean show = findBar.getVisibility() != View.VISIBLE;
        findBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) { findInput.requestFocus(); findAll(); } else clearHighlights();
    }

    private final Runnable refind = this::findAll;

    private void findAll() {
        clearHighlights();
        matches.clear();
        matchAt = -1;
        String q = findInput.getText().toString();
        if (!q.isEmpty()) {
            String t = edit.getText().toString().toLowerCase(Locale.ROOT);
            String ql = q.toLowerCase(Locale.ROOT);
            for (int i = t.indexOf(ql); i >= 0 && matches.size() < 5000; i = t.indexOf(ql, i + Math.max(1, ql.length()))) matches.add(i);
            Spannable sp = edit.getText();
            for (int i : matches) sp.setSpan(new Hit(), i, i + q.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!matches.isEmpty()) {
                int cur = edit.getSelectionStart();
                matchAt = 0;
                for (int k = 0; k < matches.size(); k++) if (matches.get(k) >= cur) { matchAt = k; break; }
            }
        }
        showMatch(false);
    }

    private static final class Hit extends BackgroundColorSpan { Hit() { super(0x5500E5FF); } }

    private void clearHighlights() {
        Spannable sp = edit.getText();
        for (Hit h : sp.getSpans(0, sp.length(), Hit.class)) sp.removeSpan(h);
    }

    private void step(int d) {
        if (matches.isEmpty()) return;
        matchAt = (matchAt + d + matches.size()) % matches.size();
        showMatch(true);
    }

    private void showMatch(boolean move) {
        matchView.setText(matches.isEmpty() ? getString(R.string.no_match) : getString(R.string.match_n_of, matchAt + 1, matches.size()));
        if (matchAt < 0 || matches.isEmpty()) return;
        int s = matches.get(matchAt), e = s + findInput.getText().length();
        edit.setSelection(s, Math.min(e, edit.length()));
        Layout l = edit.getLayout();
        if (l != null && move) {
            int line = l.getLineForOffset(s);
            edit.scrollTo(edit.getScrollX(), Math.max(0, l.getLineTop(line) - edit.getHeight() / 3));
        }
    }

    private void replaceOne() {
        if (readOnly || matches.isEmpty() || matchAt < 0) return;
        int s = matches.get(matchAt), len = findInput.getText().length();
        Editable e = edit.getText();
        e.replace(s, s + len, replaceInput.getText().toString());   // recorded for undo by the watcher
        findAll();
    }

    private void replaceAll() {
        if (readOnly || matches.isEmpty()) return;
        int len = findInput.getText().length(), n = matches.size();
        String rep = replaceInput.getText().toString();
        Editable e = edit.getText();
        String before = e.toString();
        StringBuilder b = new StringBuilder(before);
        for (int k = matches.size() - 1; k >= 0; k--) b.replace(matches.get(k), matches.get(k) + len, rep);
        applying = true;
        e.replace(0, e.length(), b);
        applying = false;
        Change c = new Change();
        c.start = 0; c.before = before; c.after = b.toString(); c.at = System.currentTimeMillis();
        undo.add(c);
        redo.clear();
        typing = null;
        updateUndoButtons();
        setDirty(true);
        findAll();
        Ui.toast(this, getString(R.string.n_replaced, n));
    }

    // ── Leaving ──────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (findBar.getVisibility() == View.VISIBLE) { toggleFind(); return; }
        if (!dirty) { super.onBackPressed(); return; }
        Dialogs.builder(this)
                .setTitle(R.string.editor_unsaved_title)
                .setPositiveButton(R.string.save, (d, w) -> save(this::finish))
                .setNeutralButton(R.string.discard, (d, w) -> {
                    //noinspection ResultOfMethodCallIgnored
                    draftFile().delete();
                    dirty = false;
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Keeps a draft if the app is closed with unsaved changes. */
    @Override
    protected void onPause() {
        super.onPause();
        if (!dirty || readOnly || edit == null) return;
        final String text = edit.getText().toString();
        final long time = fileTime;
        new Thread(() -> {
            try {
                File f = draftFile();
                //noinspection ResultOfMethodCallIgnored
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) { out.write((time + "\n" + text).getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception ignored) { }
        }).start();
    }

    // ── The text box: line numbers in the left margin (wrapped lines share one number) ──

    static final class CodeEdit extends EditText {
        private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Runnable onSel;
        private int gutter;

        CodeEdit(Context c) {
            super(c);
            num.setColor(Color.argb(140, 150, 160, 180));
            num.setTypeface(Typeface.MONOSPACE);
            num.setTextAlign(Paint.Align.RIGHT);
        }

        void setOnSelectionChanged(Runnable r) { onSel = r; }

        @Override
        protected void onSelectionChanged(int s, int e) {
            super.onSelectionChanged(s, e);
            if (onSel != null) onSel.run();
        }

        @Override
        public void setTextSize(float size) {
            super.setTextSize(size);
            num.setTextSize(getTextSize() * 0.8f);
            updateGutter();
        }

        private void updateGutter() {
            int lines = Math.max(1, getLineCount());
            int digits = String.valueOf(Math.max(lines, 99)).length();
            int g = (int) (num.measureText("0") * digits + Ui.dp(getContext(), 14));
            if (g != gutter) {
                gutter = g;
                setPadding(g, Ui.dp(getContext(), 8), Ui.dp(getContext(), 8), Ui.dp(getContext(), 8));
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            updateGutter();
            Layout l = getLayout();
            if (l != null) {
                CharSequence t = getText();
                int first = l.getLineForVertical(getScrollY()), last = l.getLineForVertical(getScrollY() + getHeight());
                // number of the first visible line
                int n = 1;
                int startOff = l.getLineStart(first);
                for (int i = 0; i < startOff; i++) if (t.charAt(i) == '\n') n++;
                float x = getScrollX() + gutter - Ui.dp(getContext(), 6);
                for (int line = first; line <= last && line < l.getLineCount(); line++) {
                    int s = l.getLineStart(line);
                    boolean newLine = s == 0 || t.charAt(s - 1) == '\n';
                    if (newLine) {
                        canvas.drawText(String.valueOf(n), x, l.getLineBaseline(line) + getTotalPaddingTop(), num);
                    }
                    int e = l.getLineEnd(line);
                    if (e > 0 && e <= t.length() && t.charAt(e - 1) == '\n') n++;
                }
            }
            super.onDraw(canvas);
        }
    }

    /** afterTextChanged only. */
    static final class SimpleWatcher implements TextWatcher {
        private final Runnable r;
        SimpleWatcher(Runnable r) { this.r = r; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable s) { r.run(); }
    }
}
