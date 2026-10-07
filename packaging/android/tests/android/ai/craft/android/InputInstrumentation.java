package ai.craft.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import org.json.JSONArray;

/** Runs the real Activity input adapter on an emulator; hardware latency still needs a pen. */
public final class InputInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { this.arguments = arguments; super.onCreate(arguments); start(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static MotionEvent pen(int action, int tool, float pressure, float tilt, float azimuth, int buttons) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 7; properties.toolType = tool;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = 400; coordinates.y = 400; coordinates.pressure = pressure;
        coordinates.setAxisValue(MotionEvent.AXIS_TILT, tilt);
        coordinates.setAxisValue(MotionEvent.AXIS_ORIENTATION, azimuth);
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, 1, new MotionEvent.PointerProperties[]{properties}, new MotionEvent.PointerCoords[]{coordinates},
            0, buttons, 1, 1, 1, 0, InputDevice.SOURCE_STYLUS, 0);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent launch = new Intent(getTargetContext(), CraftActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            CraftActivity activity = (CraftActivity) startActivitySync(launch);
            if (arguments != null && arguments.containsKey("publish")) {
                java.io.File file = new java.io.File(arguments.getString("publish"));
                check(file.getCanonicalPath().startsWith(activity.getFilesDir().getCanonicalPath()+"/Documents/"), "Only test documents can be published");
                String message = activity.publishFile(file.getPath());
                result.putString("stream", "filesDir=" + activity.getFilesDir() + "; canonicalRoot=" + activity.getFilesDir().getCanonicalPath() + "; canonicalFile=" + file.getCanonicalPath() + "; result=" + message + "\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            Throwable[] failure = new Throwable[1];
            runOnMainSync(() -> {
                try {
                    Field field = CraftActivity.class.getDeclaredField("penEvents");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked") ArrayDeque<JSONArray> queue = (ArrayDeque<JSONArray>)field.get(activity);
                    Field policy = CraftActivity.class.getDeclaredField("palms");
                    policy.setAccessible(true);
                    PalmRejection palms = (PalmRejection)policy.get(activity);
                    java.io.File document = new java.io.File(activity.getFilesDir(), "Documents/interop-test/document.pcraft");
                    java.lang.reflect.Method remember = CraftActivity.class.getDeclaredMethod("remember", java.io.File.class, android.net.Uri.class);
                    java.lang.reflect.Method locate = CraftActivity.class.getDeclaredMethod("locationFor", java.io.File.class);
                    remember.setAccessible(true); locate.setAccessible(true);
                    android.net.Uri expected = android.net.Uri.parse("content://craft-test/document");
                    remember.invoke(activity, document, expected);
                    check(expected.equals(locate.invoke(activity, document.getCanonicalFile())), "Canonical Android aliases lost the document URI");
                    activity.getSharedPreferences("documents", 0).edit().remove(document.getCanonicalPath()).commit();
                    java.lang.reflect.Method safeName = CraftActivity.class.getDeclaredMethod("safeName", String.class);
                    safeName.setAccessible(true);
                    String longName = (String)safeName.invoke(null, "写真".repeat(200) + ".pcraft");
                    check(longName.endsWith(".pcraft") && longName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 240, "Unicode filename lost its format or exceeded the filesystem byte limit");
                    check(!((String)safeName.invoke(null, "../bad/name")).contains("/"), "Unsafe document name escaped its directory");
                    synchronized (queue) {
                        queue.clear();
                        try (Events events = new Events()) {
                            MotionEvent hover = events.add(pen(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, 0, 0));
                            check(activity.dispatchGenericMotionEvent(hover), "Pen hover must be consumed");
                            check(palms.finger(MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis()), "Hover must reject the palm");
                            MotionEvent batchedHover = events.add(pen(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.TOOL_TYPE_STYLUS, 0, .3f, .8f, 0));
                            batchedHover.addBatch(SystemClock.uptimeMillis()+16, 410, 410, 0, 0, 0);
                            check(batchedHover.getHistorySize() > 0, "Hover regression must exercise batched history");
                            check(activity.dispatchGenericMotionEvent(batchedHover), "Batched hover must be consumed");
                            for (JSONArray point : queue) {
                                int action = point.getInt(0);
                                check(action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE,
                                    "Hover history became a contact event: " + action);
                            }
                            MotionEvent down = events.add(pen(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_ERASER, .35f, .5f, 1.2f, MotionEvent.BUTTON_STYLUS_PRIMARY));
                            check(activity.dispatchTouchEvent(down), "Pen down must be consumed exactly once");
                            JSONArray sample = queue.getLast();
                            check(Math.abs(sample.getDouble(3) - .35) < .001, "Pressure lost");
                            check(Math.abs(sample.getDouble(4) - .5) < .001, "Tilt lost");
                            check(Math.abs(sample.getDouble(5) - 1.2) < .001, "Orientation lost");
                            check(sample.getInt(6) == MotionEvent.TOOL_TYPE_ERASER, "Eraser identity lost");
                            check(sample.getInt(7) == MotionEvent.BUTTON_STYLUS_PRIMARY, "Side button lost");
                            check(palms.finger(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis()+10000), "Stationary stylus must reject palms");
                            activity.dispatchTouchEvent(events.add(pen(MotionEvent.ACTION_CANCEL, MotionEvent.TOOL_TYPE_ERASER, 0, 0, 0, 0)));
                            palms.reset();
                            check(!palms.finger(MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis()+10001), "Fresh finger input must recover");
                            queue.clear();
                        }
                    }
                } catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) throw new AssertionError("Android input check failed", failure[0]);
            result.putString("stream", "Batched hover, pressure, tilt, orientation, eraser, pen button and palm rejection passed.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAILED: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static final class Events implements AutoCloseable {
        private final java.util.ArrayList<MotionEvent> events = new java.util.ArrayList<>();
        MotionEvent add(MotionEvent event) { events.add(event); return event; }
        public void close() { for (MotionEvent event : events) event.recycle(); }
    }
}
