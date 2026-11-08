package com.nkt.mediaplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Videos tab in GRID view: thumbnail, length, title. */
public class VideoGridAdapter extends RecyclerView.Adapter<VideoGridAdapter.VH> {

    private final List<MediaInfo> items = new ArrayList<>();
    private final GalleryAdapter.Listener listener;
    private final Selection selection;
    private boolean compact;

    public VideoGridAdapter(GalleryAdapter.Listener l, Selection s) { listener = l; selection = s; }

    public void set(List<MediaInfo> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public List<MediaInfo> items() { return items; }

    /** Four or more per row: one line of title, no folder. */
    public void setCompact(boolean c) {
        if (compact == c) return;
        compact = c;
        notifyDataSetChanged();
    }

    public MediaInfo at(int p) { return p >= 0 && p < items.size() ? items.get(p) : null; }

    @Override public int getItemCount() { return items.size(); }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.video_cell, parent, false));
    }

    @Override
    public void onBindViewHolder(VH h, int position) {
        MediaInfo m = items.get(position);
        Thumbs.load(h.img, m, 320);
        h.dur.setText(m.durationMs > 0 ? Ui.time(m.durationMs) : "");
        h.dur.setVisibility(m.durationMs > 0 ? View.VISIBLE : View.GONE);
        h.title.setText(m.title);
        h.sub.setText((m.hidden ? "Hidden · " : "") + m.folder);
        h.sub.setVisibility(compact ? View.GONE : View.VISIBLE);
        h.title.setMaxLines(compact ? 1 : 2);
        h.title.setTextSize(compact ? 11f : 12.5f);
        boolean picking = selection.active(), picked = picking && selection.has(m);
        h.check.setVisibility(picking ? View.VISIBLE : View.GONE);
        h.check.setImageResource(picked ? R.drawable.ic_check_circle : R.drawable.ic_circle);
        h.dim.setVisibility(picked ? View.VISIBLE : View.GONE);
        h.title.setTextColor(h.itemView.getContext().getColor(picked ? R.color.accent : R.color.text));
        h.itemView.setOnClickListener(v -> {
            if (selection.active()) { selection.toggle(m); notifyItemChanged(h.getBindingAdapterPosition()); }
            else listener.onOpen(m, items);
        });
        h.itemView.setOnLongClickListener(v -> { listener.onLong(m); return true; });
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView img, check;
        final TextView dur, title, sub;
        final View dim;
        VH(View v) {
            super(v);
            v.findViewById(R.id.vc_frame).setClipToOutline(true);
            img = v.findViewById(R.id.vc_img);
            check = v.findViewById(R.id.vc_check);
            dur = v.findViewById(R.id.vc_dur);
            title = v.findViewById(R.id.vc_title);
            sub = v.findViewById(R.id.vc_sub);
            dim = v.findViewById(R.id.vc_dim);
        }
    }
}
