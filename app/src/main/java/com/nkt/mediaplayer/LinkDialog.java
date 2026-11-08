package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "Play a link" dialog: plays a direct link, opens YouTube links in YouTube's player, or downloads. */
public final class LinkDialog {
    private LinkDialog() {}

    public static void show(Activity a) {
        LinearLayout box = Ui.col(a);
        box.addView(Ui.label(a, "Play a link", 13, Ui.TEXT, 0.2f));
        TextView hint = Ui.text(a, "Paste a link to a song, video or stream (MP3, MP4, M3U8…).", 13, Ui.MUTED);
        hint.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 12));
        box.addView(hint);
        EditText e = new EditText(a);
        e.setHint("https://");
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        e.setSingleLine(true);
        e.setBackground(Ui.rect(a, Ui.FILL, 10, Ui.BORDER, 1));
        e.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
        box.addView(e, Ui.lp(Ui.MATCH, Ui.dp(a, 48)));
        LinearLayout btns = Ui.row(a);
        btns.setPadding(0, Ui.dp(a, 16), 0, 0);
        TextView audio = Ui.pill(a, "AUDIO", false, Ui.ACCENT), video = Ui.pill(a, "VIDEO", true, Ui.ACCENT), dl = Ui.pill(a, "DOWNLOAD", false, Ui.ACCENT);
        for (TextView t : new TextView[]{audio, video, dl}) {
            t.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
            btns.addView(t, Ui.lpm(a, 0, Ui.dp(a, 46), 3, 0, 3, 0));
            ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1;
        }
        box.addView(btns);
        Dialog d = Ui.sheet(a, box);
        android.view.View.OnClickListener go = v -> {
            String url = e.getText().toString().trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) { Ui.msg(a, "That doesn't look like a link. It should start with https://"); return; }
            d.dismiss();
            if (youTubeId(url) != null || url.contains("list=") && url.contains("youtu")) {
                if (v == dl) { Ui.msg(a, "YouTube videos can't be downloaded"); return; }
                a.startActivity(new Intent(a, YouTubeActivity.class).putExtra("url", url));
                return;
            }
            MediaInfo m = new MediaInfo();
            m.uri = url;
            String last = Uri.parse(url).getLastPathSegment();
            m.title = last == null ? url : last;
            m.name = m.title;
            m.folder = "Link";
            if (v == dl) { Downloads.get(a, url, m.title); return; }
            m.kind = v == audio ? MediaInfo.AUDIO : MediaInfo.VIDEO;
            if (v == audio) Media.playSongs(a, Collections.singletonList(m), 0, true);
            else Media.playVideos(a, Collections.singletonList(m), 0);
        };
        audio.setOnClickListener(go);
        video.setOnClickListener(go);
        dl.setOnClickListener(go);
        d.show();
    }

    private static final Pattern YT = Pattern.compile("(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/|embed/|v/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    public static String youTubeId(String url) {
        Matcher m = YT.matcher(url);
        return m.find() ? m.group(1) : null;
    }
}
