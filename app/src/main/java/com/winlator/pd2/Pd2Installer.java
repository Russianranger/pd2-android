package com.winlator.pd2;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Imports an existing licensed installation; never modifies or deletes the selected source. */
public final class Pd2Installer {
    private static final Object LOCK = new Object();

    private Pd2Installer() {}

    public interface Progress extends Pd2ImportIO.Progress {}

    public static File installedDirectory(Context context) {
        return new File(context.getFilesDir(), "pd2/install");
    }

    public static Pd2InstallValidator.Result importFolder(Context context, Uri tree, Progress progress) throws IOException {
        synchronized (LOCK) {
            recoverLocked(context);
            File work = createWork(context);
            File candidate = new File(work, "candidate");
            try {
                if (!DocumentsContract.isTreeUri(tree)) throw new IOException("Choose a folder using the Android folder picker.");
                String documentId = DocumentsContract.getTreeDocumentId(tree);
                Pd2ImportIO.Budget budget = new Pd2ImportIO.Budget(candidate, progress);
                copyChildren(context, tree, documentId, candidate, "", new Pd2ImportIO.PathRegistry(),
                        budget, new HashSet<>(), 0, new int[] {0});
                return validateAndPromote(context, candidate);
            } catch (RuntimeException exception) {
                throw new IOException("Cannot read the selected folder: " + exception.getMessage(), exception);
            } finally {
                deleteTemporary(work);
            }
        }
    }

    public static Pd2InstallValidator.Result importZip(Context context, Uri uri, Progress progress) throws IOException {
        synchronized (LOCK) {
            recoverLocked(context);
            File work = createWork(context);
            File candidate = new File(work, "candidate");
            File archive = new File(work, "source.zip");
            try {
                try (InputStream source = context.getContentResolver().openInputStream(uri)) {
                    if (source == null) throw new IOException("Cannot open the selected ZIP.");
                    copyArchive(source, archive, progress);
                }
                Pd2ImportIO.extractZip(archive, candidate, progress);
                return validateAndPromote(context, candidate);
            } catch (RuntimeException exception) {
                throw new IOException("Cannot import the selected ZIP: " + exception.getMessage(), exception);
            } finally {
                deleteTemporary(work);
            }
        }
    }

    /** Call off the UI thread. Recovers an interrupted directory promotion and removes incomplete staging data. */
    public static void recover(Context context) throws IOException {
        synchronized (LOCK) { recoverLocked(context); }
    }

    private static File parent(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "pd2");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create PD2 storage.");
        return directory;
    }

    private static void recoverLocked(Context context) throws IOException {
        File parent = parent(context);
        Pd2ImportIO.recoverInstallation(parent);
        File[] children = parent.listFiles();
        if (children == null) throw new IOException("Cannot read PD2 storage.");
        for (File child : children) if (child.getName().startsWith(".import-")) Pd2ImportIO.deleteTree(child);
    }

    private static File createWork(Context context) throws IOException {
        File directory = new File(parent(context), ".import-" + UUID.randomUUID());
        if (!new File(directory, "candidate").mkdirs()) throw new IOException("Cannot create import staging directory.");
        return directory;
    }

    private static Pd2InstallValidator.Result validateAndPromote(Context context, File candidate) throws IOException {
        return Pd2ImportIO.promoteValidated(candidate, parent(context));
    }

    private static void copyChildren(Context context, Uri tree, String documentId, File candidate, String prefix,
                                     Pd2ImportIO.PathRegistry paths, Pd2ImportIO.Budget budget, Set<String> visited,
                                     int depth, int[] count) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled.");
        if (depth > Pd2ImportIO.MAX_DEPTH || !visited.add(documentId)) throw new IOException("The folder contains a cycle or excessive nesting.");
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS};
        try (Cursor cursor = context.getContentResolver().query(childrenUri, columns, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot enumerate the selected folder.");
            while (cursor.moveToNext()) {
                if (++count[0] > Pd2ImportIO.MAX_ENTRIES) throw new IOException("The folder contains too many entries.");
                String childId = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                if (childId == null) throw new IOException("The storage provider returned an invalid document.");
                Pd2ImportIO.requireSafePath(name);
                if (name.indexOf('/') >= 0) throw new IOException("A document name contains a path separator.");
                String path = prefix.isEmpty() ? name : prefix + "/" + name;
                boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (!cursor.isNull(4) && (cursor.getLong(4) & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0)
                    throw new IOException("Virtual documents are not supported: " + path);
                paths.reserve(path, directory);
                File target = Pd2ImportIO.target(candidate, path, directory);
                if (directory) copyChildren(context, tree, childId, candidate, path, paths, budget, visited, depth + 1, count);
                else {
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, childId);
                    try (InputStream input = context.getContentResolver().openInputStream(documentUri)) {
                        if (input == null) throw new IOException("Cannot open " + path);
                        long expectedSize = cursor.isNull(3) ? -1 : cursor.getLong(3);
                        budget.copy(input, target, path, expectedSize, -1);
                    }
                }
            }
        }
    }

    private static void copyArchive(InputStream source, File destination, Progress progress) throws IOException {
        long copied = 0, nextCheck = 0, lastUpdate = 0;
        byte[] buffer = new byte[64 * 1024];
        try (FileOutputStream output = new FileOutputStream(destination)) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled.");
                if (read == 0) continue;
                if (read > Pd2ImportIO.MAX_ZIP_BYTES - copied) throw new IOException("The ZIP exceeds the supported 12 GB limit.");
                if (copied >= nextCheck) {
                    long usable = destination.getParentFile().getUsableSpace();
                    if (usable > 0 && usable < Pd2ImportIO.FREE_SPACE_RESERVE)
                        throw new IOException("Not enough internal storage to copy the ZIP.");
                    nextCheck = copied + 8L * 1024 * 1024;
                }
                output.write(buffer, 0, read);
                copied += read;
                long now = System.nanoTime();
                if (progress != null && now - lastUpdate > 250000000L) {
                    progress.onProgress("Reading ZIP", copied, 0);
                    lastUpdate = now;
                }
            }
            output.getFD().sync();
        }
    }

    private static void deleteTemporary(File file) {
        try { Pd2ImportIO.deleteTree(file); }
        catch (IOException ignored) { /* Recovery removes remaining staging data on the next operation. */ }
    }
}
