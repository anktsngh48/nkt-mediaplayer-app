package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Now Playing: a small green "STREAMING · 2 LISTENING · PHONE SILENT" chip while Stream is on. Tapping it opens the
 * Stream screen. It sits under the sleep-timer chip and appears and disappears by itself.
 */
final class StreamChip {

    private StreamChip() { }

    /** "songs", "videos" or "photos". */
    static String kindName(Context c, int kind) { return c.getString(kind == 2 ? R.string.stream_kind_photos : kind == 1 ? R.string.stream_kind_videos : R.string.stream_kind_songs); }

    /** The menu line for streaming or keeping this kind to this phone; null when Stream is off. */
    static String menuLabel(Context c, int kind) {
        if (!StreamService.running) return null;
        return c.getString(StreamPrefs.share(c, kind) ? R.string.stream_menu_private : R.string.stream_menu_share, kindName(c, kind));
    }

    /** Switches one kind between streamed and only on this phone, and says so. */
    static void toggle(Context c, int kind) {
        boolean on = !StreamPrefs.share(c, kind);
        StreamHub.setShare(c, kind, on);
        Ui.toast(c, c.getString(on ? R.string.stream_now_shared : R.string.stream_now_private, kindName(c, kind)));
    }

    static void attach(Activity a) {
        View sleep = a.findViewById(R.id.np_sleep);
        if (sleep == null || !(sleep.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) sleep.getParent();
        int dp = Ui.dp(a, 1);

        TextView chip = new TextView(a);
        chip.setBackgroundResource(R.drawable.bg_running);
        chip.setTextColor(a.getColor(R.color.success));
        chip.setTextSize(11);
        chip.setLetterSpacing(0.1f);
        chip.setTypeface(null, android.graphics.Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(12 * dp, 0, 12 * dp, 0);
        chip.setCompoundDrawablePadding(8 * dp);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(a.getColor(R.color.success));
        dot.setSize(8 * dp, 8 * dp);
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        chip.setVisibility(View.GONE);
        chip.setOnClickListener(v -> a.startActivity(new Intent(a, StreamActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 28 * dp);
        lp.topMargin = 10 * dp;
        parent.addView(chip, parent.indexOfChild(sleep) + 1, lp);

        // a second chip: this kind of thing is streamed, or kept to this phone (tap to switch)
        TextView share = new TextView(a);
        share.setTextSize(11);
        share.setLetterSpacing(0.1f);
        share.setTypeface(null, android.graphics.Typeface.BOLD);
        share.setGravity(Gravity.CENTER);
        share.setPadding(12 * dp, 0, 12 * dp, 0);
        share.setVisibility(View.GONE);
        share.setOnClickListener(v -> toggle(a, StreamHub.nowKind()));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 28 * dp);
        slp.topMargin = 8 * dp;
        parent.addView(share, parent.indexOfChild(chip) + 1, slp);

        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable[] run = new Runnable[1];
        run[0] = () -> {
            if (StreamService.running) {
                int n = StreamHub.listenerCount();
                StringBuilder b = new StringBuilder(a.getString(R.string.stream_chip_on));
                if (n > 0) b.append(" · ").append(a.getResources().getQuantityString(R.plurals.stream_chip_listening, n, n));
                if (StreamPrefs.silence(a)) b.append(" · ").append(a.getString(R.string.stream_chip_silent));
                chip.setText(b.toString());
                chip.setVisibility(View.VISIBLE);
                int kind = StreamHub.nowKind();
                boolean on = StreamPrefs.share(a, kind);
                share.setText(a.getString(on ? R.string.stream_chip_sharing : R.string.stream_chip_only_me, kindName(a, kind).toUpperCase()));
                share.setTextColor(a.getColor(on ? R.color.success : R.color.muted));
                share.setBackground(on ? a.getDrawable(R.drawable.bg_running) : a.getDrawable(R.drawable.bg_round_btn));
                share.setVisibility(View.VISIBLE);
            } else { chip.setVisibility(View.GONE); share.setVisibility(View.GONE); }
            h.postDelayed(run[0], 1000);
        };
        chip.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { h.post(run[0]); }
            @Override public void onViewDetachedFromWindow(View v) { h.removeCallbacks(run[0]); }
        });
    }
}
