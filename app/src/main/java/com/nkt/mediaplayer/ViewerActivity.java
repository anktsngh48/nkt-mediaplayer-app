package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen photos: swipe for next, pinch or double-tap to zoom, share, details, filmstrip. */
public class ViewerActivity extends Activity {

    // The list is handed over in memory (it can be thousands of photos)
    private static List<MediaInfo> pending = new ArrayList<>();
    private static int pendingIndex = 0;

    public static void open(Context c, List<MediaInfo> photos, int index) {
        prepare(photos, index);
        c.startActivity(new Intent(c, ViewerActivity.class));
    }

    /** Sets the list for the next start (used when the start intent is built elsewhere). */
    public static void prepare(List<MediaInfo> photos, int index) {
        pending = new ArrayList<>(photos);
        pendingIndex = index;
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private List<MediaInfo> items;
    private RecyclerView pager, film;
    private LinearLayoutManager pagerLm, filmLm;
    private View topBar, bottomBar;
    private TextView title, sub;
    private int current = 0;
    private android.widget.ImageButton favBtn;
    private String recorded;
    private boolean barsVisible = true;
    private FilmAdapter filmAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        Ui.fullScreen(this);
        items = pending;
        Intent in = getIntent();
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            // "Open with NKT Media Player" on a photo
            MediaInfo m = MediaInfo.link(in.getData(), Ui.displayName(this, in.getData()), false);
            m.image = true;
            items = new ArrayList<>();
            items.add(m);
            pendingIndex = 0;
        }
        current = Math.max(0, Math.min(pendingIndex, items.size() - 1));
        if (items.isEmpty()) { finish(); return; }

        topBar = findViewById(R.id.vw_top);
        bottomBar = findViewById(R.id.vw_bottom);
        title = findViewById(R.id.vw_title);
        sub = findViewById(R.id.vw_sub);
        findViewById(R.id.vw_back).setOnClickListener(v -> finish());
        favBtn = findViewById(R.id.vw_fav);
        favBtn.setOnClickListener(v -> {
            MediaInfo m = items.get(current);
            Ui.toast(this, Prefs.toggleFavourite(this, m) ? R.string.fav_photo_added : R.string.fav_photo_removed);
            updateFav();
        });
        findViewById(R.id.vw_share).setOnClickListener(v -> Share.send(this, items.get(current)));
        findViewById(R.id.vw_info).setOnClickListener(v -> Dialogs.details(this, items.get(current)));

        pager = findViewById(R.id.vw_pager);
        pagerLm = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        pager.setLayoutManager(pagerLm);
        pager.setAdapter(new PageAdapter());
        new PagerSnapHelper().attachToRecyclerView(pager);
        pager.scrollToPosition(current);
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                int p = pagerLm.findFirstCompletelyVisibleItemPosition();
                if (p >= 0 && p != current) { current = p; updateBars(); }
            }
        });

        film = findViewById(R.id.vw_film);
        filmLm = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        film.setLayoutManager(filmLm);
        filmAdapter = new FilmAdapter();
        film.setAdapter(filmAdapter);
        updateBars();
    }

    private void updateFav() {
        boolean fav = Prefs.isFavourite(this, items.get(current));
        favBtn.setImageResource(fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        favBtn.setColorFilter(getColor(fav ? R.color.accent : R.color.text));
    }

    private void updateBars() {
        MediaInfo m = items.get(current);
        if (favBtn != null) updateFav();
        if (!m.key().equals(recorded)) { recorded = m.key(); History.record(this, m); }
        title.setText(m.dateMs > 0
                ? DateFormat.getMediumDateFormat(this).format(new Date(m.dateMs)) + ", " + DateFormat.getTimeFormat(this).format(new Date(m.dateMs))
                : m.fileName);
        sub.setText(getString(R.string.n_of_total, m.folder, current + 1, items.size()));
        filmAdapter.notifyDataSetChanged();
        filmLm.scrollToPositionWithOffset(current, film.getWidth() / 2 - dp(24));
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        topBar.animate().alpha(barsVisible ? 1 : 0).setDuration(150).start();
        bottomBar.animate().alpha(barsVisible ? 1 : 0).setDuration(150).start();
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        if (!barsVisible) ui.postDelayed(() -> { if (!barsVisible) { topBar.setVisibility(View.INVISIBLE); bottomBar.setVisibility(View.INVISIBLE); } }, 160);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.fullScreen(this);
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    /** Decodes a photo at about screen size, the right way up. */
    private Bitmap decode(MediaInfo m) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int target = Math.max(dm.widthPixels, dm.heightPixels);
        try {
            if (Build.VERSION.SDK_INT >= 28 && !"file".equals(m.uri.getScheme())) {
                ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), m.uri);
                return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    decoder.setTargetSampleSize(Thumbs.sample(w, h, target));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(m.uri)) { BitmapFactory.decodeStream(in, null, o); }
            o.inSampleSize = Thumbs.sample(o.outWidth, o.outHeight, target);
            o.inJustDecodeBounds = false;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(m.uri)) { b = BitmapFactory.decodeStream(in, null, o); }
            return Thumbs.rotate(b, m.orientation);
        } catch (Throwable t) {
            return null;
        }
    }

    private class PageAdapter extends RecyclerView.Adapter<PageVH> {
        @Override public int getItemCount() { return items.size(); }

        @Override public PageVH onCreateViewHolder(ViewGroup parent, int viewType) {
            ZoomImageView z = new ZoomImageView(parent.getContext());
            z.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new PageVH(z);
        }

        @Override public void onBindViewHolder(PageVH h, int position) {
            MediaInfo m = items.get(position);
            String key = m.uri.toString();
            h.view.setTag(R.id.thumb_key, key);
            h.view.setImageDrawable(null);
            h.view.setOnTap(ViewerActivity.this::toggleBars);
            pool.execute(() -> {
                Bitmap b = decode(m);
                ui.post(() -> { if (b != null && key.equals(h.view.getTag(R.id.thumb_key))) h.view.setImageBitmap(b); });
            });
        }

        @Override public void onViewRecycled(PageVH h) { h.view.setImageDrawable(null); }
    }

    static class PageVH extends RecyclerView.ViewHolder {
        final ZoomImageView view;
        PageVH(ZoomImageView v) { super(v); view = v; }
    }

    private class FilmAdapter extends RecyclerView.Adapter<FilmVH> {
        @Override public int getItemCount() { return items.size(); }

        @Override public FilmVH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new FilmVH(LayoutInflater.from(parent.getContext()).inflate(R.layout.film_item, parent, false));
        }

        @Override public void onBindViewHolder(FilmVH h, int position) {
            Thumbs.load(h.img, items.get(position), 120);
            boolean on = position == current;
            h.frame.setSelected(on);
            h.img.setAlpha(on ? 1f : 0.6f);
            h.itemView.setOnClickListener(v -> {
                current = h.getBindingAdapterPosition();
                pager.scrollToPosition(current);
                updateBars();
            });
        }
    }

    static class FilmVH extends RecyclerView.ViewHolder {
        final ImageView img; final View frame;
        FilmVH(View v) { super(v); img = v.findViewById(R.id.fi_img); frame = v.findViewById(R.id.fi_frame); img.setClipToOutline(true); }
    }

    static String sizeText(Context c, long bytes) { return Formatter.formatShortFileSize(c, bytes); }
}
