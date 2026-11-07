package com.nkt.mediaplayer;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagOptionSingleton;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.jaudiotagger.tag.reference.PictureTypes;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reads and writes song tags (title, artist… and album art) with jaudiotagger. Android 8+ only. */
final class TagIO {

    private TagIO() { }

    static final FieldKey[] KEYS = {FieldKey.TITLE, FieldKey.ARTIST, FieldKey.ALBUM, FieldKey.ALBUM_ARTIST,
            FieldKey.GENRE, FieldKey.YEAR, FieldKey.TRACK};

    static class Data {
        final String[] values = new String[KEYS.length];
        byte[] art;            // null = no art
    }

    private static boolean ready = false;

    private static void init() {
        if (ready) return;
        TagOptionSingleton.getInstance().setAndroid(true);
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
        ready = true;
    }

    static Data read(File f) throws Exception {
        init();
        AudioFile af = AudioFileIO.read(f);
        Tag tag = af.getTag();
        Data d = new Data();
        for (int i = 0; i < KEYS.length; i++) {
            String v = "";
            try { if (tag != null) v = tag.getFirst(KEYS[i]); } catch (Exception ignored) { }
            d.values[i] = v == null ? "" : v;
        }
        try {
            Artwork a = tag == null ? null : tag.getFirstArtwork();
            if (a != null) d.art = a.getBinaryData();
        } catch (Exception ignored) { }
        return d;
    }

    /** Writes everything into the (cached copy of the) file. artChanged=false keeps the old art. */
    static void write(File f, Data d, boolean artChanged) throws Exception {
        init();
        AudioFile af = AudioFileIO.read(f);
        Tag tag = af.getTagOrCreateAndSetDefault();
        for (int i = 0; i < KEYS.length; i++) {
            String v = d.values[i] == null ? "" : d.values[i].trim();
            try {
                if (v.isEmpty()) tag.deleteField(KEYS[i]);
                else tag.setField(KEYS[i], v);
            } catch (Exception ignored) { }    // e.g. a year or track that isn't a number
        }
        if (artChanged) {
            try { tag.deleteArtworkField(); } catch (Exception ignored) { }
            if (d.art != null) {
                Artwork a = ArtworkFactory.getNew();
                a.setBinaryData(d.art);
                a.setMimeType("image/jpeg");
                a.setDescription("");
                a.setPictureType(PictureTypes.DEFAULT_ID);
                tag.setField(a);
            }
        }
        af.commit();
    }

    /** Lyrics stored in the file's tag, or null. */
    static String lyrics(File f) throws Exception {
        init();
        Tag tag = AudioFileIO.read(f).getTag();
        if (tag == null) return null;
        String l = tag.getFirst(FieldKey.LYRICS);
        return l == null || l.trim().isEmpty() ? null : l;
    }
}
