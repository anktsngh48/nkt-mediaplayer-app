package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** FIND ALBUM ART: results from the internet for a song with no picture; pick one, or choose from the gallery. */
public final class ArtSheet {

    private ArtSheet() { }

    public static final int REQ_GALLERY = 61;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static Dialog open;
    private static MediaInfo forSong;
    private static Switch embedSwitch;

    public static void show(Activity a, MediaInfo song) {
        if (song == null) return;
        final MediaInfo m = song;
        forSong = m;
        View v = a.getLayoutInflater().inflate(R.layout.sheet_find_art, null);
        Dialog d = SpeedSheet.bottom(a, v);
        open = d;
        d.setOnDismissListener(x -> { if (open == d) open = null; });
        ((TextView) v.findViewById(R.id.fa_title)).setTypeface(Ui.orbitron(a));
        ((TextView) v.findViewById(R.id.fa_results_label)).setTypeface(Ui.orbitron(a));
        TextView gal = v.findViewById(R.id.fa_gallery), use = v.findViewById(R.id.fa_use);
        gal.setTypeface(Ui.orbitron(a));
        use.setTypeface(Ui.orbitron(a));
        embedSwitch = v.findViewById(R.id.fa_embed);
        embedSwitch.setChecked(true);                       // "Save this for audio permanently" is on to begin with
        v.findViewById(R.id.fa_close).setOnClickListener(x -> d.dismiss());

        final String[] q = {m.title, m.artist};
        final TextView query = v.findViewById(R.id.fa_query);
        final LinearLayout results = v.findViewById(R.id.fa_results);
        final TextView status = v.findViewById(R.id.fa_status);
        final List<ArtFinder.Hit> hits = new ArrayList<>();
        final int[] chosen = {-1};
        final int[] gen = {0};

        Runnable[] search = new Runnable[1];
        search[0] = () -> {
            final int my = ++gen[0];
            query.setText(q[1].isEmpty() ? q[0] : q[0] + "  ·  " + q[1]);
            results.removeAllViews();
            hits.clear();
            chosen[0] = -1;
            status.setText(Net.off() ? R.string.net_off_toast : R.string.art_searching);
            status.setVisibility(View.VISIBLE);
            new Thread(() -> {
                List<ArtFinder.Hit> got = ArtFinder.search(q[0], q[1], m.album);
                UI.post(() -> {
                    if (my != gen[0] || !d.isShowing()) return;
                    hits.addAll(got);
                    status.setVisibility(got.isEmpty() ? View.VISIBLE : View.GONE);
                    status.setText(Net.off() ? R.string.net_off_toast : R.string.art_none);
                    for (int i = 0; i < got.size(); i++) addTile(a, results, got.get(i), i, chosen, my, gen);
                    if (!got.isEmpty()) select(results, chosen, 0);
                });
            }).start();
        };
        search[0].run();

        v.findViewById(R.id.fa_query_row).setOnClickListener(x -> {
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = Ui.dp(a, 20);
            box.setPadding(pad, pad / 2, pad, 0);
            EditText t = new EditText(a);
            t.setHint(R.string.f_title);
            t.setSingleLine(true);
            t.setText(q[0]);
            EditText ar = new EditText(a);
            ar.setHint(R.string.f_artist);
            ar.setSingleLine(true);
            ar.setText(q[1]);
            box.addView(t);
            box.addView(ar);
            Dialogs.builder(a).setTitle(R.string.art_title).setView(box)
                    .setPositiveButton(R.string.search, (dd, w) -> {
                        q[0] = t.getText().toString().trim();
                        q[1] = ar.getText().toString().trim();
                        search[0].run();
                    }).setNegativeButton(R.string.cancel, null).show();
        });

        gal.setOnClickListener(x -> {
            try { a.startActivityForResult(ImagePickActivity.intent(a), REQ_GALLERY); }      // NKT's own picture chooser
            catch (Exception e) { Ui.toast(a, R.string.no_picker); }
        });

        use.setOnClickListener(x -> {
            if (chosen[0] < 0 || chosen[0] >= hits.size()) return;
            final ArtFinder.Hit h = hits.get(chosen[0]);
            final boolean embed = embedSwitch.isChecked();
            use.setEnabled(false);
            use.setAlpha(0.5f);
            new Thread(() -> {
                byte[] data = ArtFinder.download(h.fullUrl);
                UI.post(() -> {
                    use.setEnabled(true);
                    use.setAlpha(1f);
                    if (data == null) { Ui.toast(a, R.string.art_failed); return; }
                    store(a, m, data, embed);
                    d.dismiss();
                });
            }).start();
        });
    }

