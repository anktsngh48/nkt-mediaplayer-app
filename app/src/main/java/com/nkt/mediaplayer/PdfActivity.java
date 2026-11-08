package com.nkt.mediaplayer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reads PDFs with Android's built-in renderer: pages scroll down, pinch a page to zoom, night mode. */
public class PdfActivity extends Activity {
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private RecyclerView list;
    private LinearLayoutManager lm;
    private TextView pageBadge;
    private boolean night;
    private final ExecutorService one = Executors.newSingleThreadExecutor();
    private final LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 6)) {
        @Override protected int sizeOf(Integer k, Bitmap b) { return b.getByteCount(); }
    };
    private File file;
    private Uri uri;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String p = getIntent().getStringExtra("path");
        if (p != null) file = new File(p);
        else uri = getIntent().getData();
        try {
            pfd = file != null ? ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    : getContentResolver().openFileDescriptor(uri, "r");
            renderer = new PdfRenderer(pfd);
        } catch (Exception e) {
            Ui.msg(this, "Couldn't open this PDF (it may be protected with a password)");
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout col = Ui.col(this);
        root.addView(col, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        LinearLayout top = Ui.row(this);
        top.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), Ui.dp(this, 8));
        ImageButton back = Ui.roundBtn(this, "back", 44, Ui.TEXT, true);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        LinearLayout tt = Ui.col(this);
        tt.setPadding(Ui.dp(this, 10), 0, 0, 0);
        String n = file != null ? file.getName() : Media.fromUri(this, uri, -1).name;
        TextView name = Ui.single(Ui.text(this, n, 15, Ui.TEXT));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        tt.addView(name);
        tt.addView(Ui.text(this, renderer.getPageCount() + " pages" + (file != null ? " · " + Ui.size(file.length()) : ""), 11, Ui.MUTED));
        top.addView(tt, Ui.lpw(0, Ui.WRAP, 1));
        ImageButton go = Ui.iconBtn(this, "search", 44, Ui.ACCENT);
        go.setContentDescription("Go to page");
        go.setOnClickListener(v -> goToPage());
        ImageButton moon = Ui.iconBtn(this, "moon", 44, Ui.TEXT);
        moon.setOnClickListener(v -> {
            night = !night;
            moon.setImageDrawable(new Icon(this, "moon", night ? Ui.ACCENT : Ui.TEXT));
            list.getAdapter().notifyDataSetChanged();
        });
        ImageButton share = Ui.iconBtn(this, "share", 44, Ui.TEXT);
        share.setOnClickListener(v -> { if (file != null) Media.shareFile(this, file); });
        top.addView(go);
        top.addView(moon);
        top.addView(share);
        col.addView(top);
        list = new RecyclerView(this);
        lm = new LinearLayoutManager(this);
        list.setLayoutManager(lm);
        list.setAdapter(new PageAdapter());
        list.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 24));
        list.setClipToPadding(false);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) { updateBadge(); }
        });
        col.addView(list, Ui.lpw(Ui.MATCH, 0, 1));
        pageBadge = Ui.label(this, "", 11, Ui.ACCENT, 0.05f);
        pageBadge.setBackground(Ui.rect(this, 0xCC05080F, 14, 0x5900E5FF, 1));
        pageBadge.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.END);
        bp.setMargins(0, 0, Ui.dp(this, 20), Ui.dp(this, 28));
        root.addView(pageBadge, bp);
        setContentView(root);
        Ui.edgeToEdge(this, root);
        updateBadge();
    }

    private void updateBadge() {
        int p = Math.max(0, lm.findFirstVisibleItemPosition());
        pageBadge.setText((p + 1) + " / " + renderer.getPageCount());
    }

    private void goToPage() {
        EditText e = new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setTextColor(Ui.TEXT);
        e.setHint("1 – " + renderer.getPageCount());
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(e);
        new android.app.AlertDialog.Builder(this).setTitle("Go to page").setView(box).setPositiveButton("Go", (d, w) -> {
            try {
                int p = Integer.parseInt(e.getText().toString()) - 1;
                lm.scrollToPositionWithOffset(Math.max(0, Math.min(renderer.getPageCount() - 1, p)), 0);
            } catch (Exception ignored) {}
        }).setNegativeButton("Cancel", null).show();
    }

    private Bitmap render(int i, int width) {
        Bitmap c = cache.get(i);
        if (c != null && c.getWidth() == width) return c;
        synchronized (this) {
            PdfRenderer.Page page = renderer.openPage(i);
            int h = (int) ((float) width * page.getHeight() / page.getWidth());
            Bitmap b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
            new Canvas(b).drawColor(Color.WHITE);
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            cache.put(i, b);
            return b;
        }
    }

    @Override
    protected void onDestroy() {
        one.shutdownNow();
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    final class PageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return renderer.getPageCount(); }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            ZoomImageView z = new ZoomImageView(PdfActivity.this);
            z.setBackgroundColor(0xFF1A2232);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP);
            lp.topMargin = Ui.dp(PdfActivity.this, 10);
            z.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(z) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
            ZoomImageView z = (ZoomImageView) vh.itemView;
            int width = getResources().getDisplayMetrics().widthPixels - Ui.dp(PdfActivity.this, 24);
            PdfRenderer.Page pg;
            int h;
            synchronized (PdfActivity.this) {
                pg = renderer.openPage(pos);
                h = (int) ((float) width * pg.getHeight() / pg.getWidth());
                pg.close();
            }
            z.getLayoutParams().height = h;
            z.setImageDrawable(null);
            z.setTag(pos);
            if (night) {
                ColorMatrix m = new ColorMatrix(new float[]{-1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0});
                z.setColorFilter(new ColorMatrixColorFilter(m));
            } else z.clearColorFilter();
            int renderW = Math.min(width * 2, 2400);
            one.execute(() -> {
                try {
                    Bitmap b = render(pos, renderW);
                    z.post(() -> { if (Integer.valueOf(pos).equals(z.getTag())) z.setImageBitmap(b); });
                } catch (Exception ignored) {}
            });
        }
    }
}
