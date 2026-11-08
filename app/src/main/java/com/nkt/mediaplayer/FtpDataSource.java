package com.nkt.mediaplayer;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.io.InputStream;

/** Streams ftp://host:port/path for the players (songs and videos on other devices). Seeking reopens at the offset. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class FtpDataSource extends BaseDataSource {

    private final Context app;
    private FTPClient ftp;
    private InputStream in;
    private Uri uri;
    private long left = C.LENGTH_UNSET;
    private boolean opened;

    public FtpDataSource(Context c) {
        super(true);
        app = c.getApplicationContext();
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        uri = spec.uri;
        transferInitializing(spec);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 21;
        String path = uri.getPath() == null ? "/" : uri.getPath();
        if (host == null) throw new IOException("no host");
        ftp = Remote.connect(Remote.login(app, host, port));
        long size = -1;
        try {
            FTPFile f = ftp.mlistFile(path);
            if (f != null) size = f.getSize();
        } catch (Exception ignored) { }
        if (size < 0) {
            try {
                String s = ftp.getSize(path);
                if (s != null) size = Long.parseLong(s.trim());
            } catch (Exception ignored) { }
        }
        if (spec.position > 0) ftp.setRestartOffset(spec.position);
        in = ftp.retrieveFileStream(path);
        if (in == null) { Remote.quietly(ftp); ftp = null; throw new IOException("can't open " + path); }
        if (spec.length != C.LENGTH_UNSET) left = spec.length;
        else left = size >= 0 ? Math.max(0, size - spec.position) : C.LENGTH_UNSET;
        opened = true;
        transferStarted(spec);
        return left;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (left == 0) return C.RESULT_END_OF_INPUT;
        int want = left == C.LENGTH_UNSET ? length : (int) Math.min(length, left);
        int n = in.read(buffer, offset, want);
        if (n < 0) return C.RESULT_END_OF_INPUT;
        if (left != C.LENGTH_UNSET) left -= n;
        bytesTransferred(n);
        return n;
    }

    @Override
    public Uri getUri() { return uri; }

    @Override
    public void close() {
        try { if (in != null) in.close(); } catch (Exception ignored) { }
        in = null;
        if (ftp != null) {
            // stopping mid-file: the server may not answer the transfer; just hang up
            Remote.quietly(ftp);
            ftp = null;
        }
        if (opened) { opened = false; transferEnded(); }
        uri = null;
    }
}
