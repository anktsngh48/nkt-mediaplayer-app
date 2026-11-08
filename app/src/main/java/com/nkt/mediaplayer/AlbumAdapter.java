package com.nkt.mediaplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** The strip of albums (folders) at the top of the gallery. */
public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.VH> {

    public static class Album {
        public final String name; public final int count; public final MediaInfo cover; public final boolean hidden;
        public Album(String n, int c, MediaInfo cover, boolean hidden) { name = n; count = c; this.cover = cover; this.hidden = hidden; }
    }

    public interface Listener { void onPick(Album a); }

    private final List<Album> albums = new ArrayList<>();
    private final Listener listener;
    private String selected;

    public AlbumAdapter(Listener l) { listener = l; }

    public void set(List<Album> list, String selectedName) {
        albums.clear();
        albums.addAll(list);
        selected = selectedName;
        notifyDataSetChanged();
    }

    @Override public int getItemCount() { return albums.size(); }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.album_item, parent, false));
    }

    @Override
    public void onBindViewHolder(VH h, int position) {
        Album a = albums.get(position);
        h.name.setText(a.name);
        h.count.setText((a.hidden ? "Hidden · " : "") + a.count);
        boolean on = a.name.equals(selected);
        h.frame.setSelected(on);
        if (a.cover != null) Thumbs.load(h.img, a.cover); else Thumbs.showPlaceholder(h.img, R.drawable.ic_image);
        h.itemView.setOnClickListener(v -> listener.onPick(a));
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView img; final TextView name, count; final View frame;
        VH(View v) {
            super(v);
            img = v.findViewById(R.id.al_img);
            name = v.findViewById(R.id.al_name);
            count = v.findViewById(R.id.al_count);
            frame = v.findViewById(R.id.al_frame);
            img.setClipToOutline(true);
        }
    }
}
