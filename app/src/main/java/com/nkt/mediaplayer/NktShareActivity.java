package com.nkt.mediaplayer;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * NKT Share: one tap to START. Shows the address to type on the computer.
 * Default: no password, whole storage, read & write, no time limit.
 */
public class NktShareActivity extends Activity {

    private static final int REQ_PERMS = 21;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, sub, address, login, button, folder, pwSub, access;
    private ImageView icon;
    private View addressBox, newPw;
    private Switch pw;
    private boolean startWhenAllowed = false;

    private final Runnable tick = new Runnable() {
        @Override public void run() { update(); ui.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nkt_share);
        Ui.padForSystemBars(findViewById(R.id.root));
        ((TextView) findViewById(R.id.sh_title)).setTypeface(Ui.orbitron(this));

        icon = findViewById(R.id.sh_icon);
        status = findViewById(R.id.sh_status);
        sub = findViewById(R.id.sh_sub);
        addressBox = findViewById(R.id.sh_address_box);
        address = findViewById(R.id.sh_address);
        login = findViewById(R.id.sh_login);
        button = findViewById(R.id.sh_button);
        button.setTypeface(Ui.orbitron(this));
        folder = findViewById(R.id.sh_folder);
        pw = findViewById(R.id.sh_pw);
        pwSub = findViewById(R.id.sh_pw_sub);
        newPw = findViewById(R.id.sh_newpw);
        access = findViewById(R.id.sh_access);

        findViewById(R.id.sh_back).setOnClickListener(v -> finish());
        button.setOnClickListener(v -> {
            if (FtpService.running) FtpService.stop(this);
            else tryStart();
            ui.postDelayed(this::update, 300);
        });
        findViewById(R.id.sh_copy).setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("NKT Share", address.getText()));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });

        pw.setChecked(FtpService.passwordOn(this));
        pw.setOnCheckedChangeListener((b, on) -> { FtpService.setPasswordOn(this, on); settingsChanged(); });
        findViewById(R.id.sh_pw_row).setOnClickListener(v -> pw.toggle());
        newPw.setOnClickListener(v -> { FtpService.newPassword(this); settingsChanged(); });
        findViewById(R.id.sh_folder_row).setOnClickListener(v -> pickFolder());
        findViewById(R.id.sh_access_row).setOnClickListener(v -> { if (!canWrite()) askAccess(); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (startWhenAllowed && canWrite()) { startWhenAllowed = false; FtpService.start(this); }
        startWhenAllowed = false;
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    // ── Start / permissions ──────────────────────────────────────────────────

    private boolean canWrite() {
        if (Build.VERSION.SDK_INT >= 30) return HiddenScanner.hasAllFilesAccess(this);
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void tryStart() {
        if (FtpService.ipAddress() == null) {
            Toast.makeText(this, R.string.share_no_wifi, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS + 1);   // optional; sharing starts anyway
        }
        if (canWrite()) FtpService.start(this);
        else { startWhenAllowed = true; askAccess(); }
    }

    private void askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            Dialogs.builder(this)
                    .setTitle(R.string.share_access_title)
                    .setMessage(R.string.share_access_text)
                    .setPositiveButton(R.string.allow, (d, w) -> {
                        startWhenAllowed = startWhenAllowed || !FtpService.running;
                        try { startActivity(HiddenScanner.allFilesAccessIntent(this)); } catch (Exception ignored) { }
                    })
                    .setNegativeButton(R.string.cancel, (d, w) -> startWhenAllowed = false)
                    .show();
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMS && startWhenAllowed && canWrite()) {
            startWhenAllowed = false;
            FtpService.start(this);
        }
    }

    /** Settings take effect on the next start; restart now if it's running. */
    private void settingsChanged() {
        if (FtpService.running) {
            FtpService.stop(this);
            ui.postDelayed(() -> FtpService.start(this), 700);
            Toast.makeText(this, R.string.share_restarted, Toast.LENGTH_SHORT).show();
        }
        update();
    }

    // ── Shared folder ────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void pickFolder() {
        File base = Environment.getExternalStorageDirectory();
        List<String> names = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        names.add(getString(R.string.whole_storage)); paths.add(base.getAbsolutePath());
        String[][] common = {
                {getString(R.string.f_music), Environment.DIRECTORY_MUSIC},
                {getString(R.string.f_download), Environment.DIRECTORY_DOWNLOADS},
                {getString(R.string.f_camera), Environment.DIRECTORY_DCIM},
                {getString(R.string.f_pictures), Environment.DIRECTORY_PICTURES},
                {getString(R.string.f_movies), Environment.DIRECTORY_MOVIES},
                {getString(R.string.f_documents), Environment.DIRECTORY_DOCUMENTS}};
        for (String[] c : common) {
            File f = new File(base, c[1]);
            if (f.isDirectory()) { names.add(c[0]); paths.add(f.getAbsolutePath()); }
        }
        String cur = FtpService.root(this);
        int checked = Math.max(0, paths.indexOf(cur));
        Dialogs.builder(this)
                .setTitle(R.string.shared_folder)
                .setSingleChoiceItems(names.toArray(new String[0]), checked, (d, w) -> {
                    FtpService.setRoot(this, paths.get(w));
                    d.dismiss();
                    settingsChanged();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Screen ───────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void update() {
        boolean on = FtpService.running;
        String ip = FtpService.ipAddress();
        icon.setColorFilter(getColor(on ? R.color.success : R.color.muted));
        if (on) {
            status.setText(R.string.share_on);
            int n = FtpService.connected.get();
            sub.setText(n > 0 ? getResources().getQuantityString(R.plurals.devices, n, n) : getString(R.string.share_on_sub));
            address.setText(ip == null ? getString(R.string.share_no_wifi) : "ftp://" + ip + ":" + FtpService.PORT);
            addressBox.setVisibility(View.VISIBLE);
            if (FtpService.passwordOn(this)) {
                login.setText(getString(R.string.share_login, FtpService.user(), FtpService.password(this)));
                login.setVisibility(View.VISIBLE);
            } else login.setVisibility(View.GONE);
            button.setText(R.string.stop_caps);
            button.setBackgroundResource(R.drawable.bg_stop_btn);
            button.setTextColor(0xFFFF4F6A);
        } else {
            status.setText(R.string.share_off);
            sub.setText(FtpService.error != null ? FtpService.error
                    : ip == null ? getString(R.string.share_no_wifi) : getString(R.string.share_off_sub));
            addressBox.setVisibility(View.GONE);
            login.setVisibility(View.GONE);
            button.setText(R.string.start_caps);
            button.setBackgroundResource(R.drawable.bg_start_btn);
            button.setTextColor(getColor(R.color.bg));
        }

        String root = FtpService.root(this);
        String base = Environment.getExternalStorageDirectory().getAbsolutePath();
        folder.setText(root.equals(base) ? getString(R.string.whole_storage) : root.replace(base, getString(R.string.phone_short)));
        boolean pwOn = FtpService.passwordOn(this);
        pwSub.setText(pwOn ? getString(R.string.share_login, FtpService.user(), FtpService.password(this)) : getString(R.string.no_password_sub));
        newPw.setVisibility(pwOn ? View.VISIBLE : View.GONE);
        access.setText(canWrite() ? R.string.access_rw : R.string.access_need);
        access.setTextColor(getColor(canWrite() ? R.color.muted : R.color.accent));
    }
}
