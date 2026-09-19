package com.aicodix.rattlegram;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device-side regression tests, including real JNI and the Android audio stack. */
public final class StabilityInstrumentation extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private Object call(SignalMonitor monitor, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = SignalMonitor.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(monitor, args);
    }

    private void drain(SignalMonitor monitor) throws Exception {
        Field field = SignalMonitor.class.getDeclaredField("recordHandler");
        field.setAccessible(true);
        CountDownLatch done = new CountDownLatch(1);
        ((Handler) field.get(monitor)).post(done::countDown);
        check(done.await(15, TimeUnit.SECONDS), "Recording worker did not respond");
    }

    private void nativeOwnership() throws Exception {
        SignalMonitor first = new SignalMonitor(getTargetContext(), null);
        SignalMonitor second = new SignalMonitor(getTargetContext(), null);
        try {
            check((Boolean)call(first, "createDecoder", new Class<?>[]{int.class}, 8000), "First decoder allocation");
            check((Boolean)call(second, "createDecoder", new Class<?>[]{int.class}, 8000), "Second decoder allocation");
            call(first, "destroyDecoder", new Class<?>[0]);
            check((Integer)call(second, "processDecoder", new Class<?>[0]) != 4,
                    "Destroying one monitor destroyed another monitor's decoder");
        } finally { first.release(); second.release(); }
    }

    private void microphoneLifecycle() throws Exception {
        SignalMonitor monitor = new SignalMonitor(getTargetContext(), null);
        try {
            monitor.configure(8000, 0, MediaRecorder.AudioSource.DEFAULT);
            monitor.start();
            monitor.start();
            drain(monitor);
            check(monitor.isListening(), "Microphone did not start");
            for (int i = 0; i < 12; i++) {
                monitor.configure(i % 2 == 0 ? 16000 : 8000, 0, MediaRecorder.AudioSource.DEFAULT);
                monitor.start();
                monitor.stop();
                monitor.start();
                drain(monitor);
                check(monitor.isListening(), "Microphone lost after reconfigure " + i);
            }
            monitor.stop();
            drain(monitor);
            check(!monitor.isListening(), "Microphone did not stop");
            monitor.configure(123, 0, MediaRecorder.AudioSource.DEFAULT);
            monitor.start();
            drain(monitor);
            check(!monitor.isListening(), "Unsupported sample rate accepted");
        } finally { monitor.release(); monitor.release(); }
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        try {
            getTargetContext().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("backgroundMonitor", false).putBoolean("positionBeacon", false).commit();
            Intent launch = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(launch);
            waitForIdleSync();
            // Keep the app visible while exercising microphone access.
            Activity visible = activity;
            runOnMainSync(() -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("monitorService");
                    field.setAccessible(true);
                    MonitorService service = (MonitorService)field.get(visible);
                    if (service != null) service.pause();
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            SystemClock.sleep(500);
            nativeOwnership();
            runOnMainSync(() -> ((MainActivity)visible).onRequestPermissionsResult(
                    1, new String[]{android.Manifest.permission.RECORD_AUDIO}, new int[0]));
            check(!visible.isFinishing(), "Cancelled permission result closed the Activity");
            microphoneLifecycle();
            check(GpsUtils.extract("[GPS:53.900000,27.566700]") != null, "Valid coordinates rejected");
            check(GpsUtils.extract("[GPS:91,0]") == null, "Invalid latitude accepted");
            check(GpsUtils.extract("[GPS:0,181]") == null, "Invalid longitude accepted");
            check(GpsUtils.extract("[GPS:" + new String(new char[400]).replace('\0', '9') + ",0]") == null,
                    "Infinite coordinates accepted");
            Activity initial = activity;
            runOnMainSync(initial::finish);
            waitForIdleSync();
            activity = null;
            for (int i = 0; i < 10; i++) {
                Activity launched = startActivitySync(launch);
                waitForIdleSync();
                SystemClock.sleep(150);
                check(!launched.isFinishing(), "Activity closed during launch " + i);
                runOnMainSync(launched::finish);
                waitForIdleSync();
            }
            result.putString("stream", "\nPASS: " + checks + " checks; native decoder isolation, audio lifecycle, GPS bounds, 10 Activity launches.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (activity != null) { Activity remaining = activity; runOnMainSync(remaining::finish); }
        }
    }
}
