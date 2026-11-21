package com.nkt.mediaplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.List;

/**
 * Stream: one tap to START. Shows the address and a QR code for the other device, the song that is going out with
 * play / pause / next, "Silence this phone", the library for listeners, and who is listening.
 * Leaving this screen does not stop the streaming; STOP does.
 */
public class StreamActivity extends Activity {

    private static final int REQ_NOTIF = 31;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageView icon, qr, nowArt, btnPlay;
    private TextView status, sub, address, button, intro, scanNote, keepNote, nowTitle, nowSub, nowHint;
    private TextView blockedSub, limitSub, sharedSub, silenceSub, listenHead, sectionNow;
    private View addressBox, shareBtn, transport, listenBox;
    private Switch silence, library, hvolSw;
    private final Switch[] shareSw = new Switch[3];
    private final TextView[] shareSub = new TextView[3];
    private LinearLayout listenRows;
    private String shownQr = "", shownArtId = "", sharedCount = "";
    private long countAt = 0;
    private boolean counting = false;

    private final Runnable tick = new Runnable() {
        @Override public void run() { update(); ui.postDelayed(this, 1000); }
    };

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), 0, dp(20), dp(28));
        root.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        Ui.padForSystemBars(root);

        // top row: the same NKT bar as the Library, with Stream as the line under it
        col.addView(Ui.appBar(this, getString(R.string.stream_caps), this::finish),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // status card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_options_bar);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        col.addView(card, lp(MATCH, WRAP, 18, 0));

        icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_wifi);
        card.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        status = text("", 16, R.color.text, true);
        status.setGravity(Gravity.CENTER);
        card.addView(status, lp(MATCH, WRAP, 10, 0));
        sub = text("", 13, R.color.muted, false);
        sub.setGravity(Gravity.CENTER);
        card.addView(sub, lp(MATCH, WRAP, 4, 0));

        FrameLayout qrBox = new FrameLayout(this);
        qrBox.setBackground(rounded(0xFFFFFFFF, 16));
        qrBox.setPadding(dp(14), dp(14), dp(14), dp(14));
        qr = new ImageView(this);
        qr.setContentDescription(getString(R.string.stream_scan));
        qrBox.addView(qr, new FrameLayout.LayoutParams(dp(160), dp(160)));
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(WRAP, WRAP);
        ql.topMargin = dp(16);
        card.addView(qrBox, ql);
        qrBox.setTag("qrbox");
        scanNote = text(getString(R.string.stream_scan), 12, R.color.muted, false);
        scanNote.setGravity(Gravity.CENTER);
        card.addView(scanNote, lp(MATCH, WRAP, 8, 0));

        // address with edit and copy
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundResource(R.drawable.bg_address);
        box.setPadding(dp(14), 0, dp(6), 0);
        address = text("", 13, R.color.accent, false);
        address.setSingleLine(true);
        address.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        address.setTextIsSelectable(true);
        box.addView(address, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(iconButton(R.drawable.ic_edit, R.string.stream_edit_address, v -> editAddress()), new LinearLayout.LayoutParams(dp(44), dp(44)));
        box.addView(iconButton(R.drawable.ic_copy, R.string.copy, v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("NKT Stream", address.getText()));
            Ui.toast(this, R.string.copied);
        }), new LinearLayout.LayoutParams(dp(44), dp(44)));
        addressBox = box;
        card.addView(box, lp(MATCH, 54, 16, 0));

        shareBtn = outlined(getString(R.string.stream_share_link), v -> {
            String a = StreamService.address(this);
            if (a == null) return;
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a);
            startActivity(Intent.createChooser(send, getString(R.string.stream_share_link)));
        });
        card.addView(shareBtn, lp(MATCH, 46, 12, 0));

        button = new TextView(this);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Ui.orbitron(this));
        button.setTextSize(14);
        button.setLetterSpacing(0.16f);
        button.setOnClickListener(v -> {
            if (StreamService.running) askStop(); else tryStart();
            ui.postDelayed(this::update, 400);
        });
        card.addView(button, lp(MATCH, 54, 12, 0));

        intro = text(getString(R.string.stream_intro), 13, R.color.muted, false);
        intro.setGravity(Gravity.CENTER);
        card.addView(intro, lp(MATCH, WRAP, 14, 0));
        keepNote = text(getString(R.string.stream_keeps), 12, R.color.muted, false);
        keepNote.setGravity(Gravity.CENTER);
        card.addView(keepNote, lp(MATCH, WRAP, 12, 0));
        TextView intro2 = text(getString(R.string.stream_intro2), 12, R.color.muted, false);
        intro2.setGravity(Gravity.CENTER);
        intro2.setTag("intro2");
        card.addView(intro2, lp(MATCH, WRAP, 8, 0));

        // what is going out
        sectionNow = label("");
        col.addView(sectionNow, lp(MATCH, WRAP, 22, 0));
        LinearLayout now = new LinearLayout(this);
        now.setOrientation(LinearLayout.VERTICAL);
        now.setBackgroundResource(R.drawable.bg_options_bar);
        now.setPadding(dp(12), dp(12), dp(12), dp(12));
        col.addView(now, lp(MATCH, WRAP, 8, 0));
        LinearLayout nowRow = new LinearLayout(this);
        nowRow.setGravity(Gravity.CENTER_VERTICAL);
        nowArt = new ImageView(this);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        nowArt.setBackground(rounded(0xFF0B1222, 8));
        nowArt.setClipToOutline(true);
        nowArt.setImageResource(R.drawable.ic_music);
        nowArt.setColorFilter(getColor(R.color.accent));
        nowArt.setPadding(dp(14), dp(14), dp(14), dp(14));
        nowRow.addView(nowArt, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        nowTitle = text("", 14, R.color.text, true);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowSub = text("", 12, R.color.muted, false);
        nowSub.setSingleLine(true);
        nowSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(nowTitle);
        names.addView(nowSub, lp(MATCH, WRAP, 2, 0));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.setMarginStart(dp(12));
        nowRow.addView(names, nl);
        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.addView(iconButton(R.drawable.ic_prev, R.string.cd_prev, v -> { StreamHub.previous(); ui.postDelayed(this::update, 300); }), new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageButton pp = iconButton(R.drawable.ic_pause, R.string.cd_play, v -> { StreamHub.playPause(); ui.postDelayed(this::update, 300); });
        pp.setBackgroundResource(R.drawable.bg_round_btn);
        btnPlay = pp;
        tr.addView(pp, new LinearLayout.LayoutParams(dp(48), dp(48)));
        tr.addView(iconButton(R.drawable.ic_next, R.string.cd_next, v -> { StreamHub.next(); ui.postDelayed(this::update, 300); }), new LinearLayout.LayoutParams(dp(44), dp(44)));
        transport = tr;
        nowRow.addView(tr);
        now.addView(nowRow);
        nowHint = text(getString(R.string.stream_now_hint), 12, R.color.muted, false);
        now.addView(nowHint, lp(MATCH, WRAP, 8, 0));

        // what goes out: songs, videos and photos can each be streamed or kept to this phone
        col.addView(label(getString(R.string.stream_share_caps)), lp(MATCH, WRAP, 22, 0));
        LinearLayout sh = group();
        col.addView(sh, lp(MATCH, WRAP, 8, 0));
        final int[] shIcons = {R.drawable.ic_music, R.drawable.ic_play, R.drawable.ic_image};
        final int[] shNames = {R.string.stream_share_songs, R.string.stream_share_videos, R.string.stream_share_photos};
        for (int k = 0; k < 3; k++) {
            final int kind = k;
            Switch sw = new Switch(this);
            sw.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
            TextView ss = text("", 12, R.color.muted, false);
            shareSw[k] = sw;
            shareSub[k] = ss;
            sh.addView(row(shIcons[k], getString(shNames[k]), ss, sw, sw::toggle));
            sw.setChecked(StreamPrefs.share(this, k));
            ss.setText(sw.isChecked() ? R.string.stream_share_sub_on : R.string.stream_share_sub_off);
            sw.setOnCheckedChangeListener((b, on) -> {
                StreamHub.setShare(this, kind, on);
                shareSub[kind].setText(on ? R.string.stream_share_sub_on : R.string.stream_share_sub_off);
                update();
            });
        }

        // videos can go out as sound only
        final Switch aoSw = new Switch(this);
        aoSw.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        final TextView aoSub = text("", 12, R.color.muted, false);
        sh.addView(row(R.drawable.ic_music, getString(R.string.stream_audio_only), aoSub, aoSw, aoSw::toggle));
        aoSw.setChecked(StreamPrefs.audioOnly(this));
        aoSub.setText(aoSw.isChecked() ? R.string.stream_audio_only_on : R.string.stream_audio_only_off);
        aoSw.setOnCheckedChangeListener((b, on) -> {
            StreamHub.setAudioOnly(this, on);
            aoSub.setText(on ? R.string.stream_audio_only_on : R.string.stream_audio_only_off);
        });

        // playback
        col.addView(label(getString(R.string.stream_playback)), lp(MATCH, WRAP, 22, 0));
        LinearLayout pb = group();
        col.addView(pb, lp(MATCH, WRAP, 8, 0));
        silence = new Switch(this);
        silence.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        silenceSub = text("", 12, R.color.muted, false);
        pb.addView(row(R.drawable.ic_mute, getString(R.string.stream_silence), silenceSub, silence, () -> silence.toggle()));
        silence.setChecked(StreamPrefs.silence(this));
        silence.setOnCheckedChangeListener((b, on) -> { StreamHub.setSilence(this, on); update(); });
        hvolSw = new Switch(this);
        hvolSw.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        TextView hvSub = text("", 12, R.color.muted, false);
        hvSub.setVisibility(View.GONE);
        pb.addView(row(R.drawable.ic_devices, getString(R.string.stream_hvol), hvSub, hvolSw, () -> hvolSw.toggle()));
        hvolSw.setChecked(StreamPrefs.hostVolOn(this));
        hvolSw.setOnCheckedChangeListener((b, on) -> {
            StreamPrefs.setHostVolOn(this, on);
            StreamHub.hostVolumeChanged(this);
        });
        limitSub = text("", 12, R.color.muted, false);
        pb.addView(row(R.drawable.ic_devices, getString(R.string.stream_limit), limitSub, chevron(), this::pickLimit));
        blockedSub = text("", 12, R.color.muted, false);
        pb.addView(row(R.drawable.ic_mute, getString(R.string.stream_blocked), blockedSub, chevron(), this::showBlocked));

        // library for listeners
        col.addView(label(getString(R.string.stream_library_caps)), lp(MATCH, WRAP, 22, 0));
        LinearLayout lib = group();
        col.addView(lib, lp(MATCH, WRAP, 8, 0));
        library = new Switch(this);
        library.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        TextView libSub = text(getString(R.string.stream_pick_sub), 12, R.color.muted, false);
        lib.addView(row(R.drawable.ic_list, getString(R.string.stream_pick), libSub, library, () -> library.toggle()));
        library.setChecked(StreamPrefs.libraryOn(this));
        library.setOnCheckedChangeListener((b, on) -> { StreamPrefs.setLibraryOn(this, on); StreamHub.libraryChanged(this); update(); });
        sharedSub = text("", 12, R.color.muted, false);
        lib.addView(row(R.drawable.ic_folder, getString(R.string.stream_shared), sharedSub, chevron(),
                () -> startActivity(new Intent(this, SharedLibraryActivity.class))));

        // who is listening
        listenBox = new LinearLayout(this);
        ((LinearLayout) listenBox).setOrientation(LinearLayout.VERTICAL);
        listenHead = label("");
        ((LinearLayout) listenBox).addView(listenHead);
        listenRows = new LinearLayout(this);
        listenRows.setOrientation(LinearLayout.VERTICAL);
        ((LinearLayout) listenBox).addView(listenRows, lp(MATCH, WRAP, 8, 0));
        col.addView(listenBox, lp(MATCH, WRAP, 22, 0));

        // the other way round: play another phone's Stream in this app
        col.addView(label(getString(R.string.listen_caps)), lp(MATCH, WRAP, 22, 0));
        LinearLayout lis = group();
        col.addView(lis, lp(MATCH, WRAP, 8, 0));
        lis.addView(row(R.drawable.ic_devices, getString(R.string.listen_to_other), text(getString(R.string.listen_to_other_sub), 12, R.color.muted, false),
                chevron(), () -> startActivity(new Intent(this, ListenActivity.class))));
    }

    @Override
    protected void onResume() {
        super.onResume();
        silence.setChecked(StreamPrefs.silence(this));
        library.setChecked(StreamPrefs.libraryOn(this));
        hvolSw.setChecked(StreamPrefs.hostVolOn(this));
        for (int k = 0; k < 3; k++) shareSw[k].setChecked(StreamPrefs.share(this, k));
        countAt = 0;
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    // ── Start ────────────────────────────────────────────────────────────────

    private void tryStart() {
        if (FtpService.ipAddress() == null) {
            Ui.toast(this, R.string.share_no_wifi);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);   // optional; streaming starts anyway
        StreamService.start(this);
    }

    /** KICK: remove for now (they can be brought back, and a new stream starts clean) or block for good. */
    private void askKick(String id, String name) {
        Dialogs.builder(this)
                .setTitle(getString(R.string.stream_kick_title, name))
                .setMessage(R.string.stream_kick_msg)
                .setPositiveButton(R.string.stream_block_good, (d, w) -> { StreamHub.block(id); update(); })
                .setNegativeButton(R.string.stream_remove_now, (d, w) -> { StreamHub.kick(id); update(); })
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    /** The devices blocked for good, with a way to let them back. Works with Stream on or off. */
    private void showBlocked() {
        final List<String[]> list = StreamPrefs.blocked(this);
        if (list.isEmpty()) {
            Dialogs.builder(this).setTitle(R.string.stream_blocked).setMessage(R.string.stream_blocked_none)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        final String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = (list.get(i)[1].isEmpty() ? getString(R.string.stream_unknown_device) : list.get(i)[1]) + " · " + list.get(i)[0];
        final boolean[] on = new boolean[labels.length];
        Dialogs.builder(this)
                .setTitle(R.string.stream_blocked)
                .setMultiChoiceItems(labels, on, (d, w, checked) -> on[w] = checked)
                .setPositiveButton(R.string.stream_unblock_selected, (d, w) -> {
                    for (int i = 0; i < on.length; i++) if (on[i]) StreamHub.unblock(this, list.get(i)[0]);
                    update();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** STOP: with listeners on a song, ask whether they may finish it or everything stops now. */
    private void askStop() {
        if (StreamService.draining || !StreamHub.canDrain()) {
            StreamService.stop(this);
            return;
        }
        final boolean song = StreamHub.canSong(), queue = StreamHub.canQueue();
        final java.util.ArrayList<String> names = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> acts = new java.util.ArrayList<>();      // 0 = this song, 1 = whole queue, 2 = stop now
        if (song) { names.add(getString(R.string.stream_let_song)); acts.add(0); }
        if (queue) { names.add(getString(R.string.stream_let_queue)); acts.add(1); }
        names.add(getString(R.string.stream_stop_now)); acts.add(2);
        Dialogs.builder(this)
                .setTitle(R.string.stream_stop_title)
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    int a = acts.get(which);
                    if (a == 2) StreamService.stop(this);
                    else StreamService.finishLast(this, a == 1);
                    ui.postDelayed(this::update, 400);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Edit the address ─────────────────────────────────────────────────────

    private void editAddress() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        box.addView(label(getString(R.string.stream_name_caps)));
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText name = field(StreamPrefs.name(this), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, dp(48), 1f));
        ImageButton dice = iconButton(R.drawable.ic_refresh, R.string.stream_random_name, v -> name.setText(StreamPrefs.newRandomName()));
        dice.setBackgroundResource(R.drawable.bg_round_btn);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(48), dp(48));
        dl.setMarginStart(dp(8));
        nameRow.addView(dice, dl);
        box.addView(nameRow, lp(MATCH, WRAP, 6, 0));

        box.addView(label(getString(R.string.stream_port_caps)), lp(MATCH, WRAP, 14, 0));
        EditText port = field(String.valueOf(StreamPrefs.port(this)), InputType.TYPE_CLASS_NUMBER);
        box.addView(port, lp(MATCH, 48, 6, 0));

        box.addView(label(getString(R.string.stream_new_address_caps)), lp(MATCH, WRAP, 14, 0));
        TextView preview = text("", 13, R.color.accent, false);
        preview.setBackground(rounded(0xFF0B1222, 10));
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.addView(preview, lp(MATCH, WRAP, 6, 0));
        TextView ipNote = text(getString(R.string.stream_ip_note), 12, R.color.muted, false);
        box.addView(ipNote, lp(MATCH, WRAP, 6, 0));

        LinearLayout pinRow = new LinearLayout(this);
        pinRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout pinTexts = new LinearLayout(this);
        pinTexts.setOrientation(LinearLayout.VERTICAL);
        pinTexts.addView(text(getString(R.string.stream_pin), 14, R.color.text, false));
        pinTexts.addView(text(getString(R.string.stream_pin_sub), 12, R.color.muted, false));
        pinRow.addView(pinTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch pinSw = new Switch(this);
        pinSw.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        pinSw.setChecked(StreamPrefs.pinOn(this));
        pinRow.addView(pinSw);
        box.addView(pinRow, lp(MATCH, WRAP, 14, 0));
        EditText pin = field(StreamPrefs.pin(this), InputType.TYPE_CLASS_NUMBER);
        pin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        pin.setVisibility(pinSw.isChecked() ? View.VISIBLE : View.GONE);
        box.addView(pin, lp(MATCH, 48, 8, 0));

        TextView warn = text(getString(R.string.stream_guess_warn), 12, R.color.text, false);
        warn.setBackground(stroked(0x14FF4F6A, 0x66FF4F6A, 10));
        warn.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(warn, lp(MATCH, WRAP, 14, 0));
        box.addView(text(getString(R.string.stream_restart_note), 12, R.color.muted, false), lp(MATCH, WRAP, 10, 0));

        Runnable refresh = () -> {
            String ip = FtpService.ipAddress();
            String n = name.getText().toString().trim();
            preview.setText("http://" + (ip == null ? "…" : ip) + ":" + port.getText() + "/" + n);
            warn.setVisibility(StreamPrefs.guessable(n) && !pinSw.isChecked() ? View.VISIBLE : View.GONE);
            pin.setVisibility(pinSw.isChecked() ? View.VISIBLE : View.GONE);
        };
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refresh.run(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        };
        name.addTextChangedListener(tw);
        port.addTextChangedListener(tw);
        pinSw.setOnCheckedChangeListener((b, on) -> refresh.run());
        refresh.run();

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog d = Dialogs.builder(this)
                .setTitle(R.string.stream_edit_title)
                .setView(sv)
                .setPositiveButton(R.string.save_caps, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            int p = 0;
            try { p = Integer.parseInt(port.getText().toString().trim()); } catch (Exception ignored) { }
            if (!StreamPrefs.validName(n)) { Ui.toast(this, R.string.stream_bad_name); return; }
            if (p < 1024 || p > 65535) { Ui.toast(this, R.string.stream_bad_port); return; }
            if (pinSw.isChecked() && !pin.getText().toString().matches("\\d{4}")) { Ui.toast(this, R.string.stream_bad_pin); return; }
            boolean changed = !n.equals(StreamPrefs.name(this)) || p != StreamPrefs.port(this)
                    || pinSw.isChecked() != StreamPrefs.pinOn(this) || (pinSw.isChecked() && !pin.getText().toString().equals(StreamPrefs.pin(this)));
            StreamPrefs.setName(this, n);
            StreamPrefs.setPort(this, p);
            StreamPrefs.setPin(this, pinSw.isChecked(), pin.getText().toString());
            d.dismiss();
            if (changed && StreamService.running) {
                StreamService.stop(this);
                ui.postDelayed(() -> StreamService.start(this), 900);
                Ui.toast(this, R.string.share_restarted);
            }
            ui.postDelayed(this::update, 1200);
        }));
        d.show();
    }

    private EditText field(String value, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(getColor(R.color.text));
        e.setTextSize(15);
        e.setBackground(stroked(0xFF0B1222, 0x1F00E5FF, 10));
        e.setPadding(dp(12), 0, dp(12), 0);
        return e;
    }

    private void pickLimit() {
        int[] vals = {0, 2, 5, 10, 20};
        String[] names = {getString(R.string.stream_no_limit), "2", "5", "10", "20"};
        int cur = StreamPrefs.limit(this), at = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) at = i;
        Dialogs.builder(this)
                .setTitle(R.string.stream_limit)
                .setSingleChoiceItems(names, at, (d, w) -> {
                    StreamPrefs.setLimit(this, vals[w]);
                    d.dismiss();
                    update();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Screen ───────────────────────────────────────────────────────────────

    private void update() {
        boolean on = StreamService.running;
        String addr = StreamService.address(this);
        icon.setColorFilter(getColor(on ? R.color.success : R.color.muted));
        View qrBox = (View) qr.getParent();
        View intro2 = ((View) status.getParent()).findViewWithTag("intro2");
        if (on) {
            int n = StreamHub.listenerCount();
            boolean fin = StreamService.draining;
            status.setText(fin ? R.string.stream_finishing : R.string.stream_on);
            sub.setText(fin ? getString(R.string.stream_finishing_sub, n)
                    : n > 0 ? getResources().getQuantityString(R.plurals.stream_listeners, n, n) : getString(R.string.stream_on_sub));
            address.setText(addr == null ? getString(R.string.share_no_wifi) : addr);
            addressBox.setVisibility(View.VISIBLE);
            shareBtn.setVisibility(addr == null ? View.GONE : View.VISIBLE);
            qrBox.setVisibility(addr == null ? View.GONE : View.VISIBLE);
            scanNote.setVisibility(addr == null ? View.GONE : View.VISIBLE);
            if (addr != null && !addr.equals(shownQr)) { shownQr = addr; qr.setImageBitmap(qrBitmap(addr)); }
            intro.setVisibility(View.GONE);
            if (intro2 != null) intro2.setVisibility(View.GONE);
            keepNote.setVisibility(View.VISIBLE);
            button.setText(fin ? R.string.stream_stop_now_caps : R.string.stop_caps);
            button.setBackgroundResource(R.drawable.bg_stop_btn);
            button.setTextColor(0xFFFF4F6A);
        } else {
            status.setText(R.string.stream_off);
            sub.setText(StreamService.error != null ? StreamService.error
                    : addr == null ? getString(R.string.share_no_wifi) : getString(R.string.stream_off_sub));
            addressBox.setVisibility(View.GONE);
            shareBtn.setVisibility(View.GONE);
            qrBox.setVisibility(View.GONE);
            scanNote.setVisibility(View.GONE);
            intro.setVisibility(View.VISIBLE);
            if (intro2 != null) intro2.setVisibility(View.VISIBLE);
            keepNote.setVisibility(View.GONE);
            button.setText(R.string.start_caps);
            button.setBackgroundResource(R.drawable.bg_start_btn);
            button.setTextColor(getColor(R.color.bg));
        }

        int nb = StreamPrefs.blocked(this).size();
        blockedSub.setText(nb == 0 ? getString(R.string.stream_blocked_sub0) : getResources().getQuantityString(R.plurals.stream_blocked_n, nb, nb));

        // the song or video that is going out
        sectionNow.setText(on ? R.string.stream_now : R.string.stream_what);
        boolean has = StreamHub.running && StreamHub.hasItem();
        if (!StreamHub.running) has = false;
        if (has) {
            nowTitle.setText(StreamHub.nowTitle());
            String a = StreamHub.nowArtist();
            String t = Ui.time(StreamHub.nowPos()) + " / " + Ui.time(StreamHub.nowDur());
            nowSub.setText(!StreamHub.nowOk() ? getString(R.string.stream_cant)
                    : (a.isEmpty() ? t : a + " · " + t));
            String id = StreamHub.nowId();
            if (!id.equals(shownArtId)) {
                shownArtId = id;
                new Thread(() -> {
                    Bitmap b = StreamHub.nowArt();
                    ui.post(() -> {
                        if (b != null && id.equals(shownArtId)) { nowArt.setPadding(0, 0, 0, 0); nowArt.clearColorFilter(); nowArt.setImageBitmap(b); }
                    });
                }).start();
            }
        } else {
            boolean priv = StreamHub.running && StreamHub.nowPrivate();
            nowTitle.setText(priv ? R.string.stream_private_now : R.string.stream_nothing);
            nowSub.setText(priv ? R.string.stream_private_sub : R.string.stream_what_sub);
            if (!shownArtId.isEmpty()) {
                shownArtId = "";
                nowArt.setPadding(dp(14), dp(14), dp(14), dp(14));
                nowArt.setColorFilter(getColor(R.color.accent));
                nowArt.setImageResource(R.drawable.ic_music);
            }
        }
        transport.setVisibility(on ? View.VISIBLE : View.GONE);
        nowHint.setVisibility(on ? View.VISIBLE : View.GONE);
        if (btnPlay != null) {
            btnPlay.setImageResource(StreamHub.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            btnPlay.setColorFilter(getColor(R.color.accent));
        }

        silenceSub.setText(StreamPrefs.silence(this) ? R.string.stream_silence_on : R.string.stream_silence_off);
        int lim = StreamPrefs.limit(this);
        limitSub.setText(lim == 0 ? getString(R.string.stream_no_limit) : String.valueOf(lim));
        boolean chosen = !StreamPrefs.MODE_ALL.equals(StreamPrefs.shareMode(this));
        sharedSub.setText(getString(R.string.stream_shared_sub,
                chosen ? getString(R.string.stream_chosen) : getString(R.string.stream_all_kinds),
                sharedCount.isEmpty() ? "…" : sharedCount,
                getString(StreamPrefs.allowDownloads(this) ? R.string.stream_dl_on : R.string.stream_dl_off)));
        if (!counting && System.currentTimeMillis() - countAt > 15_000) {
            counting = true;
            new Thread(() -> {
                int n = 0;
                try { n = chosen ? StreamShare.items(this).size() : StreamShare.countAll(this); } catch (Throwable ignored) { }
                final int count = n;
                ui.post(() -> {
                    sharedCount = getResources().getQuantityString(R.plurals.stream_items, count, count);
                    countAt = System.currentTimeMillis();
                    counting = false;
                });
            }).start();
        }

        // listeners
        List<String[]> rows = on ? StreamHub.listenerRows() : new java.util.ArrayList<>();
        listenBox.setVisibility(on ? View.VISIBLE : View.GONE);
        listenHead.setText(getString(R.string.stream_listening_caps, rows.size()));
        listenRows.removeAllViews();
        for (String[] r : rows) {
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackgroundResource(R.drawable.bg_row);
            item.setPadding(dp(12), dp(10), dp(10), dp(10));
            ImageView ic = new ImageView(this);
            ic.setImageResource(R.drawable.ic_phone);
            ic.setColorFilter(getColor(R.color.success));
            item.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.addView(text(r[1], 14, R.color.text, true));
            t.addView(text(r[2], 12, R.color.muted, false), lp(MATCH, WRAP, 2, 0));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tl.setMarginStart(dp(12));
            item.addView(t, tl);
            TextView kick = new TextView(this);
            kick.setText(R.string.kick_caps);
            kick.setGravity(Gravity.CENTER);
            kick.setTextSize(11);
            kick.setLetterSpacing(0.14f);
            kick.setTypeface(null, android.graphics.Typeface.BOLD);
            kick.setTextColor(0xFFFF4F6A);
            kick.setBackground(stroked(0x14FF4F6A, 0x80FF4F6A, 22));
            kick.setPadding(dp(16), 0, dp(16), 0);
            kick.setOnClickListener(v -> askKick(r[0], r[1]));
            item.addView(kick, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
            listenRows.addView(item, lp(MATCH, WRAP, 8, 0));
        }

        // devices that were kicked: they can be brought back
        List<String[]> out = on ? StreamHub.kickedRows() : new java.util.ArrayList<>();
        if (!out.isEmpty()) {
            TextView head = label(getString(R.string.stream_kicked_caps, out.size()));
            listenRows.addView(head, lp(MATCH, WRAP, 20, 0));
            for (String[] r : out) {
                LinearLayout item = new LinearLayout(this);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setBackgroundResource(R.drawable.bg_row);
                item.setPadding(dp(12), dp(10), dp(10), dp(10));
                ImageView ic = new ImageView(this);
                ic.setImageResource(R.drawable.ic_phone);
                ic.setColorFilter(getColor(R.color.muted));
                item.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
                LinearLayout t = new LinearLayout(this);
                t.setOrientation(LinearLayout.VERTICAL);
                t.addView(text(r[1], 14, R.color.text, true));
                t.addView(text(r[2], 12, R.color.muted, false), lp(MATCH, WRAP, 2, 0));
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tl.setMarginStart(dp(12));
                item.addView(t, tl);
                TextView back = new TextView(this);
                back.setText(R.string.bring_back_caps);
                back.setGravity(Gravity.CENTER);
                back.setTextSize(11);
                back.setLetterSpacing(0.14f);
                back.setTypeface(null, android.graphics.Typeface.BOLD);
                back.setTextColor(0xFF00E5FF);
                back.setBackground(stroked(0x1400E5FF, 0x8000E5FF, 22));
                back.setPadding(dp(16), 0, dp(16), 0);
                back.setOnClickListener(v -> { StreamHub.unkick(r[0]); update(); });
                item.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
                listenRows.addView(item, lp(MATCH, WRAP, 8, 0));
            }
        }
    }

    private static Bitmap qrBitmap(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0);
            int w = m.getWidth(), h = m.getHeight();
            int scale = Math.max(1, 560 / Math.max(w, h));
            Bitmap b = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888);
            int dark = 0xFF05080F, light = Color.WHITE;
            for (int y = 0; y < h * scale; y++)
                for (int x = 0; x < w * scale; x++) b.setPixel(x, y, m.get(x / scale, y / scale) ? dark : light);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Small view helpers ───────────────────────────────────────────────────

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private LinearLayout.LayoutParams lp(int w, int h, int topDp, int startDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h > 0 ? dp(h) : h);
        p.topMargin = dp(topDp);
        p.setMarginStart(dp(startDp));
        return p;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 10, R.color.muted, false);
        t.setLetterSpacing(0.18f);
        return t;
    }

    private ImageButton iconButton(int res, int desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setColorFilter(getColor(R.color.accent));
        b.setContentDescription(getString(desc));
        b.setOnClickListener(l);
        return b;
    }

    private View outlined(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12);
        t.setLetterSpacing(0.14f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(getColor(R.color.accent));
        t.setBackground(stroked(Color.TRANSPARENT, getColor(R.color.accent), 23));
        t.setOnClickListener(l);
        return t;
    }

    private android.graphics.drawable.GradientDrawable rounded(int fill, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private android.graphics.drawable.GradientDrawable stroked(int fill, int stroke, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = rounded(fill, radiusDp);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout group() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackgroundResource(R.drawable.bg_options_bar);
        g.setPadding(dp(6), dp(6), dp(6), dp(6));
        return g;
    }

    private View chevron() {
        ImageView c = new ImageView(this);
        c.setImageResource(R.drawable.ic_chevron_right);
        c.setColorFilter(getColor(R.color.muted));
        return c;
    }

    /** An icon, a title with a line under it, and something at the end (a switch or an arrow). */
    private View row(int iconRes, String title, TextView subText, View end, Runnable tap) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(60));
        r.setPadding(dp(10), dp(6), dp(10), dp(6));
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setColorFilter(getColor(R.color.accent));
        r.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(title, 14, R.color.text, false));
        t.addView(subText, lp(MATCH, WRAP, 2, 0));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(14));
        tl.setMarginEnd(dp(8));
        r.addView(t, tl);
        r.addView(end, new LinearLayout.LayoutParams(end instanceof ImageView ? dp(18) : WRAP, end instanceof ImageView ? dp(18) : WRAP));
        r.setOnClickListener(v -> tap.run());
        return r;
    }
}
