package com.nkt.mediaplayer;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Reads the phone's songs, videos and photos from Android's media index. */
public final class MediaLibrary {

    private MediaLibrary() { }

    private static Uri coll(Uri legacy, String type) {
        if (Build.VERSION.SDK_INT < 29) return legacy;
        switch (type) {
            case "audio": return MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            case "video": return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            default:      return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        }
    }

    private static int col(Cursor c, String name) { return c.getColumnIndex(name); }
    private static String str(Cursor c, int i) { return i < 0 || c.isNull(i) ? "" : c.getString(i); }
    private static long lng(Cursor c, int i) { return i < 0 || c.isNull(i) ? 0 : c.getLong(i); }

    public static List<MediaInfo> audio(Context ctx) {
        List<MediaInfo> out = new ArrayList<>();
        Uri coll = coll(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio");
        List<String> proj = new ArrayList<>();
        proj.add(MediaStore.Audio.Media._ID);
        proj.add(MediaStore.Audio.Media.TITLE);
        proj.add(MediaStore.Audio.Media.ARTIST);
        proj.add(MediaStore.Audio.Media.ALBUM);
        proj.add(MediaStore.Audio.Media.ALBUM_ID);
        proj.add(MediaStore.Audio.Media.DURATION);
        proj.add(MediaStore.Audio.Media.YEAR);
        proj.add(MediaStore.Audio.Media.DATE_ADDED);
        proj.add(MediaStore.Audio.Media.SIZE);
        proj.add(MediaStore.Audio.Media.DISPLAY_NAME);
        proj.add(MediaStore.MediaColumns.DATA);
        if (Build.VERSION.SDK_INT >= 29) proj.add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME);
        if (Build.VERSION.SDK_INT >= 30) proj.add(MediaStore.Audio.Media.GENRE);
        String sel = MediaStore.Audio.Media.DURATION + " >= 10000";     // skip ringtones and short clips
        try (Cursor c = ctx.getContentResolver().query(coll, proj.toArray(new String[0]), sel, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (c == null) return out;
            int iId = col(c, MediaStore.Audio.Media._ID), iT = col(c, MediaStore.Audio.Media.TITLE),
                iAr = col(c, MediaStore.Audio.Media.ARTIST), iAl = col(c, MediaStore.Audio.Media.ALBUM),
                iAlId = col(c, MediaStore.Audio.Media.ALBUM_ID), iD = col(c, MediaStore.Audio.Media.DURATION),
                iY = col(c, MediaStore.Audio.Media.YEAR), iDa = col(c, MediaStore.Audio.Media.DATE_ADDED),
                iS = col(c, MediaStore.Audio.Media.SIZE), iN = col(c, MediaStore.Audio.Media.DISPLAY_NAME),
                iP = col(c, MediaStore.MediaColumns.DATA),
                iB = col(c, "bucket_display_name"), iG = col(c, "genre");
            while (c.moveToNext()) {
                long id = c.getLong(iId);
                String path = str(c, iP);
                String folder = iB >= 0 ? str(c, iB) : parentName(path);
                MediaInfo m = new MediaInfo(id, ContentUris.withAppendedId(coll, id), str(c, iT), str(c, iAr),
                        str(c, iAl), lng(c, iAlId), folder, lng(c, iD), false);
                m.year = (int) lng(c, iY);
                m.dateMs = lng(c, iDa) * 1000;
                m.size = lng(c, iS);
                m.fileName = str(c, iN);
                m.path = path;
                m.genre = str(c, iG);
                out.add(m);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static List<MediaInfo> video(Context ctx) {
        List<MediaInfo> out = new ArrayList<>();
        Uri coll = coll(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video");
        String[] proj = {
                MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA
        };
        try (Cursor c = ctx.getContentResolver().query(coll, proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                MediaInfo m = new MediaInfo(id, ContentUris.withAppendedId(coll, id), str(c, 1), "", "", -1,
                        str(c, 3), lng(c, 2), true);
                long taken = lng(c, 4);
                m.dateMs = taken > 0 ? taken : lng(c, 5) * 1000;
                m.size = lng(c, 6);
                m.fileName = str(c, 7);
                m.path = str(c, 8);
                out.add(m);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static List<MediaInfo> images(Context ctx) {
        List<MediaInfo> out = new ArrayList<>();
        Uri coll = coll(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image");
        String[] proj = {
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.ORIENTATION, MediaStore.MediaColumns.DATA
        };
        try (Cursor c = ctx.getContentResolver().query(coll, proj, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                MediaInfo m = new MediaInfo(id, ContentUris.withAppendedId(coll, id), str(c, 1), "", "", -1,
                        str(c, 2), 0, false);
                m.image = true;
                long taken = lng(c, 3);
                m.dateMs = taken > 0 ? taken : lng(c, 4) * 1000;
                m.size = lng(c, 5);
                m.orientation = (int) lng(c, 6);
                m.fileName = str(c, 1);
                m.path = str(c, 7);
                out.add(m);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Every PDF the phone's library knows (Android 11+: needs "All files access" to include other apps' PDFs). */
    public static List<MediaInfo> pdfs(Context ctx) {
        List<MediaInfo> out = new ArrayList<>();
        Uri coll = MediaStore.Files.getContentUri("external");
        String[] proj = {MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATA};
        try (Cursor c = ctx.getContentResolver().query(coll, proj, MediaStore.Files.FileColumns.MIME_TYPE + "=?",
                new String[]{"application/pdf"}, MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) return out;
            int iId = col(c, MediaStore.Files.FileColumns._ID), iN = col(c, MediaStore.Files.FileColumns.DISPLAY_NAME),
                iS = col(c, MediaStore.Files.FileColumns.SIZE), iD = col(c, MediaStore.Files.FileColumns.DATE_MODIFIED),
                iP = col(c, MediaStore.MediaColumns.DATA);
            while (c.moveToNext()) {
                long id = c.getLong(iId);
                String name = str(c, iN), path = str(c, iP);
                if (name.isEmpty()) name = new java.io.File(path).getName();
                MediaInfo m = new MediaInfo(id, ContentUris.withAppendedId(coll, id), MediaInfo.stripExt(name), "", "", -1,
                        parentName(path), 0, false);
                m.other = true;
                m.fileName = name;
                m.path = path;
                m.size = lng(c, iS);
                m.dateMs = lng(c, iD) * 1000;
                out.add(m);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static String parentName(String path) {
        if (path == null || path.isEmpty()) return "Other";
        File parent = new File(path).getParentFile();
        return parent == null ? "Other" : parent.getName();
    }
}
