package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** Settings → APPEARANCE → App logo: the picture shown when a song has no album art. */
public class LogoActivity extends Activity {

    private static final int PICK = 41;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ImageView yoursImg, yoursTick, defaultTick;
    private TextView yoursCap, defaultCap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logo);
        Ui.padForSystemBars(findViewById(R.id.root));
        ((TextView) findViewById(R.id.lg_title)).setTypeface(Ui.orbitron(this));
        findViewById(R.id.lg_back).setOnClickListener(v -> finish());
        yoursImg = findViewById(R.id.lg_yours_img);
        yoursTick = findViewById(R.id.lg_yours_tick);
        defaultTick = findViewById(R.id.lg_default_tick);
        yoursCap = findViewById(R.id.lg_yours_cap);
        defaultCap = findViewById(R.id.lg_default_cap);
        defaultCap.setTypeface(Ui.orbitron(this));
        yoursCap.setTypeface(Ui.orbitron(this));
        TextView choose = findViewById(R.id.lg_choose);
        choose.setTypeface(Ui.orbitron(this));
        choose.setOnClickListener(v -> pick());
        findViewById(R.id.lg_yours_box).setOnClickListener(v -> pick());
        findViewById(R.id.lg_default_box).setOnClickListener(v -> useDefault());
        findViewById(R.id.lg_reset).setOnClickListener(v -> useDefault());
        update();
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, getString(R.string.logo_choose)), PICK);
        } catch (Exception e) {
            Ui.toast(this, R.string.logo_failed);
        }
    }

    private void useDefault() {
        AppLogo.reset(this);
        PlaybackService.refreshArt();
        update();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        new Thread(() -> {
            boolean ok = AppLogo.save(getApplicationContext(), u);
            ui.post(() -> {
                if (isFinishing()) return;
                if (ok) { Ui.toast(this, R.string.logo_saved); PlaybackService.refreshArt(); }
                else Ui.toast(this, R.string.logo_failed);
                update();
            });
        }).start();
    }

    private void update() {
        boolean mine = AppLogo.custom(this);
        defaultTick.setVisibility(mine ? View.GONE : View.VISIBLE);
        yoursTick.setVisibility(mine ? View.VISIBLE : View.GONE);
        defaultCap.setTextColor(getColor(mine ? R.color.muted : R.color.accent));
        yoursCap.setTextColor(getColor(mine ? R.color.accent : R.color.muted));
        if (mine) {
            Bitmap b = AppLogo.bitmap(this, 256);
            yoursImg.setImageTintList(null);
            yoursImg.setPadding(0, 0, 0, 0);
            yoursImg.setImageBitmap(b);
        } else {
            int p = Ui.dp(this, 14);
            yoursImg.setImageResource(R.drawable.ic_image);
            yoursImg.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.muted)));
            yoursImg.setPadding(p, p, p, p);
        }
    }
}
