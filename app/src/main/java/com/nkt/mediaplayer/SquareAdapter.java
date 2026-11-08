package com.nkt.mediaplayer;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** The music squares grid: a heading per section, then square tiles two per row. */
public class SquareAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int HEADER = 0, SQUARE = 1;

    public interface Listener { void onOpen(Groups.Group g); }

    private final List<Object> items = new ArrayList<>();   // String = heading, Group = tile
    private final Listener listener;

    public SquareAdapter(Listener l) { listener = l; }

    public void set(List<Groups.Section> sections) {
        items.clear();
        for (Groups.Section s : sections) {
            items.add(s.title);
            items.addAll(s.groups);
        }
        notifyDataSetChanged();
    }

    public boolean isEmpty() { return items.isEmpty(); }

    public GridLayoutManager.SpanSizeLookup spans() {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int p) { return p < items.size() && items.get(p) instanceof String ? 2 : 1; }
        };
    }

    @Override public int getItemCount() { return items.size(); }
    @Override public int getItemViewType(int p) { return items.get(p) instanceof String ? HEADER : SQUARE; }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (type == HEADER) return new RecyclerView.ViewHolder(inf.inflate(R.layout.gallery_header, parent, false)) { };
        return new SquareVH(inf.inflate(R.layout.square_item, parent, false));
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
        Object o = items.get(position);
        Context c = h.itemView.getContext();
        if (o instanceof String) {
            TextView t = h.itemView.findViewById(R.id.gh_text);
            t.setText((String) o);
            t.setTypeface(Ui.orbitron(c));
            return;
        }
        Groups.Group g = (Groups.Group) o;
        SquareVH s = (SquareVH) h;
        s.name.setText(g.name);
        s.count.setText(c.getResources().getQuantityString(R.plurals.songs, g.songs.size(), g.songs.size()));
        if (!g.songs.isEmpty()) Thumbs.load(s.art, g.songs.get(0), 256);
        s.itemView.setOnClickListener(v -> listener.onOpen(g));
    }

    static class SquareVH extends RecyclerView.ViewHolder {
        final ImageView art; final TextView name, count;
        SquareVH(View v) {
            super(v);
            art = v.findViewById(R.id.sq_art);
            name = v.findViewById(R.id.sq_name);
            count = v.findViewById(R.id.sq_count);
            v.findViewById(R.id.sq_card).setClipToOutline(true);
        }
    }
}
