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

/** One list for every tab: songs, videos, folders, playlists and their action rows. */
public class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {

    public static final int MEDIA = 0, FOLDER = 1, PLAYLIST = 2, ACTION = 3, BACK = 4, TOGGLE = 5;

    public static class Row {
        public final int type;
        public final String title;
        public final String sub;
        public final MediaInfo media;     // MEDIA rows
        public final String key;          // folder / playlist name, or action id
        public final int icon;

        public Row(int type, String title, String sub, MediaInfo media, String key, int icon) {
            this.type = type; this.title = title; this.sub = sub; this.media = media; this.key = key; this.icon = icon;
        }

        public static Row media(MediaInfo m) { return new Row(MEDIA, m.title, m.subtitle(), m, null, 0); }
    }

    public interface Listener {
        void onClick(Row r, int position);
        void onLongClick(Row r, int position);
        default void onToggle(Row r, boolean on) { }
    }

    private boolean toggleOn;
    public void setToggle(boolean on) { toggleOn = on; }

    private final List<Row> rows = new ArrayList<>();
    private final Listener listener;
    private String playingId;

    public RowAdapter(Listener l) { this.listener = l; }

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

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_item, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(VH h, int position) {
        Row r = rows.get(position);
        h.title.setText(r.title);
        h.sub.setText(r.sub == null ? "" : r.sub);
        h.sub.setVisibility(r.sub == null || r.sub.isEmpty() ? View.GONE : View.VISIBLE);

        boolean playing = r.type == MEDIA && playingId != null && playingId.equals(r.media.uri.toString());
        h.title.setTextColor(h.itemView.getContext().getColor(playing ? R.color.accent : R.color.text));
        h.itemView.setSelected(playing);

        h.toggle.setOnCheckedChangeListener(null);
        h.toggle.setVisibility(r.type == TOGGLE ? View.VISIBLE : View.GONE);
        if (r.type == TOGGLE) {
            h.toggle.setChecked(toggleOn);
            h.toggle.setOnCheckedChangeListener((b, on) -> listener.onToggle(r, on));
        }
        if (r.type == MEDIA) {
            Thumbs.load(h.thumb, r.media);
            h.more.setVisibility(View.VISIBLE);
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
        View.OnLongClickListener longClick = v -> {
            int p = h.getBindingAdapterPosition();
            if (p == RecyclerView.NO_POSITION) return false;
            Row row = rows.get(p);
            if (row.type != MEDIA && row.type != PLAYLIST) return false;
            listener.onLongClick(row, p);
            return true;
        };
        h.itemView.setOnLongClickListener(longClick);
        h.more.setOnClickListener(longClick::onLongClick);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title, sub;
        final ImageButton more;
        final android.widget.Switch toggle;

        VH(View v) {
            super(v);
            thumb = v.findViewById(R.id.row_thumb);
            thumb.setClipToOutline(true);          // rounded corners from the background shape
            title = v.findViewById(R.id.row_title);
            sub = v.findViewById(R.id.row_sub);
            more = v.findViewById(R.id.row_more);
            toggle = v.findViewById(R.id.row_switch);
        }
    }
}
