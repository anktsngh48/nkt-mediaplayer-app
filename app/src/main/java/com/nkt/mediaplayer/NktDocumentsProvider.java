package com.nkt.mediaplayer;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Makes NKT Media Player appear as a place in other apps' file pickers (Google Drive "Upload", WhatsApp "Document",
 * email attachments…). Read-only: files can be chosen from here, never changed or deleted. Needs "All files access",
 * the same permission the hidden-files option uses. Hidden files and folders show only when "show hidden" is on.
 */
public class NktDocumentsProvider extends DocumentsProvider {

    private static final String ROOT_ID = "nkt";
    private static final String[] ROOT_COLUMNS = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_MIME_TYPES};
    private static final String[] DOC_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};

    private static File base() { return Environment.getExternalStorageDirectory(); }

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(Root.COLUMN_ROOT_ID, ROOT_ID);
        r.add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD);
        r.add(Root.COLUMN_ICON, R.mipmap.ic_launcher);
        r.add(Root.COLUMN_TITLE, getContext().getString(R.string.app_name));
        r.add(Root.COLUMN_DOCUMENT_ID, base().getAbsolutePath());
        r.add(Root.COLUMN_MIME_TYPES, "*/*");
        return c;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DOC_COLUMNS);
        add(c, fileOf(documentId));
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DOC_COLUMNS);
        if (!HiddenScanner.hasAllFilesAccess(getContext())) {
            Bundle extras = new Bundle();
            extras.putCharSequence(DocumentsContract.EXTRA_INFO,
                    getContext().getString(R.string.docs_need_access));
            c.setExtras(extras);
            return c;
        }
        File dir = fileOf(parentDocumentId);
        File[] kids = dir.listFiles();
        if (kids == null) return c;
        boolean hidden = Prefs.showHidden(getContext());
        boolean atRoot = dir.equals(base());
        Arrays.sort(kids, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File f : kids) {
            String n = f.getName();
            if (!hidden && n.startsWith(".")) continue;
            if (atRoot && n.equals("Android")) continue;            // app data: not reachable anyway
            add(c, f);
        }
        return c;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!mode.startsWith("r") || mode.contains("w")) throw new SecurityException("Read only");
        if (!HiddenScanner.hasAllFilesAccess(getContext())) throw new SecurityException("Allow All files access in NKT Media Player");
        File f = fileOf(documentId);
        if (f.isDirectory()) throw new FileNotFoundException("Folder");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            String p = new File(parentDocumentId).getCanonicalPath(), d = new File(documentId).getCanonicalPath();
            return d.equals(p) || d.startsWith(p + "/");
        } catch (Exception e) {
            return false;
        }
    }

    // ── helpers ──

    /** The document id is the file's path; only paths inside the phone's storage are accepted. */
    private static File fileOf(String documentId) throws FileNotFoundException {
        try {
            File f = new File(documentId).getCanonicalFile();
            String root = base().getCanonicalPath();
            String p = f.getPath();
            if (!(p.equals(root) || p.startsWith(root + "/"))) throw new FileNotFoundException("Outside storage");
            if (!f.exists()) throw new FileNotFoundException("Missing");
            return f;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException("Bad path");
        }
    }

    private static void add(MatrixCursor c, File f) {
        boolean dir = f.isDirectory();
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(Document.COLUMN_DOCUMENT_ID, f.getAbsolutePath());
        r.add(Document.COLUMN_DISPLAY_NAME, f.equals(base()) ? "NKT Media Player" : f.getName());
        r.add(Document.COLUMN_MIME_TYPE, dir ? Document.MIME_TYPE_DIR : mimeOf(f.getName()));
        r.add(Document.COLUMN_SIZE, dir ? null : f.length());
        r.add(Document.COLUMN_LAST_MODIFIED, f.lastModified());
        r.add(Document.COLUMN_FLAGS, 0);
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }
}
