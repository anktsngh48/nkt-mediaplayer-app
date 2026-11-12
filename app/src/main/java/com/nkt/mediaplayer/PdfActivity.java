package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PDF reader: pages one under another, pinch or double-tap a page to zoom, page bubble, go to page, night mode. */
public class PdfActivity extends Activity {

    public static void open(Context c, Uri uri) {
        c.startActivity(new Intent(c, PdfActivity.class).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService renderThread = Executors.newSingleThreadExecutor();   // PdfRenderer: one page at a time
    private ParcelFileDescriptor fd;
    private PdfRenderer pdf;
    private int count = 0;
    private float[] ratio;                      // height / width per page
    private RecyclerView list;
    private LinearLayoutManager lm;
    private TextView bubble, titleView;
    private ImageButton nightBtn;
    private Uri uri;
    private LruCache<Integer, Bitmap> pages;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uri = getIntent().getData();
        if (uri == null) { finish(); return; }
        int max = (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 96L * 1024 * 1024);
        pages = new LruCache<Integer, Bitmap>(max) {
            @Override protected int sizeOf(Integer k, Bitmap b) { return b.getByteCount(); }
        };
        build();
        openPdf();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (ExternalOpen.isExternal(getIntent()) && ExternalOpen.back(this, uri)) return;
        super.onBackPressed();
    }

    private void build() {
        int dp = Ui.dp(this, 1);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0E14);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(4 * dp, 6 * dp, 6 * dp, 4 * dp);
        top.setBackgroundColor(getColor(R.color.bg));
        ImageButton back = button(R.drawable.ic_back, R.string.cd_back);
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back);
        titleView = new TextView(this);
        titleView.setTextColor(getColor(R.color.text));
        titleView.setTextSize(15);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleView.setPadding(6 * dp, 0, 6 * dp, 0);
        top.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nightBtn = button(R.drawable.ic_night, R.string.cd_night);
        nightBtn.setOnClickListener(v -> { Prefs.setPdfNight(this, !Prefs.pdfNight(this)); applyNight(); });
        top.addView(nightBtn);
        ImageButton more = button(R.drawable.ic_open, R.string.open_with_caps);
        more.setOnClickListener(v -> openWith());
        top.addView(more);
        col.addView(top);

        list = new RecyclerView(this);
        lm = new LinearLayoutManager(this);
        list.setLayoutManager(lm);
        list.setItemViewCacheSize(4);
        list.setPadding(0, 6 * dp, 0, 40 * dp);
        list.setClipToPadding(false);
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(col);

