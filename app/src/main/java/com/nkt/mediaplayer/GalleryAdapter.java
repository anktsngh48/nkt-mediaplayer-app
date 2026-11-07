package com.nkt.mediaplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Photo + video grid with date headers (by day, or by month when zoomed out). Also the Videos grid (no headers). */
public class GalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int HEADER = 0, CELL = 1;

    public interface Listener {
        void onOpen(MediaInfo m, List<MediaInfo> all);
        void onLong(MediaInfo m);
    }

    private static class Entry {
        final String header; final MediaInfo media;
        Entry(String h, MediaInfo m) { header = h; media = m; }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<MediaInfo> media = new ArrayList<>();
    private final Listener listener;
    private int cols = 3;
    private final Selection selection;

    public GalleryAdapter(Listener l, Selection s) { listener = l; selection = s; }

    public List<MediaInfo> media() { return media; }

    /** dateHeaders=false: plain grid (videos tab, or gallery sorted by name/size). */
    public void set(List<MediaInfo> items, int columns, boolean dateHeaders) {
        cols = columns;
        media.clear();
        media.addAll(items);
        entries.clear();
        boolean byMonth = columns >= 5;
        String last = null;
        for (MediaInfo m : items) {
            if (dateHeaders) {
                String h = byMonth ? monthLabel(m.dateMs) : dayLabel(m.dateMs);
                if (!h.equals(last)) { entries.add(new Entry(h, null)); last = h; }
            }
            entries.add(new Entry(null, m));
        }
        notifyDataSetChanged();
    }

    /** For the side scroll bar: the date (or first letter) at a position. */
    public String label(int position, boolean byDate) {
        if (position < 0 || position >= entries.size()) return "";
        for (int p = position; p >= 0; p--) if (entries.get(p).header != null) return entries.get(p).header;
        Entry e = entries.get(position);
        if (e.media == null) return "";
        if (byDate) return monthLabel(e.media.dateMs);
        return e.media.title.isEmpty() ? "" : e.media.title.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    public GridLayoutManager.SpanSizeLookup spans() {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return position < entries.size() && entries.get(position).header != null ? cols : 1;
            }
        };
    }

    private static String dayLabel(long ms) {
        if (ms <= 0) return "UNKNOWN DATE";
        Calendar c = Calendar.getInstance(), now = Calendar.getInstance();
        c.setTimeInMillis(ms);
        if (sameDay(c, now)) return "TODAY";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(c, now)) return "YESTERDAY";
        String fmt = c.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR) ? "EEE, d MMM" : "d MMM yyyy";
        return new SimpleDateFormat(fmt, Locale.getDefault()).format(new Date(ms)).toUpperCase(Locale.getDefault());
    }

    private static String monthLabel(long ms) {
        if (ms <= 0) return "UNKNOWN DATE";
        return new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(new Date(ms)).toUpperCase(Locale.getDefault());
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    @Override public int getItemCount() { return entries.size(); }
    @Override public int getItemViewType(int position) { return entries.get(position).header != null ? HEADER : CELL; }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (type == HEADER) return new HeaderVH(inf.inflate(R.layout.gallery_header, parent, false));
        return new CellVH(inf.inflate(R.layout.gallery_cell, parent, false));
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
        Entry e = entries.get(position);
        if (h instanceof HeaderVH) {
            ((HeaderVH) h).text.setText(e.header);
            ((HeaderVH) h).text.setTypeface(Ui.orbitron(h.itemView.getContext()));
            return;
        }
        CellVH c = (CellVH) h;
        MediaInfo m = e.media;
        Thumbs.load(c.img, m, cols >= 5 ? 120 : 256);
        c.badge.setVisibility(m.video ? View.VISIBLE : View.GONE);
        c.badge.setText(m.video && m.durationMs > 0 ? Ui.time(m.durationMs) : "");
        c.hidden.setVisibility(m.hidden ? View.VISIBLE : View.GONE);
        boolean picking = selection.active(), picked = picking && selection.has(m);
        c.check.setVisibility(picking ? View.VISIBLE : View.GONE);
        c.check.setImageResource(picked ? R.drawable.ic_check_circle : R.drawable.ic_circle);
        c.dim.setVisibility(picked ? View.VISIBLE : View.GONE);
        c.itemView.setOnClickListener(v -> {
            if (selection.active()) { selection.toggle(m); notifyItemChanged(c.getBindingAdapterPosition()); }
            else listener.onOpen(m, media);
        });
        c.itemView.setOnLongClickListener(v -> { listener.onLong(m); return true; });
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView text;
        HeaderVH(View v) { super(v); text = v.findViewById(R.id.gh_text); }
    }

    static class CellVH extends RecyclerView.ViewHolder {
        final ImageView img, check; final TextView badge; final View hidden, dim;
        CellVH(View v) {
            super(v);
            check = v.findViewById(R.id.gc_check);
            dim = v.findViewById(R.id.gc_dim);
            img = v.findViewById(R.id.gc_img);
            badge = v.findViewById(R.id.gc_badge);
            hidden = v.findViewById(R.id.gc_hidden);
        }
    }
}
