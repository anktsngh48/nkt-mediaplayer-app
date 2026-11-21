package com.nkt.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Left over from an earlier version. The browser is now BrowserActivity; anything that still opens this
 * screen is passed straight on to it.
 */
public class WebActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = new Intent(this, BrowserActivity.class);
        if (getIntent() != null && getIntent().getData() != null) in.setData(getIntent().getData());
        startActivity(in);
        finish();
    }
}