    private static void addTile(Activity a, LinearLayout row, ArtFinder.Hit h, int index, int[] chosen, int my, int[] gen) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(a, 104), LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMarginEnd(Ui.dp(a, 12));
        col.setLayoutParams(cp);
        ImageView img = new ImageView(a);
        img.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 104), Ui.dp(a, 104)));
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundResource(R.drawable.bg_thumb);
        img.setClipToOutline(true);
        Thumbs.showPlaceholder(img, R.drawable.ic_music);
        TextView cap = new TextView(a);
        cap.setText(h.source);
        cap.setTextSize(11);
        cap.setTextColor(a.getColor(R.color.muted));
        cap.setGravity(android.view.Gravity.CENTER);
        cap.setPadding(0, Ui.dp(a, 6), 0, 0);
        col.addView(img);
        col.addView(cap);
        col.setTag(img);
        col.setClickable(true);
        img.setClickable(false);
        col.setOnClickListener(x -> select(row, chosen, index));
        row.addView(col);
        new Thread(() -> {
            byte[] b = ArtFinder.download(h.thumbUrl);
            if (b == null) return;
            Bitmap bmp = Thumbs.decodeSampled(b, 240);
            if (bmp == null) return;
            UI.post(() -> { if (my == gen[0]) Thumbs.showBitmap(img, bmp); });
        }).start();
    }

    private static void select(LinearLayout row, int[] chosen, int index) {
        chosen[0] = index;
        float d = row.getResources().getDisplayMetrics().density;
        for (int i = 0; i < row.getChildCount(); i++) {
            View col = row.getChildAt(i);
            ImageView img = (ImageView) col.getTag();
            if (img == null) continue;
            boolean on = i == index;
            // the ring is drawn OVER the picture (a background would be hidden behind it); the others are dimmed
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(d * 14);
            g.setColor(android.graphics.Color.TRANSPARENT);
            g.setStroke(Math.round(d * (on ? 3 : 1)), on ? 0xFF00E5FF : 0x2200E5FF);
            img.setForeground(g);
            img.setAlpha(on ? 1f : 0.55f);
            col.setSelected(on);
        }
    }

    /** A picture from the gallery came back (called from the Now Playing screen). */
    public static void picked(Activity a, Uri uri) {
        final MediaInfo m = forSong;
        if (m == null || uri == null) return;
        final boolean embed = embedSwitch != null && embedSwitch.isChecked();
        new Thread(() -> {
            byte[] data = null;
            try (InputStream in = a.getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while (in != null && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
                data = bos.toByteArray();
            } catch (Throwable ignored) { }
            final byte[] got = data;
            UI.post(() -> {
                if (got == null || got.length == 0) { Ui.toast(a, R.string.art_failed); return; }
                store(a, m, got, embed);
                if (open != null) open.dismiss();
            });
        }).start();
    }

    /** Keeps the picture for the song (inside the app), and in the song file too when asked. */
    private static void store(Activity a, MediaInfo m, byte[] data, boolean embed) {
        new Thread(() -> {
            boolean ok = ArtStore.save(a, m, data);
            UI.post(() -> {
                if (!ok) { Ui.toast(a, R.string.art_failed); return; }
                Ui.toast(a, R.string.art_saved);
                PlaybackService.refreshArt();
                if (embed) {
                    File f = ArtStore.file(a, m);
                    MetaEditActivity.openWithArt(a, m, f, true);
                }
            });
        }).start();
    }
}
