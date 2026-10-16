package studio.eidolon.smoke;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

/** Framework-only smoke test: no shared test libraries or extra keep rules in the release app. */
public final class ReleaseSmokeInstrumentation extends Instrumentation {
    private Activity activity;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle status = new Bundle();
        status.putString("id", "InstrumentationTestRunner");
        status.putString("class", getClass().getName());
        status.putString("test", "optimizedPreviewSurvivesRecreationAndReopen");
        status.putInt("numtests", 1);
        status.putInt("current", 1);
        sendStatus(1, status);
        Bundle result = new Bundle();
        try {
            if (getTargetContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                throw new AssertionError("Grant CAMERA with adb shell pm grant before running the release smoke test");
            }
            long started = SystemClock.elapsedRealtime();
            open();
            awaitFrames();
            report("startup_to_moving_preview", started);
            ActivityMonitor monitor = addMonitor("studio.eidolon.camera.MainActivity", null, false);
            started = SystemClock.elapsedRealtime();
            runOnMainSync(() -> activity.recreate());
            Activity recreated = waitForMonitorWithTimeout(monitor, 20_000);
            removeMonitor(monitor);
            if (recreated == null) throw new AssertionError("Activity recreation timed out");
            activity = recreated;
            awaitFrames();
            report("recreation_to_moving_preview", started);
            close();
            started = SystemClock.elapsedRealtime();
            open();
            awaitFrames();
            report("reopen_to_moving_preview", started);
            close();
            sendStatus(0, status);
            result.putString("stream", "\nOK (1 test)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            if (activity != null) close();
            status.putString("stack", android.util.Log.getStackTraceString(failure));
            sendStatus(-2, status);
            result.putString("stream", "\nFAILURES!!!\nTests run: 1, Failures: 1\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    // Device diagnostics, not assertions or real-phone performance claims.
    private void report(String stage, long started) {
        long elapsed = SystemClock.elapsedRealtime() - started;
        Debug.MemoryInfo memory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memory);
        android.util.Log.i("EidolonPerformance", stage + "_ms=" + elapsed
            + ", pss_kib=" + memory.getTotalPss()
            + ", native_allocated_bytes=" + Debug.getNativeHeapAllocatedSize());
    }

    private void open() {
        Intent intent = new Intent(Intent.ACTION_MAIN)
            .setClassName(getTargetContext().getPackageName(), "studio.eidolon.camera.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = startActivitySync(intent);
        waitForIdleSync();
    }

    private void close() {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
        activity = null;
    }

    private static TextureView texture(View view) {
        if (view instanceof TextureView) return (TextureView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextureView found = texture(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void awaitFrames() {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        long first = 0;
        long[] latest = {0};
        while (SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync(() -> {
                TextureView view = texture(activity.getWindow().getDecorView());
                latest[0] = view == null || view.getSurfaceTexture() == null ? 0 : view.getSurfaceTexture().getTimestamp();
            });
            if (first > 0 && latest[0] > first) return;
            if (latest[0] > 0) first = latest[0];
            SystemClock.sleep(100);
        }
        throw new AssertionError("Release preview did not deliver changing frames");
    }
}
