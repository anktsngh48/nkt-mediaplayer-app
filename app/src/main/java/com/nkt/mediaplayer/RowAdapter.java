package com.nkt.mediaplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** One list for every tab: songs, videos, folders, playlists, history, settings and their action rows. */
public class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int MEDIA = 0, FOLDER = 1, PLAYLIST = 2, ACTION = 3, BACK = 4, TOGGLE = 5, HEADER = 6;

    public static class Row {
        public final int type;
        public final String title;
        public final String sub;
        public final MediaInfo media;     // MEDIA rows
        public final String key;          // folder / playlist name, or action id
        public final int icon;
        public boolean on;                // TOGGLE rows

        public Row(int type, String title, String sub, MediaInfo media, String key, int icon) {
            this.type = type; this.title = title; this.sub = sub; this.media = media; this.key = key; this.icon = icon;
        }

        public static Row media(MediaInfo m) { return new Row(MEDIA, m.title, m.subtitle(), m, null, 0); }
        public static Row media(MediaInfo m, String sub) { return new Row(MEDIA, m.title, sub, m, null, 0); }
        public static Row header(String text) { return new Row(HEADER, text, null, null, null, 0); }
        public static Row toggle(String title, String sub, String key, int icon, boolean on) {
            Row r = new Row(TOGGLE, title, sub, null, key, icon);
            r.on = on;
            return r;
        }
    }

    public interface Listener {
        void onClick(Row r, int position);
        void onLongClick(Row r, int position);
        void onMore(Row r, int position);
        default void onToggle(Row r, boolean on) { }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Listener listener;
    private final Selection selection;
    private String playingId;

    public RowAdapter(Listener l, Selection s) { listener = l; selection = s; }

    public void setRows(List<Row> list) {
        rows.clear();
        rows.addAll(list);
        notifyDataSetChanged();
    }

    public List<Row> rows() { return rows; }

    public void setPlaying(String mediaId) {
        if (mediaId == null ? playingId == null : mediaId.equals(playingId)) return;
        playingId = mediaId;
        notifyDataSetChanged();
    }

    @Override public int getItemCount() { return rows.size(); }
    @Override public int getItemViewType(int p) { return rows.get(p).type == HEADER ? 1 : 0; }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == 1) return new HeaderVH(inf.inflate(R.layout.gallery_header, parent, false));
        return new VH(inf.inflate(R.layout.row_item, parent, false));
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        Row r = rows.get(position);
        if (holder instanceof HeaderVH) {
            TextView t = ((HeaderVH) holder).text;
            t.setText(r.title);
            t.setTypeface(Ui.orbitron(t.getContext()));
            return;
        }
        VH h = (VH) holder;
        h.title.setText(r.title);
        h.sub.setText(r.sub == null ? "" : r.sub);
        h.sub.setVisibility(r.sub == null || r.sub.isEmpty() ? View.GONE : View.VISIBLE);

        boolean picking = selection.active() && r.type == MEDIA;
        boolean picked = picking && selection.has(r.media);
        boolean playing = r.type == MEDIA && playingId != null && playingId.equals(r.media.uri.toString());
        h.title.setTextColor(h.itemView.getContext().getColor(playing || picked ? R.color.accent : R.color.text));
        h.itemView.setSelected(playing);
        h.itemView.setActivated(picked);
        h.check.setVisibility(picking ? View.VISIBLE : View.GONE);
        h.check.setImageResource(picked ? R.drawable.ic_check_circle : R.drawable.ic_circle);

        h.toggle.setOnCheckedChangeListener(null);
        h.toggle.setVisibility(r.type == TOGGLE ? View.VISIBLE : View.GONE);
        if (r.type == TOGGLE) {
            h.toggle.setChecked(r.on);
            h.toggle.setOnCheckedChangeListener((b, on) -> { r.on = on; listener.onToggle(r, on); });
        }
        if (r.type == MEDIA) {
            Thumbs.load(h.thumb, r.media);
            h.more.setVisibility(picking ? View.GONE : View.VISIBLE);
        } else {
            h.thumb.setTag(R.id.thumb_key, null);
            Thumbs.showPlaceholder(h.thumb, r.icon);
            h.more.setVisibility(r.type == PLAYLIST ? View.VISIBLE : View.GONE);
        }

        h.itemView.setOnClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p == RecyclerView.NO_POSITION) return;
            if (rows.get(p).type == TOGGLE) { h.toggle.toggle(); return; }
            listener.onClick(rows.get(p), p);
        });
        h.itemView.setOnLongClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p == RecyclerView.NO_POSITION) return false;
            Row row = rows.get(p);
            if (row.type != MEDIA && row.type != PLAYLIST) return false;
            listener.onLongClick(row, p);
            return true;
        });
        h.more.setOnClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION) listener.onMore(rows.get(p), p);
        });
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView text;
        HeaderVH(View v) { super(v); text = v.findViewById(R.id.gh_text); }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb, check;
        final TextView title, sub;
        final ImageButton more;
        final android.widget.Switch toggle;

        VH(View v) {
            super(v);
            thumb = v.findViewById(R.id.row_thumb);
            thumb.setClipToOutline(true);          // rounded corners from the background shape
            check = v.findViewById(R.id.row_check);
            title = v.findViewById(R.id.row_title);
            sub = v.findViewById(R.id.row_sub);
            more = v.findViewById(R.id.row_more);
            toggle = v.findViewById(R.id.row_switch);
        }
    }
}
