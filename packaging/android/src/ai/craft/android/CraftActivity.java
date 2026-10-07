package ai.craft.android;

import com.google.androidgamesdk.GameActivity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.graphics.pdf.PdfRenderer;
import android.print.*;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.system.Os;
import android.view.MotionEvent;
import android.view.KeyEvent;
import androidx.activity.OnBackPressedCallback;
import android.view.View;
import android.view.ViewGroup;
import android.view.SurfaceView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.graphics.Insets;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Android services only. The original Rust/egui application owns the complete editor UI. */
public final class CraftActivity extends GameActivity {
    public String bridgeVersion(String ignored) { return "1"; }
    static { System.loadLibrary(BuildConfig.CRAFT_LIBRARY); }
    private native void wake();
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final ConcurrentLinkedQueue<String> results = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<JSONArray> penEvents = new ArrayDeque<>();
    private final PalmRejection palms = new PalmRejection();
    private MotionEvent lastFinger;
    private volatile int modifiers;
    private volatile boolean backRequested;
    private final ConcurrentLinkedQueue<JSONArray> shortcuts = new ConcurrentLinkedQueue<>();
    private JSONObject request;
    private static final int PICK = 120;

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        modifiers = event.getMetaState();
        String name = shortcutName(event.getKeyCode());
        if (!name.isEmpty() && (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed())) {
            shortcuts.add(new JSONArray().put(name).put(event.getAction() == KeyEvent.ACTION_DOWN).put(modifiers).put(event.getRepeatCount() > 0));
            wake();
            return true;
        }
        if (KeyEvent.isModifierKey(event.getKeyCode())) wake();
        return super.dispatchKeyEvent(event);
    }
    private static String shortcutName(int key) {
        if (key >= KeyEvent.KEYCODE_A && key <= KeyEvent.KEYCODE_Z) return Character.toString((char)('A' + key - KeyEvent.KEYCODE_A));
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) return Character.toString((char)('0' + key - KeyEvent.KEYCODE_0));
        if (key >= KeyEvent.KEYCODE_F1 && key <= KeyEvent.KEYCODE_F12) return "F" + (key - KeyEvent.KEYCODE_F1 + 1);
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return "ArrowLeft";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "ArrowRight";
            case KeyEvent.KEYCODE_DPAD_UP: return "ArrowUp";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "ArrowDown";
            case KeyEvent.KEYCODE_DEL: return "Backspace";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "Delete";
            case KeyEvent.KEYCODE_TAB: return "Tab";
            case KeyEvent.KEYCODE_ENTER: return "Enter";
            case KeyEvent.KEYCODE_SPACE: return "Space";
            case KeyEvent.KEYCODE_ESCAPE: return "Escape";
            case KeyEvent.KEYCODE_PAGE_UP: return "PageUp";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "PageDown";
            case KeyEvent.KEYCODE_MOVE_HOME: return "Home";
            case KeyEvent.KEYCODE_MOVE_END: return "End";
            default: return "";
        }
    }
    public String keyboardState(String ignored) {
        JSONArray keys = new JSONArray(); JSONArray key;
        while ((key = shortcuts.poll()) != null) keys.put(key);
        try {
            boolean back = backRequested; backRequested = false;
            return new JSONObject().put("modifiers", modifiers).put("keys", keys).put("back", back).toString();
        } catch (JSONException error) { return "{}"; }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (collectPen(event)) return true;
        if (palms.finger(event.getActionMasked(), SystemClock.uptimeMillis())) { cancelFingers(); return true; }
        if (android.os.Build.VERSION.SDK_INT >= 33 && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
            cancelFingers(); return true;
        }
        if (lastFinger != null) lastFinger.recycle();
        lastFinger = event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL ? null : MotionEvent.obtain(event);
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return collectPen(event) || super.dispatchGenericMotionEvent(event);
    }
    private void cancelFingers() {
        if (lastFinger == null) return;
        lastFinger.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(lastFinger);
        lastFinger.recycle(); lastFinger = null;
        palms.cancelFingerSequence();
    }
    private boolean collectPen(MotionEvent event) {
        int index = -1;
        for (int i = 0; i < event.getPointerCount(); i++) {
            int type = event.getToolType(i);
            if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER || type == MotionEvent.TOOL_TYPE_MOUSE) { index = i; break; }
        }
        if (index < 0) return false;
        int tool = event.getToolType(index);
        int action = event.getActionMasked();
        if ((action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) && index != event.getActionIndex()) action = MotionEvent.ACTION_MOVE;
        if (android.os.Build.VERSION.SDK_INT >= 33 && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) action = MotionEvent.ACTION_CANCEL;
        if (tool != MotionEvent.TOOL_TYPE_MOUSE) { cancelFingers(); palms.pen(action, SystemClock.uptimeMillis()); }
        int[] origin = new int[2];
        if (mSurfaceView != null) mSurfaceView.getLocationOnScreen(origin);
        float dx = event.getRawX() - event.getX() - origin[0];
        float dy = event.getRawY() - event.getY() - origin[1];
        synchronized (penEvents) {
            // Android batches both contact moves and hover moves. Retain that distinction:
            // turning hover history into ACTION_MOVE would synthesize a press in the native UI.
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) {
                for (int h = 0; h < event.getHistorySize(); h++) {
                    addPen(action, event.getHistoricalX(index, h) + dx, event.getHistoricalY(index, h) + dy,
                        event.getHistoricalPressure(index, h), event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, h),
                        event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, h), tool, event.getButtonState(), event.getPointerId(index));
                }
            }
            addPen(action, event.getX(index) + dx, event.getY(index) + dy, event.getPressure(index),
                event.getAxisValue(MotionEvent.AXIS_TILT, index), event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index),
                tool, event.getButtonState(), event.getPointerId(index), event.getAxisValue(MotionEvent.AXIS_VSCROLL), event.getAxisValue(MotionEvent.AXIS_HSCROLL));
        }
        wake();
        return true;
    }
    private void addPen(int action, float x, float y, float pressure, float tilt, float orientation, int tool, int buttons, int id) {
        addPen(action, x, y, pressure, tilt, orientation, tool, buttons, id, 0, 0);
    }
    private void addPen(int action, float x, float y, float pressure, float tilt, float orientation, int tool, int buttons, int id, float verticalScroll, float horizontalScroll) {
        try {
            // Bound the queue during a stalled GPU frame. Keep button/up/cancel transitions.
            if (penEvents.size() >= 4096 && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE)) return;
            penEvents.add(new JSONArray().put(action).put(x).put(y).put(pressure).put(tilt).put(orientation).put(tool).put(buttons).put(id).put(verticalScroll).put(horizontalScroll));
        } catch (JSONException error) { android.util.Log.w("Craft", "Invalid pen sample", error); }
    }
    public String drainPen(String ignored) {
        JSONArray batch = new JSONArray();
        synchronized (penEvents) { while (!penEvents.isEmpty()) batch.put(penEvents.removeFirst()); }
        return batch.toString();
    }

    public String safeInsets(String ignored) {
        // The SurfaceView itself is inset, so native window coordinates already exclude the bars.
        return "[0,0,0,0]";
    }
    @Override public WindowInsetsCompat onApplyWindowInsets(View view, WindowInsetsCompat insets) {
        WindowInsetsCompat result = super.onApplyWindowInsets(view, insets);
        Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
        if (mSurfaceView != null && mSurfaceView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams layout = (ViewGroup.MarginLayoutParams)mSurfaceView.getLayoutParams();
            if (layout.leftMargin != safe.left || layout.topMargin != safe.top || layout.rightMargin != safe.right || layout.bottomMargin != safe.bottom) {
                layout.setMargins(safe.left, safe.top, safe.right, safe.bottom);
                mSurfaceView.setLayoutParams(layout);
            }
        }
        return result;
    }

    @Override protected void onPause() {
        cancelFingers();
        palms.reset();
        synchronized (penEvents) { addPen(MotionEvent.ACTION_CANCEL, 0, 0, 0, 0, 0, MotionEvent.TOOL_TYPE_STYLUS, 0, 0); }
        wake();
        super.onPause();
    }

    @Override protected void onDestroy() {
        super.onDestroy(); // GameActivity waits for Rust's event loop and on_exit cleanup.
        files.shutdown();
        // winit permits one event loop per process. A subsequent launcher start must get a
        // fresh process, just as reopening the original desktop application does.
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override public void onCreate(Bundle state) {
        try {
            File workspace = WorkspaceProvider.workspace(this);
            if (!workspace.isDirectory() && !workspace.mkdirs() && !workspace.isDirectory()) throw new IOException("Cannot create workspace");
            Os.setenv("HOME", workspace.getAbsolutePath(), true);
            Os.setenv("XDG_CONFIG_HOME", new File(getFilesDir(), "config").getAbsolutePath(), true);
            Os.setenv("XDG_DATA_HOME", new File(getFilesDir(), "data").getAbsolutePath(), true);
            Os.setenv("XDG_CACHE_HOME", getCacheDir().getAbsolutePath(), true);
            Os.setenv("TMPDIR", getCacheDir().getAbsolutePath(), true);
            if (android.os.Build.HARDWARE.equals("ranchu") || android.os.Build.HARDWARE.equals("goldfish")) {
                // The AVD's SwiftShader Vulkan driver crashed in vkCreateDevice. GLES is stable.
                Os.setenv("WGPU_BACKEND", "gl", true);
            }
        } catch (Exception error) { android.util.Log.e("Craft", "Application directories", error); }
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(getWindow().getDecorView());
                if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
                    new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView()).hide(WindowInsetsCompat.Type.ime());
                } else { backRequested = true; wake(); }
            }
        });
        if (state != null) {
            try { String pending = state.getString("craft.dialog"); if (pending != null) request = new JSONObject(pending); }
            catch (JSONException error) { fail(error); }
        }
        acceptIntent(getIntent());
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (request != null) state.putString("craft.dialog", request.toString());
        super.onSaveInstanceState(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptIntent(intent);
    }

    private void acceptIntent(Intent intent) {
        if (intent == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> shared = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (shared != null) uris.addAll(shared);
        } else {
            Uri uri = Intent.ACTION_SEND.equals(intent.getAction()) ? intent.getParcelableExtra(Intent.EXTRA_STREAM) : intent.getData();
            if (uri != null) uris.add(uri);
        }
        if (!uris.isEmpty()) {
            files.execute(() -> {
                try {
                    ArrayList<String> paths = new ArrayList<>();
                    for (Uri uri : uris) { if ("content".equals(uri.getScheme())) paths.add(importDocument(uri).getAbsolutePath()); }
                    result(paths);
                } catch (Exception e) { fail(e); }
            });
        }
    }

    public String beginFileDialog(String json) {
        runOnUiThread(() -> {
            String dialogId = null;
            try {
                JSONObject next = new JSONObject(json);
                dialogId = next.getString("id");
                if (request != null) throw new IOException("A file picker is already open");
                request = next;
                String kind = request.getString("kind");
                JSONObject options = request.getJSONObject("dialog");
                Intent intent;
                boolean sequence = kind.equals("save") && options.optString("name").matches(".*(#{2,}|%0?[0-9]*d).*\\.(png|jpg|jpeg|tif|tiff|exr)");
                if (sequence) request.put("sequence", safeName(options.getString("name")));
                if (kind.equals("folder") || sequence) {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                } else {
                    intent = new Intent(kind.equals("save") ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, kind.equals("open_multiple"));
                    if (kind.equals("save")) intent.putExtra(Intent.EXTRA_TITLE, safeName(options.optString("name", "Untitled")));
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, PICK);
            } catch (Exception error) { request = null; fail(error, dialogId); }
        });
        return "";
    }

    public String pollFileDialog(String ignored) { String value = results.poll(); return value == null ? "" : value; }

    @Override protected void onActivityResult(int code, int status, Intent data) {
        super.onActivityResult(code, status, data);
        if (code != PICK) return;
        JSONObject pending = request;
        request = null;
        String dialogId = pending == null ? null : pending.optString("id");
        if (status != RESULT_OK || data == null || pending == null) { result(Collections.emptyList(), dialogId); return; }
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        for (Uri uri : uris) {
            try { getContentResolver().takePersistableUriPermission(uri, flags); }
            catch (SecurityException ignored) { /* Some providers only grant access for this session. */ }
        }
        files.execute(() -> {
            try {
                ArrayList<String> paths = new ArrayList<>();
                String kind = pending.getString("kind");
                for (Uri uri : uris) {
                    File local;
                    File owned = WorkspaceProvider.ownedFile(this, uri);
                    if (owned != null) {
                        local = pending.has("sequence") ? new File(owned, pending.getString("sequence")) : owned;
                    } else if (pending.has("sequence")) {
                        Uri document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
                        File directory = newDocument(displayName(document));
                        if (!directory.mkdirs()) throw new IOException("Cannot create sequence directory");
                        copyTree(uri, document, directory, 0);
                        local = new File(directory, pending.getString("sequence"));
                    } else if (kind.equals("save")) {
                        local = newDocument(displayName(uri));
                        remember(local, uri);
                    } else if (kind.equals("folder")) {
                        Uri document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
                        local = newDocument(displayName(document));
                        if (!local.mkdirs() && !local.isDirectory()) throw new IOException("Cannot create local folder");
                        copyTree(uri, document, local, 0);
                    } else { local = importDocument(uri); }
                    paths.add(local.getAbsolutePath());
                }
                result(paths, dialogId);
            } catch (Exception error) { fail(error, dialogId); }
        });
    }

    private static String safeName(String name) {
        String clean = name.replaceAll("[\\\\/\\p{Cntrl}]", "_");
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) return "Untitled";
        int dot = clean.lastIndexOf('.');
        String extension = dot > 0 && clean.length() - dot <= 17 ? clean.substring(dot) : "";
        String stem = extension.isEmpty() ? clean : clean.substring(0, dot);
        int remaining = 240 - extension.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < stem.length();) {
            int point = stem.codePointAt(offset);
            String value = new String(Character.toChars(point));
            int bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > remaining) break;
            result.append(value); remaining -= bytes; offset += Character.charCount(point);
        }
        return (result.length() == 0 ? "Untitled" : result.toString()) + extension;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        }
        return "Untitled";
    }

    private File newDocument(String name) throws IOException {
        File directory = new File(getFilesDir(), "Documents/" + UUID.randomUUID());
        if (!directory.mkdirs()) throw new IOException("Cannot create document directory");
        return new File(directory, safeName(name));
    }

    private File importDocument(Uri uri) throws IOException {
        File owned = WorkspaceProvider.ownedFile(this, uri);
        if (owned != null) return owned;
        File target = newDocument(displayName(uri));
        copyDocument(uri, target);
        return target;
    }

    private void copyDocument(Uri uri, File target) throws IOException {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try (InputStream input = getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(part)) {
            if (input == null) throw new IOException("The document provider returned no data");
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        } catch (IOException error) { part.delete(); throw error; }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        remember(target, uri);
    }

    private void copyTree(Uri tree, Uri parent, File folder, int depth) throws IOException {
        if (depth > 64) throw new IOException("Folder nesting exceeds 64 levels");
        remember(folder, parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        try (Cursor cursor = getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot list the selected folder");
            while (cursor.moveToNext()) {
                File child = new File(folder, safeName(cursor.getString(1)));
                if (child.exists()) throw new IOException("Conflicting document names in selected folder");
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    if (!child.mkdir()) throw new IOException("Cannot create subfolder");
                    copyTree(tree, uri, child, depth + 1);
                } else { copyDocument(uri, child); }
            }
        }
    }

    private void remember(File file, Uri uri) throws IOException {
        String path = file.getCanonicalPath();
        if (!getSharedPreferences("documents", MODE_PRIVATE).edit().putString(path, uri.toString()).remove("relocate:" + path).commit()) throw new IOException("Cannot persist document location");
    }

    /** Called only after Rust has atomically saved the durable local copy. */
    private Uri locationFor(File file) throws IOException { return locationFor(file, true); }
    private Uri locationFor(File file, boolean create) throws IOException {
        File root = getFilesDir().getCanonicalFile();
        file = file.getCanonicalFile();
        android.content.SharedPreferences preferences = getSharedPreferences("documents", MODE_PRIVATE);
        String location = preferences.getString(file.getPath(), null);
        if (location == null && file.toPath().startsWith(root.toPath())) {
            // Migrate mappings made with Android's /data/user/0 versus /data/data alias.
            String legacy = new File(getFilesDir(), root.toPath().relativize(file.toPath()).toString()).getAbsolutePath();
            location = preferences.getString(legacy, null);
        }
        boolean relocated = preferences.getBoolean("relocate:" + file.getPath(), false);
        if (location != null && !relocated) return Uri.parse(location);
        if (location == null && !create) return null;
        File parent = file.getParentFile();
        if (parent == null || file.equals(root)) return null;
        Uri parentUri = locationFor(parent, create);
        if (parentUri == null) return null;
        if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(getContentResolver().getType(parentUri))) throw new IOException("The selected destination is not a folder");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, DocumentsContract.getDocumentId(parentUri));
        try (Cursor cursor = getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) if (file.getName().equals(cursor.getString(1))) {
                if (!relocated) throw new IOException("A document named " + file.getName() + " already exists in the provider; select that document explicitly before replacing it");
                Uri existing = DocumentsContract.buildDocumentUriUsingTree(parentUri, cursor.getString(0));
                remember(file, existing);
                return existing;
            }
        }
        if (!create) return null;
        String mime = file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(file.getName()).toLowerCase(Locale.ROOT));
        Uri uri = DocumentsContract.createDocument(getContentResolver(), parentUri, mime == null ? "application/octet-stream" : mime, file.getName());
        if (uri == null) throw new IOException("The document provider could not create " + file.getName());
        remember(file, uri);
        return uri;
    }

    public synchronized String moveDocument(String json) {
        try {
            JSONObject operation = new JSONObject(json);
            File source = new File(operation.getString("from")).getCanonicalFile();
            File target = new File(operation.getString("to")).getCanonicalFile();
            if (WorkspaceProvider.workspace(this).getCanonicalFile().toPath().startsWith(source.toPath())) throw new IOException("The application workspace root cannot be moved; move its contents instead");
            Uri original = locationFor(source, false);
            if (original == null) {
                if (locationFor(target.getParentFile(), true) != null) throw new IOException("Cross-provider move requires copy and verified removal");
                return "";
            }
            if (DocumentsContract.isTreeUri(original) && DocumentsContract.getTreeDocumentId(original).equals(DocumentsContract.getDocumentId(original))) throw new IOException("Select the parent folder before renaming or moving the granted root");
            boolean rename = source.getParentFile().equals(target.getParentFile());
            if (!rename && !source.getName().equals(target.getName())) throw new IOException("Move and rename this document in separate operations");
            Uri oldParent = rename ? null : locationFor(source.getParentFile(), false);
            Uri newParent = rename ? null : locationFor(target.getParentFile(), true);
            if (!rename && (oldParent == null || newParent == null)) throw new IOException("The document provider cannot move to this folder; use Copy/Move import");
            Uri changed = rename ? DocumentsContract.renameDocument(getContentResolver(), original, target.getName())
                : DocumentsContract.moveDocument(getContentResolver(), original, oldParent, newParent);
            if (changed == null) throw new IOException("The provider did not complete the move");
            if (!target.getName().equals(displayName(changed))) {
                Uri restored = rename ? DocumentsContract.renameDocument(getContentResolver(), changed, source.getName())
                    : DocumentsContract.moveDocument(getContentResolver(), changed, newParent, oldParent);
                if (restored != null) remember(source, restored);
                throw new IOException("The provider could not preserve the requested filename; the operation was reversed");
            }
            android.content.SharedPreferences preferences = getSharedPreferences("documents", MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = preferences.edit();
            for (String key : preferences.getAll().keySet()) {
                if (!key.startsWith("/")) continue;
                String canonical = new File(key).getCanonicalPath();
                if (canonical.equals(source.getPath()) || canonical.startsWith(source.getPath() + "/")) {
                    editor.remove(key).remove("relocate:" + canonical);
                    if (!canonical.equals(source.getPath())) {
                        String moved = target.getPath() + canonical.substring(source.getPath().length());
                        editor.putString(moved, preferences.getString(key, "")).putBoolean("relocate:" + moved, true);
                    }
                }
            }
            // Descendant URIs are resolved by name lazily from the new folder URI.
            if (!editor.putString(target.getPath(), changed.toString()).remove("relocate:" + target.getPath()).commit()) showError("The document moved, but Android could not persist its location. Keep the local copy and reopen the folder after freeing storage.");
            return "";
        } catch (Exception error) { return error.getMessage() == null ? error.toString() : error.getMessage(); }
    }

    public synchronized String removeDocument(String path) {
        try {
            File file = new File(path).getCanonicalFile();
            if (file.toPath().startsWith(WorkspaceProvider.workspace(this).getCanonicalFile().toPath())) {
                WorkspaceProvider.changed(this, file);
                return ""; // Native workspace deletion is performed by the original filesystem code.
            }
            Uri uri = locationFor(file, false);
            if (uri == null) return "";
            if (!DocumentsContract.deleteDocument(getContentResolver(), uri)) throw new IOException("The provider refused to remove the original");
            android.content.SharedPreferences preferences = getSharedPreferences("documents", MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = preferences.edit();
            for (String key : preferences.getAll().keySet()) if (key.startsWith("/") && new File(key).getCanonicalFile().equals(file)) editor.remove(key);
            editor.remove("relocate:" + file.getPath()).apply();
            return "";
        } catch (Exception error) { return error.getMessage() == null ? error.toString() : error.getMessage(); }
    }

    public synchronized String publishFile(String path) {
        try {
            File file = new File(path).getCanonicalFile();
            if (file.toPath().startsWith(WorkspaceProvider.workspace(this).getCanonicalFile().toPath())) {
                WorkspaceProvider.changed(this, file);
                return "";
            }
            if (!file.toPath().startsWith(getFilesDir().getCanonicalFile().toPath())) return "";
            Uri location = locationFor(file);
            if (location == null) {
                if (file.toPath().startsWith(new File(getFilesDir(), "Documents").getCanonicalFile().toPath())) throw new IOException("The external document location is missing; the local copy is preserved");
                return "";
            }
            if (file.isDirectory()) return "";
            try (InputStream input = new FileInputStream(file); OutputStream output = getContentResolver().openOutputStream(location, "wt")) {
                if (output == null) throw new IOException("The document provider is not writable");
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            }
            return "";
        } catch (Exception error) { return "External save failed; the local copy is preserved: " + error.getMessage(); }
    }

    public String showError(String message) {
        runOnUiThread(() -> { if (!isFinishing()) new AlertDialog.Builder(this).setTitle(getString(R.string.app_name)).setMessage(message).setPositiveButton(android.R.string.ok, null).show(); });
        return "";
    }
    public String printPdf(String json) {
        runOnUiThread(() -> {
            try {
                JSONObject job = new JSONObject(json);
                File source = new File(job.getString("path")).getCanonicalFile();
                File jobs = new File(getFilesDir(), "PrintJobs").getCanonicalFile();
                if (!source.toPath().startsWith(jobs.toPath())) throw new IOException("Invalid print job location");
                int count;
                try (PdfRenderer pdf = new PdfRenderer(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY))) { count = pdf.getPageCount(); }
                String title = job.optString("title", "Document");
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(title).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(count).build();
                PrintAttributes attributes = new PrintAttributes.Builder()
                    .setColorMode(job.optBoolean("grayscale") ? PrintAttributes.COLOR_MODE_MONOCHROME : PrintAttributes.COLOR_MODE_COLOR)
                    .setDuplexMode(job.optInt("duplex") == 1 ? PrintAttributes.DUPLEX_MODE_LONG_EDGE : job.optInt("duplex") == 2 ? PrintAttributes.DUPLEX_MODE_SHORT_EDGE : PrintAttributes.DUPLEX_MODE_NONE).build();
                getSystemService(PrintManager.class).print(title, new PrintDocumentAdapter() {
                    @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes, CancellationSignal cancel, LayoutResultCallback callback, Bundle extras) {
                        if (cancel.isCanceled()) callback.onLayoutCancelled();
                        else callback.onLayoutFinished(info, false);
                    }
                    @Override public void onWrite(PageRange[] requested, ParcelFileDescriptor destination, CancellationSignal cancel, WriteResultCallback callback) {
                        files.execute(() -> {
                            try (InputStream input = new FileInputStream(source); OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(destination)) {
                                byte[] buffer = new byte[65536]; int length;
                                while ((length = input.read(buffer)) != -1) {
                                    if (cancel.isCanceled()) { callback.onWriteCancelled(); return; }
                                    output.write(buffer, 0, length);
                                }
                                output.flush();
                                // Supply the original vector PDF and accurately report its pages;
                                // Android's spooler applies the user's selected print ranges.
                                callback.onWriteFinished(new PageRange[]{new PageRange(0, count - 1)});
                            } catch (IOException error) { callback.onWriteFailed(error.getMessage()); }
                        });
                    }
                    @Override public void onFinish() { source.delete(); }
                }, attributes);
            } catch (Exception error) { showError("Could not open Android printing: " + error.getMessage()); }
        });
        return "";
    }
    public String openExternal(String location) {
        runOnUiThread(() -> {
            try {
                JSONObject options = location.startsWith("{") ? new JSONObject(location) : null;
                String target = options == null ? location : options.getString("location");
                boolean edit = options != null && options.optBoolean("edit");
                Uri uri;
                String mime = null;
                if (target.startsWith("/")) {
                    File file = new File(target).getCanonicalFile();
                    if (file.isDirectory()) {
                        String mapped = getSharedPreferences("documents", MODE_PRIVATE).getString(file.getPath(), null);
                        if (mapped == null) throw new IOException("Open this workspace through the application's File menu");
                        uri = Uri.parse(mapped);
                        mime = DocumentsContract.Document.MIME_TYPE_DIR;
                    } else {
                        uri = WorkspaceProvider.uriForFile(this, file);
                        if (uri == null) uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
                        mime = getContentResolver().getType(uri);
                    }
                } else { uri = Uri.parse(target); }
                Intent intent = new Intent(edit ? Intent.ACTION_EDIT : Intent.ACTION_VIEW);
                if (mime == null) intent.setData(uri); else intent.setDataAndType(uri, mime);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (edit) intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (options != null && !options.optString("package").isEmpty()) intent.setPackage(options.getString("package"));
                startActivity(intent);
            }
            catch (Exception error) { showError(error.getMessage()); }
        });
        return "";
    }
    public String revealExternal(String path) {
        runOnUiThread(() -> {
            try {
                String uri = getSharedPreferences("documents", MODE_PRIVATE).getString(new File(path).getCanonicalPath(), null);
                Uri owned = WorkspaceProvider.uriForFile(this, new File(path));
                if (owned != null) uri = owned.toString();
                Intent browser = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                if (uri != null) browser.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(uri));
                startActivity(browser);
            } catch (Exception error) { showError(error.getMessage()); }
        });
        return "";
    }
    public String getClipboardText(String ignored) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard.getPrimaryClip();
        return clip == null || clip.getItemCount() == 0 ? "" : clip.getItemAt(0).coerceToText(this).toString();
    }
    public String setClipboardText(String text) { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Craft", text)); return ""; }
    public String getClipboardImage(String ignored) {
        ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
        try { if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getUri() != null) return importDocument(clip.getItemAt(0).getUri()).getAbsolutePath(); }
        catch (IOException error) { showError(error.getMessage()); }
        return "";
    }
    public String microphonePermission(String ignored) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) return "granted";
        runOnUiThread(() -> requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 121));
        return "Microphone permission is required. Grant access, then start recording again.";
    }
    public String setClipboardImage(String path) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", new File(path));
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newUri(getContentResolver(), "Craft image", uri));
        return "";
    }
    private void result(List<String> paths) {
        result(paths, null);
    }
    private void result(List<String> paths, String id) {
        try { results.add(new JSONObject().put("source", id == null ? "intent" : "dialog").put("id", id).put("paths", new JSONArray(paths)).toString()); wake(); }
        catch (JSONException error) { fail(error, id); }
    }
    private void fail(Exception error) {
        fail(error, null);
    }
    private void fail(Exception error, String id) {
        android.util.Log.e("Craft", "Document operation failed", error);
        try { results.add(new JSONObject().put("source", id == null ? "intent" : "dialog").put("id", id).put("error", error.getMessage() == null ? error.toString() : error.getMessage()).toString()); wake(); }
        catch (JSONException ignored) { showError(error.toString()); }
    }
}
