package com.nkt.mediaplayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Items picked for Share / Delete / Add to playlist. Shared by the list and grid adapters. */
public final class Selection {

    public interface Changed { void onSelectionChanged(); }

    private final Map<String, MediaInfo> picked = new LinkedHashMap<>();
    private boolean active = false;
    private final Changed changed;

    public Selection(Changed c) { changed = c; }

    public boolean active() { return active; }
    public int count() { return picked.size(); }
    public boolean has(MediaInfo m) { return m != null && picked.containsKey(m.key()); }
    public List<MediaInfo> items() { return new ArrayList<>(picked.values()); }

    public void start(MediaInfo m) {
        active = true;
        if (m != null) picked.put(m.key(), m);
        changed.onSelectionChanged();
    }

    public void toggle(MediaInfo m) {
        if (m == null) return;
        if (picked.containsKey(m.key())) picked.remove(m.key()); else picked.put(m.key(), m);
        if (picked.isEmpty()) active = false;
        changed.onSelectionChanged();
    }

    public void addAll(List<MediaInfo> all) {
        active = true;
        for (MediaInfo m : all) picked.put(m.key(), m);
        changed.onSelectionChanged();
    }

    public boolean hasAll(List<MediaInfo> l) {
        if (l == null || l.isEmpty()) return false;
        for (MediaInfo m : l) if (!picked.containsKey(m.key())) return false;
        return true;
    }

    /** A folder: picks all of it, or un-picks it when it's all picked already. */
    public void toggleAll(List<MediaInfo> l) {
        if (l == null || l.isEmpty()) return;
        if (hasAll(l)) for (MediaInfo m : l) picked.remove(m.key());
        else { active = true; for (MediaInfo m : l) picked.put(m.key(), m); }
        if (picked.isEmpty()) active = false;
        changed.onSelectionChanged();
    }

    public void clear() {
        picked.clear();
        active = false;
        changed.onSelectionChanged();
    }
}
