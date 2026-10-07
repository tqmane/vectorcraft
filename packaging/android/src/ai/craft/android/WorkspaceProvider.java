package ai.craft.android;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Makes user work visible in Android Files; preferences, control tokens and caches stay private. */
public final class WorkspaceProvider extends DocumentsProvider {
    private static final String ROOT_ID = "workspace:";
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES};
    private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
    // Observe only folders a file browser opened, with a bounded number of native watches.
    private final LinkedHashMap<String, android.os.FileObserver> watches = new LinkedHashMap<String, android.os.FileObserver>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, android.os.FileObserver> entry) {
            if (size() <= 64) return false;
            entry.getValue().stopWatching(); return true;
        }
    };

    static File workspace(Context context) { return new File(context.getFilesDir(), "Documents/Workspace"); }
    static String authority(Context context) { return context.getPackageName() + ".documents"; }
    static Uri uriForFile(Context context, File target) throws IOException {
        File root = workspace(context).getCanonicalFile();
        File resolved = target.getCanonicalFile();
        if (!resolved.toPath().startsWith(root.toPath())) return null;
        String id = ROOT_ID + root.toPath().relativize(resolved.toPath()).toString().replace(File.separatorChar, '/');
        file(context, id);
        return DocumentsContract.buildDocumentUri(authority(context), id);
    }
    static File file(Context context, String documentId) throws FileNotFoundException {
        try {
            if (!documentId.startsWith(ROOT_ID)) throw new IOException("Unknown workspace");
            String relative = documentId.substring(ROOT_ID.length());
            for (String part : relative.split("/")) if (part.startsWith(".")) throw new IOException("Private files are not shared");
            File root = workspace(context).getCanonicalFile();
            File file = new File(root, relative).getCanonicalFile();
            if (!file.toPath().startsWith(root.toPath())) throw new IOException("Document is outside the workspace");
            return file;
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    static File ownedFile(Context context, Uri uri) throws FileNotFoundException {
        if (!authority(context).equals(uri.getAuthority())) return null;
        String id = DocumentsContract.isDocumentUri(context, uri) ? DocumentsContract.getDocumentId(uri) : DocumentsContract.getTreeDocumentId(uri);
        return file(context, id);
    }
    private String id(File file) throws IOException {
        String relative = workspace(getContext()).getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath()).toString().replace(File.separatorChar, '/');
        return ROOT_ID + relative;
    }
    static void changed(Context context, File file) {
        try {
            if (file.getCanonicalFile().toPath().startsWith(workspace(context).getCanonicalFile().toPath())) {
                context.getContentResolver().notifyChange(DocumentsContract.buildRootsUri(authority(context)), null);
                String parent = workspace(context).getCanonicalFile().toPath().relativize(file.getParentFile().getCanonicalFile().toPath()).toString().replace(File.separatorChar, '/');
                context.getContentResolver().notifyChange(DocumentsContract.buildChildDocumentsUri(authority(context), ROOT_ID + parent), null);
            }
        } catch (IOException ignored) { /* The saved file remains valid if a file browser is gone. */ }
    }
    @Override public boolean onCreate() { File root = workspace(getContext()); if (!root.isDirectory()) root.mkdirs(); return root.isDirectory(); }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        cursor.newRow().add(Root.COLUMN_ROOT_ID, ROOT_ID).add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            .add(Root.COLUMN_TITLE, getContext().getString(R.string.app_name)).add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_AVAILABLE_BYTES, workspace(getContext()).getUsableSpace());
        return cursor;
    }
    private String mime(File file) {
        if (file.isDirectory()) return Document.MIME_TYPE_DIR;
        String name = file.getName(); int dot = name.lastIndexOf('.');
        String mime = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return mime == null ? "application/octet-stream" : mime;
    }
    private void row(MatrixCursor cursor, File file) throws FileNotFoundException {
        try {
            String id = id(file);
            file(getContext(), id); // Reject symlinks/hidden paths that escape the public root.
            if (!file.exists()) throw new FileNotFoundException("Document no longer exists");
            int flags = file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE;
            if (!ROOT_ID.equals(id)) flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
            cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, ROOT_ID.equals(id) ? getContext().getString(R.string.app_name) : file.getName())
                .add(Document.COLUMN_MIME_TYPE, mime(file)).add(Document.COLUMN_FLAGS, flags)
                .add(Document.COLUMN_SIZE, file.isDirectory() ? null : file.length()).add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        row(cursor, file(getContext(), documentId)); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        File directory = file(getContext(), parent);
        synchronized (watches) {
            if (!watches.containsKey(parent)) {
                android.os.FileObserver observer = new android.os.FileObserver(directory.getPath(), android.os.FileObserver.CREATE | android.os.FileObserver.DELETE | android.os.FileObserver.MOVED_FROM | android.os.FileObserver.MOVED_TO | android.os.FileObserver.CLOSE_WRITE) {
                    @Override public void onEvent(int event, String path) { getContext().getContentResolver().notifyChange(DocumentsContract.buildChildDocumentsUri(authority(getContext()), parent), null); }
                };
                watches.put(parent, observer); observer.startWatching();
            }
        }
        File[] files = directory.listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File child : files) if (!child.getName().startsWith(".")) { try { row(cursor, child); } catch (FileNotFoundException ignored) { /* Unsafe symlink. */ } }
        }
        return cursor;
    }
    @Override public boolean isChildDocument(String parent, String child) {
        try { return !parent.equals(child) && file(getContext(), child).toPath().startsWith(file(getContext(), parent).toPath()); }
        catch (FileNotFoundException error) { return false; }
    }
    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal cancel) throws FileNotFoundException {
        if (cancel != null) cancel.throwIfCanceled();
        File file = file(getContext(), documentId);
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode), new android.os.Handler(getContext().getMainLooper()), error -> changed(getContext(), file));
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    private void name(String name) throws FileNotFoundException {
        if (name.isEmpty() || name.startsWith(".") || name.contains("/") || name.contains("\\") || name.indexOf(0) >= 0 || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240) throw new FileNotFoundException("Invalid workspace filename");
    }
    @Override public String createDocument(String parent, String mime, String displayName) throws FileNotFoundException {
        name(displayName);
        try {
            File folder = file(getContext(), parent);
            for (int suffix = 0; suffix < 1000; suffix++) {
                int dot = displayName.lastIndexOf('.');
                String name = suffix == 0 ? displayName : dot > 0 ? displayName.substring(0, dot) + " (" + suffix + ")" + displayName.substring(dot) : displayName + " (" + suffix + ")";
                File target = new File(folder, name);
                boolean created = Document.MIME_TYPE_DIR.equals(mime) ? target.mkdir() : target.createNewFile();
                if (created) { changed(getContext(), target); return id(target); }
            }
            throw new IOException("No free filename");
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    @Override public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        name(displayName);
        if (ROOT_ID.equals(documentId)) throw new FileNotFoundException("The workspace root cannot be renamed");
        try {
            File source = file(getContext(), documentId); File target = new File(source.getParentFile(), displayName);
            Files.move(source.toPath(), target.toPath()); changed(getContext(), target); return id(target);
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        if (ROOT_ID.equals(documentId)) throw new FileNotFoundException("The workspace root cannot be deleted");
        try {
            File source = file(getContext(), documentId);
            Files.walkFileTree(source.toPath(), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException { if (error != null) throw error; Files.delete(directory); return FileVisitResult.CONTINUE; }
            });
            changed(getContext(), source);
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
}
