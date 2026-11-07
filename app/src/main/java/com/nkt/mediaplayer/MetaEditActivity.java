package com.nkt.mediaplayer;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;

/** Edit a song's title, artist, album, album artist, genre, year, track and album art. */
public class MetaEditActivity extends Activity {

    private static final String EXTRA_URI = "uri", EXTRA_PATH = "path", EXTRA_NAME = "name", EXTRA_TITLE = "title";
    private static final int REQ_ART = 1, REQ_WRITE = 2, REQ_LEGACY = 3;

    public static void open(Context c, MediaInfo m) {
        if (m == null) return;
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(c, R.string.meta_needs_8, Toast.LENGTH_LONG).show();
            return;
        }
        String scheme = m.uri.getScheme();
        if (!"content".equals(scheme) && !"file".equals(scheme)) {
            Toast.makeText(c, R.string.meta_only_local, Toast.LENGTH_SHORT).show();
            return;
        }
        c.startActivity(new Intent(c, MetaEditActivity.class)
                .putExtra(EXTRA_URI, m.uri.toString())
                .putExtra(EXTRA_PATH, m.path)
                .putExtra(EXTRA_NAME, m.fileName)
                .putExtra(EXTRA_TITLE, m.title));
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Uri uri;
    private String path, name;
    private File work;                         // private copy that gets edited
    private TagIO.Data data;
    private boolean artChanged = false, busy = false;
    private final EditText[] inputs = new EditText[TagIO.KEYS.length];
    private ImageView art;
    private TextView save;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_meta_edit);
        Ui.padForSystemBars(findViewById(R.id.root));
        ((TextView) findViewById(R.id.me_title)).setTypeface(Ui.orbitron(this));
        save = findViewById(R.id.me_save);
        save.setTypeface(Ui.orbitron(this));
        art = findViewById(R.id.me_art);
        art.setClipToOutline(true);

        uri = Uri.parse(getIntent().getStringExtra(EXTRA_URI));
        path = nz(getIntent().getStringExtra(EXTRA_PATH));
        name = nz(getIntent().getStringExtra(EXTRA_NAME));
        if (name.isEmpty()) name = Ui.displayName(this, uri);
        if (path.isEmpty() && "file".equals(uri.getScheme())) path = nz(uri.getPath());
        ((TextView) findViewById(R.id.me_file)).setText(path.isEmpty() ? name : path);

        int[] labels = {R.string.f_title, R.string.f_artist, R.string.f_album, R.string.f_album_artist,
                R.string.f_genre, R.string.f_year, R.string.f_track};
        LinearLayout fields = findViewById(R.id.me_fields);
        for (int i = 0; i < labels.length; i++) {
            View row = getLayoutInflater().inflate(R.layout.meta_field, fields, false);
            ((TextView) row.findViewById(R.id.mf_label)).setText(labels[i]);
            inputs[i] = row.findViewById(R.id.mf_input);
            if (i >= 5) inputs[i].setInputType(InputType.TYPE_CLASS_NUMBER);
            else inputs[i].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            inputs[i].setEnabled(false);
            fields.addView(row);
        }

        findViewById(R.id.me_back).setOnClickListener(v -> finish());
        findViewById(R.id.me_art_change).setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(Intent.createChooser(pick, getString(R.string.change_art)), REQ_ART); }
            catch (Exception e) { Toast.makeText(this, R.string.no_picker, Toast.LENGTH_SHORT).show(); }
        });
        findViewById(R.id.me_art_remove).setOnClickListener(v -> {
            if (data == null) return;
            data.art = null;
            artChanged = true;
            showArt();
        });
        save.setOnClickListener(v -> startSave());

        load();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ── Load ─────────────────────────────────────────────────────────────────

    private void load() {
        setBusy(true);
        new Thread(() -> {
            try {
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".mp3";
                File f = new File(getCacheDir(), "edit" + ext.toLowerCase(java.util.Locale.ROOT));
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(f)) {
                    if (in == null) throw new Exception("no input");
                    copy(in, out);
                }
                TagIO.Data d = TagIO.read(f);
                ui.post(() -> {
                    work = f;
                    data = d;
                    for (int i = 0; i < inputs.length; i++) { inputs[i].setText(d.values[i]); inputs[i].setEnabled(true); }
                    if (inputs[0].getText().length() == 0) inputs[0].setHint(getIntent().getStringExtra(EXTRA_TITLE));
                    showArt();
                    setBusy(false);
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    Toast.makeText(this, R.string.meta_cant_read, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }).start();
    }

    private void showArt() {
        Bitmap b = null;
        if (data != null && data.art != null) {
            try { b = BitmapFactory.decodeByteArray(data.art, 0, data.art.length); } catch (Throwable ignored) { }
        }
        if (b != null) Thumbs.showBitmap(art, b); else Thumbs.showPlaceholder(art, R.drawable.ic_music);
    }

    private void setBusy(boolean b) {
        busy = b;
        save.setEnabled(!b);
        save.setAlpha(b ? 0.4f : 1f);
        save.setText(b ? R.string.working : R.string.save_caps);
    }

    // ── New album art ────────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode == REQ_ART && resultCode == RESULT_OK && result != null && result.getData() != null && data != null) {
            Uri img = result.getData();
            new Thread(() -> {
                byte[] jpg = toJpeg(img);
                ui.post(() -> {
                    if (jpg == null) { Toast.makeText(this, R.string.bad_image, Toast.LENGTH_SHORT).show(); return; }
                    data.art = jpg;
                    artChanged = true;
                    showArt();
                });
            }).start();
        } else if (requestCode == REQ_WRITE) {
            if (resultCode == RESULT_OK) writeBack();
            else { setBusy(false); Toast.makeText(this, R.string.meta_no_permission, Toast.LENGTH_LONG).show(); }
        }
    }

    /** Square-ish, at most 1000 px, JPEG. */
    private byte[] toJpeg(Uri img) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(img)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1000) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(img)) { b = BitmapFactory.decodeStream(in, null, o); }
            if (b == null) return null;
            int max = Math.max(b.getWidth(), b.getHeight());
            if (max > 1000) {
                float k = 1000f / max;
                b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 90, bos);
            return bos.toByteArray();
        } catch (Throwable t) { return null; }
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    private void startSave() {
        if (busy || data == null || work == null) return;
        for (int i = 0; i < inputs.length; i++) data.values[i] = inputs[i].getText().toString();
        setBusy(true);
        new Thread(() -> {
            try {
                TagIO.write(work, data, artChanged);
                ui.post(this::askAndWrite);
            } catch (Throwable t) {
                ui.post(() -> { setBusy(false); Toast.makeText(this, R.string.meta_cant_write, Toast.LENGTH_LONG).show(); });
            }
        }).start();
    }

    /** Ask Android for permission to change the original file (if needed), then copy the edited one over it. */
    private void askAndWrite() {
        boolean isFile = "file".equals(uri.getScheme());
        if (isFile || HiddenScanner.hasAllFilesAccess(this) && Build.VERSION.SDK_INT >= 30) { writeBack(); return; }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), Collections.singletonList(uri));
                startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
            } catch (Exception e) { writeBack(); }
            return;
        }
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY);
            return;
        }
        writeBack();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_LEGACY) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) writeBack();
        else { setBusy(false); Toast.makeText(this, R.string.meta_no_permission, Toast.LENGTH_LONG).show(); }
    }

    private void writeBack() {
        new Thread(() -> {
            try {
                if ("file".equals(uri.getScheme())) {
                    try (InputStream in = new FileInputStream(work); OutputStream out = new FileOutputStream(new File(uri.getPath()))) { copy(in, out); }
                } else {
                    try (InputStream in = new FileInputStream(work); OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new Exception("no output");
                        copy(in, out);
                    }
                }
                ui.post(this::done);
            } catch (Throwable t) {
                if (Build.VERSION.SDK_INT == 29 && t instanceof RecoverableSecurityException) {
                    IntentSender is = ((RecoverableSecurityException) t).getUserAction().getActionIntent().getIntentSender();
                    ui.post(() -> {
                        try { startIntentSenderForResult(is, REQ_WRITE, null, 0, 0, 0); }
                        catch (Exception e) { setBusy(false); Toast.makeText(this, R.string.meta_no_permission, Toast.LENGTH_LONG).show(); }
                    });
                    return;
                }
                ui.post(() -> { setBusy(false); Toast.makeText(this, R.string.meta_cant_write, Toast.LENGTH_LONG).show(); });
            }
        }).start();
    }

    private void done() {
        String p = path.isEmpty() && "file".equals(uri.getScheme()) ? uri.getPath() : path;
        if (p != null && !p.isEmpty()) MediaScannerConnection.scanFile(this, new String[]{p}, null, null);
        Thumbs.forget(new MediaInfo(-1, uri, "", "", "", -1, "", 0, false));
        Toast.makeText(this, R.string.meta_saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    @Override
    protected void onDestroy() {
        if (work != null) //noinspection ResultOfMethodCallIgnored
            work.delete();
        super.onDestroy();
    }
}
