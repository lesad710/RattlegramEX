package com.aicodix.rattlegram;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashLogger implements Thread.UncaughtExceptionHandler {
    private static final String PREFS_CRASH = "last_crash";

    private final Thread.UncaughtExceptionHandler previous;
    private final Context context;

    public static void install(Context context) {
        if (Thread.getDefaultUncaughtExceptionHandler() instanceof CrashLogger)
            return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashLogger(
            Thread.getDefaultUncaughtExceptionHandler(), context.getApplicationContext()));
    }

    private CrashLogger(Thread.UncaughtExceptionHandler previous, Context context) {
        this.previous = previous;
        this.context = context;
    }

    @SuppressLint("ApplySharedPref") // Must be persisted before Android terminates the process.
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String trace = "=== Crash @ " + ts + " in " + t.getName() + " ===\n"
                + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\n" + sw + "\n";
            SharedPreferences.Editor edit = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit();
            edit.putString(PREFS_CRASH, trace);
            // The process is about to exit; asynchronous apply can lose the trace.
            edit.commit();
        } catch (Exception ignored) {
        }
        if (previous != null)
            previous.uncaughtException(t, e);
    }

    public static String takeLastCrash(Context context) {
        SharedPreferences pref = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String crash = pref.getString(PREFS_CRASH, null);
        if (crash != null) {
            pref.edit().remove(PREFS_CRASH).apply();
        }
        return crash;
    }
}