        bubble = new TextView(this);
        bubble.setBackgroundResource(R.drawable.bg_indicator);
        bubble.setTextColor(getColor(R.color.accent));
        bubble.setTextSize(13);
        bubble.setPadding(14 * dp, 7 * dp, 14 * dp, 7 * dp);
        bubble.setOnClickListener(v -> goToPage());
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = 18 * dp;
        root.addView(bubble, bp);
        setContentView(root);
        Ui.padForSystemBars(root);

        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) { updateBubble(); }
        });
        applyNight();
    }

    private ImageButton button(int icon, int desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        b.setBackgroundResource(android.R.color.transparent);
        b.setContentDescription(getString(desc));
        int s = Ui.dp(this, 44);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return b;
    }

    private void openWith() {
        MediaInfo m = MediaInfo.link(uri, titleView.getText().toString(), false);
        m.other = true;
        m.fileName = titleView.getText().toString();
        FileOps.openWith(this, m);
    }

    private void openPdf() {
        String name = "file".equals(uri.getScheme()) && uri.getLastPathSegment() != null ? uri.getLastPathSegment() : Ui.displayName(this, uri) + ".pdf";
        titleView.setText(name);
        renderThread.execute(() -> {
            try {
                ParcelFileDescriptor f = "file".equals(uri.getScheme())
                        ? ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY)
                        : getContentResolver().openFileDescriptor(uri, "r");
                if (f == null) throw new Exception("no file");
                PdfRenderer r = new PdfRenderer(f);
                int n = r.getPageCount();
                float[] rs = new float[n];
                for (int i = 0; i < n; i++) {
                    try (PdfRenderer.Page p = r.openPage(i)) { rs[i] = p.getHeight() / (float) Math.max(1, p.getWidth()); }
                }
                ui.post(() -> {
                    fd = f;
                    pdf = r;
                    count = n;
                    ratio = rs;
                    list.setAdapter(new Pages());
                    int last = Prefs.pdfPage(this, uri.toString());
                    if (last > 0 && last < n) lm.scrollToPositionWithOffset(last, 0);
                    updateBubble();
                });
            } catch (Throwable t) {
                ui.post(() -> { Ui.toast(this, R.string.pdf_locked); openWith(); finish(); });
            }
        });
    }

    private int currentPage() {
        int p = lm.findFirstVisibleItemPosition();
        return Math.max(0, p);
    }

    private void updateBubble() {
        if (count == 0) return;
        bubble.setText(getString(R.string.pdf_page, currentPage() + 1, count));
    }

    private void goToPage() {
        if (count == 0) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 – " + count);
        FrameLayout box = new FrameLayout(this);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        Dialogs.builder(this)
                .setTitle(R.string.go_to_page)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        int p = Integer.parseInt(input.getText().toString().trim()) - 1;
                        lm.scrollToPositionWithOffset(Math.max(0, Math.min(count - 1, p)), 0);
                        ui.post(this::updateBubble);
                    } catch (Exception ignored) { }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private ColorMatrixColorFilter nightFilter() {
        // invert brightness, keep colours readable
        ColorMatrix m = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0});
        return new ColorMatrixColorFilter(m);
    }

    private void applyNight() {
        boolean night = Prefs.pdfNight(this);
        nightBtn.setImageTintList(ColorStateList.valueOf(getColor(night ? R.color.accent : R.color.muted)));
        if (list.getAdapter() != null) list.getAdapter().notifyDataSetChanged();
    }

    private class Pages extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return count; }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            ZoomImageView z = new ZoomImageView(parent.getContext());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100);
            int m = Ui.dp(PdfActivity.this, 5);
            lp.setMargins(m, m, m, m);
            z.setLayoutParams(lp);
            z.setBackgroundColor(Color.WHITE);
            return new RecyclerView.ViewHolder(z) { };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            ZoomImageView z = (ZoomImageView) h.itemView;
            int width = list.getWidth() > 0 ? list.getWidth() - Ui.dp(PdfActivity.this, 10) : getResources().getDisplayMetrics().widthPixels;
            ViewGroup.LayoutParams lp = z.getLayoutParams();
            lp.height = Math.round(width * ratio[position]);
            z.setLayoutParams(lp);
            boolean night = Prefs.pdfNight(PdfActivity.this);
            z.setColorFilter(night ? nightFilter() : null);
            z.setBackgroundColor(night ? Color.BLACK : Color.WHITE);
            z.setTag(R.id.thumb_key, position);
            Bitmap b = pages.get(position);
            if (b != null) { z.setImageBitmap(b); return; }
            z.setImageDrawable(null);
            final int target = Math.min(2400, Math.round(width * 1.6f));    // sharp when zoomed a little
            renderThread.execute(() -> {
                if (pdf == null) return;
                Bitmap out;
                try (PdfRenderer.Page p = pdf.openPage(position)) {
                    int w = target, hgt = Math.round(target * ratio[position]);
                    out = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888);
                    out.eraseColor(Color.WHITE);
                    p.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } catch (Throwable t) { return; }
                pages.put(position, out);
                ui.post(() -> { if (Integer.valueOf(position).equals(z.getTag(R.id.thumb_key))) z.setImageBitmap(out); });
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (count > 0) Prefs.setPdfPage(this, uri.toString(), currentPage());
    }

    @Override
    protected void onDestroy() {
        final PdfRenderer r = pdf;
        final ParcelFileDescriptor f = fd;
        pdf = null;
        renderThread.execute(() -> {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
            try { if (f != null) f.close(); } catch (Throwable ignored) { }
        });
        renderThread.shutdown();
        super.onDestroy();
    }
}
