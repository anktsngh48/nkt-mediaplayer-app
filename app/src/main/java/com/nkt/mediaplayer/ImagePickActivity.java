package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * NKT's own picture chooser, used wherever the app asks for a picture (album art, app logo), so Android's
 * gallery app is not opened. Tap a picture to choose it. "OTHER APPS" is still there for pictures that live elsewhere.
 * Returns the picture's address as the result data, like Android's chooser does.
 */
public class ImagePickActivity extends Activity {

    private static final int OTHER = 91;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<MediaInfo> pics = new ArrayList<>();
    private RecyclerView grid;
    private TextView empty;

    /** The intent to start for a result: it replaces the system chooser. */
    public static Intent intent(android.content.Context c) { return new Intent(c, ImagePickActivity.class); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pick_image);
        Ui.padForSystemBars(findViewById(R.id.root));
        TextView title = findViewById(R.id.pi_title);
        title.setTypeface(Ui.orbitron(this));
        ((TextView) findViewById(R.id.pi_other)).setTypeface(Ui.orbitron(this));
        findViewById(R.id.pi_back).setOnClickListener(v -> finish());
        findViewById(R.id.pi_other).setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(Intent.createChooser(pick, getString(R.string.pick_image_title)), OTHER); }
            catch (Exception e) { Ui.toast(this, R.string.no_picker); }
        });
        empty = findViewById(R.id.pi_empty);
        grid = findViewById(R.id.pi_grid);
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        grid.setAdapter(new Adapter());

        new Thread(() -> {
            List<MediaInfo> l = new ArrayList<>();
            try { l = MediaLibrary.images(this); } catch (Throwable ignored) { }
            final List<MediaInfo> got = l;
            ui.post(() -> {
                if (isFinishing()) return;
                pics.clear();
                pics.addAll(got);
                grid.getAdapter().notifyDataSetChanged();
                empty.setVisibility(pics.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OTHER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            setResult(RESULT_OK, new Intent().setData(data.getData()));
            finish();
        }
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return pics.size(); }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.gallery_cell, parent, false);
            v.findViewById(R.id.gc_badge).setVisibility(View.GONE);
            v.findViewById(R.id.gc_check).setVisibility(View.GONE);
            return new RecyclerView.ViewHolder(v) { };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            MediaInfo m = pics.get(position);
            Thumbs.load((ImageView) h.itemView.findViewById(R.id.gc_img), m, 256);
            h.itemView.setOnClickListener(v -> {
                setResult(RESULT_OK, new Intent().setData(m.uri));
                finish();
            });
        }
    }
}
