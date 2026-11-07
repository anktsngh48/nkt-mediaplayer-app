package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NKT Media Player in the "Share" list (like VLC): songs, videos, photos and links shared from other apps
 * open in the right player. Shared links: YouTube → YouTube's player; others → play as audio/video or download.
 */
public class ShareReceiverActivity extends Activity {

    private static final Pattern URL = Pattern.compile("https?://\\S+");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        List<Uri> streams = streams(in);
        if (!streams.isEmpty()) { openStreams(streams, in.getType()); finish(); return; }

        String text = in.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) text = in.getDataString();
        Matcher m = text == null ? null : URL.matcher(text);
        if (m == null || !m.find()) { Ui.toast(getApplicationContext(), getString(R.string.share_nothing)); finish(); return; }
        String link = m.group().replaceAll("[)\\].,]+$", "");
        if (YouTube.isYouTube(link)) { YouTubeActivity.open(this, link); finish(); return; }
        askLink(Uri.parse(link));
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> streams(Intent in) {
        List<Uri> out = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Parcelable> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) for (Parcelable p : l) if (p instanceof Uri) out.add((Uri) p);
        } else {
            Parcelable p = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) out.add((Uri) p);
        }
        return out;
    }

    private String kindOf(Uri u, String fallbackType) {
        String t = null;
        try { t = getContentResolver().getType(u); } catch (Exception ignored) { }
        if (t == null || t.equals("*/*") || t.endsWith("/*")) {
            String n = Ui.displayName(this, u).toLowerCase(Locale.ROOT);
            if (n.matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|opus|wma|amr)$")) return "audio";
            if (n.matches(".*\\.(jpg|jpeg|png|webp|gif|heic|heif|bmp)$")) return "image";
            if (n.matches(".*\\.(mp4|mkv|webm|3gp|mov|avi|m4v|ts)$")) return "video";
            t = t == null ? fallbackType : t;
        }
        if (t == null) return "video";
        if (t.startsWith("audio")) return "audio";
        if (t.startsWith("image")) return "image";
        return "video";
    }

    private void openStreams(List<Uri> uris, String type) {
        String kind = kindOf(uris.get(0), type);
        List<MediaInfo> items = new ArrayList<>();
        ClipData clip = null;
        for (Uri u : uris) {
            if (!kindOf(u, type).equals(kind)) continue;
            MediaInfo m = new MediaInfo(-1, u, Ui.displayName(this, u), "", "", -1,
                    getString(R.string.shared_folder_name), 0, kind.equals("video"));
            m.image = kind.equals("image");
            items.add(m);
            if (clip == null) clip = ClipData.newRawUri("", u); else clip.addItem(new ClipData.Item(u));
        }
        // pass on the permission to read the shared files
        Intent next;
        if (kind.equals("audio")) {
            next = new Intent(this, NowPlayingActivity.class).setAction(Intent.ACTION_VIEW).setData(items.get(0).uri);
        } else if (kind.equals("image")) {
            ViewerActivity.prepare(items, 0);
            next = new Intent(this, ViewerActivity.class);
        } else {
            next = PlayerActivity.intent(this, items, 0, 0);
        }
        next.setClipData(clip);
        next.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(next);
    }

    private void askLink(Uri link) {
        String name = Downloads.fileName(link, null);
        boolean audio = name.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|opus)$");
        Dialogs.builder(this)
                .setTitle(R.string.open_link)
                .setMessage(link.toString())
                .setPositiveButton(R.string.play_caps, (d, w) -> {
                    MediaInfo m = MediaInfo.link(link, name, !audio);
                    List<MediaInfo> one = new ArrayList<>();
                    one.add(m);
                    if (audio) startActivity(new Intent(this, NowPlayingActivity.class).setAction(Intent.ACTION_VIEW).setData(link));
                    else PlayerActivity.open(this, one, 0);
                    finish();
                })
                .setNeutralButton(R.string.download_caps, (d, w) -> { Downloads.start(this, link, null); finish(); })
                .setNegativeButton(R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }
}
