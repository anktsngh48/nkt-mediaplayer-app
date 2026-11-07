package com.nkt.mediaplayer;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/** Bottom sheet: sleep timer (music only), playback speed, skip silence (music only). */
public final class SpeedSheet {

    private SpeedSheet() { }

    public interface Target {
        float speed();
        void setSpeed(float s);
    }

    /** A dialog that slides up from the bottom. */
    public static Dialog bottom(Activity a, View content) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(content);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
        return d;
    }

    public static String label(float s) {
        String t = String.format(Locale.US, "%.2f", s);
        if (t.endsWith("0")) t = t.substring(0, t.length() - 1);
        return t + "×";
    }

    private static int toProgress(float s) { return Math.round((s - 0.25f) / 0.05f); }
    private static float fromProgress(int p) { return Math.round((0.25f + p * 0.05f) * 100f) / 100f; }

    public static void show(Activity a, Target target, boolean music) {
        View v = a.getLayoutInflater().inflate(R.layout.sheet_timer, null);
        Dialog d = bottom(a, v);
        Handler h = new Handler(Looper.getMainLooper());

        // ── speed ──
        SeekBar bar = v.findViewById(R.id.sp_bar);
        TextView value = v.findViewById(R.id.sp_value);
        int[] chipIds = {R.id.sp_075, R.id.sp_100, R.id.sp_125, R.id.sp_150, R.id.sp_200};
        float[] chipSpeeds = {0.75f, 1f, 1.25f, 1.5f, 2f};
        Runnable showSpeed = () -> {
            float s = target.speed();
            value.setText(label(s));
            bar.setProgress(toProgress(s));
            for (int i = 0; i < chipIds.length; i++) v.findViewById(chipIds[i]).setSelected(Math.abs(chipSpeeds[i] - s) < 0.01f);
        };
        for (int i = 0; i < chipIds.length; i++) {
            final float s = chipSpeeds[i];
            v.findViewById(chipIds[i]).setOnClickListener(x -> { target.setSpeed(s); showSpeed.run(); });
        }
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                target.setSpeed(fromProgress(p));
                value.setText(label(fromProgress(p)));
                for (int i = 0; i < chipIds.length; i++) v.findViewById(chipIds[i]).setSelected(Math.abs(chipSpeeds[i] - fromProgress(p)) < 0.01f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        showSpeed.run();

        // ── timer & skip silence (music only, needs the background player) ──
        PlaybackService svc = PlaybackService.instance;
        int[] timerIds = {R.id.st_off, R.id.st_15, R.id.st_30, R.id.st_45, R.id.st_60, R.id.st_end};
        if (!music || svc == null) {
            for (int id : new int[]{R.id.st_label, R.id.st_left, R.id.st_row1, R.id.st_silence_row})
                v.findViewById(id).setVisibility(View.GONE);
            ((View) v.findViewById(R.id.st_45).getParent()).setVisibility(View.GONE);
            return;
        }
        int[] minutes = {0, 15, 30, 45, 60, -1};
        TextView left = v.findViewById(R.id.st_left);
        Runnable showTimer = new Runnable() {
            @Override public void run() {
                long ms = svc.sleepLeftMs();
                boolean end = svc.sleepIsEndOfSong();
                for (int id : timerIds) v.findViewById(id).setSelected(false);
                if (end) {
                    v.findViewById(R.id.st_end).setSelected(true);
                    left.setText(R.string.stops_end_of_song);
                    left.setVisibility(View.VISIBLE);
                } else if (ms > 0) {
                    left.setText(a.getString(R.string.stops_in, Ui.time(ms)));
                    left.setVisibility(View.VISIBLE);
                } else {
                    v.findViewById(R.id.st_off).setSelected(true);
                    left.setVisibility(View.GONE);
                }
                if (d.isShowing()) h.postDelayed(this, 1000);
            }
        };
        for (int i = 0; i < timerIds.length; i++) {
            final int m = minutes[i];
            v.findViewById(timerIds[i]).setOnClickListener(x -> {
                if (m == 0) svc.cancelSleep();
                else if (m < 0) svc.setSleepEndOfSong();
                else svc.setSleepMinutes(m);
                h.removeCallbacksAndMessages(null);
                showTimer.run();
            });
        }
        d.setOnDismissListener(x -> h.removeCallbacksAndMessages(null));
        showTimer.run();

        Switch silence = v.findViewById(R.id.st_silence);
        silence.setChecked(svc.skipSilence());
        silence.setOnCheckedChangeListener((b, on) -> svc.setSkipSilence(on));
    }
}
