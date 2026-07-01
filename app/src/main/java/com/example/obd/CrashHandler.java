package com.example.obd;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Catches uncaught exceptions, dumps a full crash report (stacktrace + recent
 * ObdLogger entries + device info) to a per-crash file in the app's external
 * files dir, then re-throws to the previous handler so Android still shows the
 * crash dialog and reports to Play Console / Crashlytics if configured.
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    public static final String CRASH_PREFIX = "obd-crash-";

    private final File dir;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashHandler(File dir, Thread.UncaughtExceptionHandler previous) {
        this.dir = dir;
        this.previous = previous;
    }

    public static void install(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(dir, prev));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            writeCrashFile(t, e);
        } catch (Throwable writerFailure) {
            // Last-ditch: at least get the original cause into logcat.
            Log.e(TAG, "failed to write crash file", writerFailure);
        }
        if (previous != null) {
            previous.uncaughtException(t, e);
        } else {
            // No system default — terminate ourselves so the process doesn't hang.
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        }
    }

    private void writeCrashFile(Thread t, Throwable e) {
        File f = new File(dir, CRASH_PREFIX + System.currentTimeMillis() + ".log");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
            w.write("---- OBD2 app crash report ----\n");
            w.write("time:    " + new java.util.Date() + "\n");
            w.write("thread:  " + t.getName() + " (id=" + t.getId() + ")\n");
            w.write("device:  " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
            w.write("android: " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")\n");
            w.write("\n---- stacktrace ----\n");
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            w.write(sw.toString());
            w.write("\n---- recent ObdLogger entries ----\n");
            w.write(ObdLogger.get().render());
        } catch (Exception ioe) {
            Log.e(TAG, "crash file write failed: " + ioe.getMessage());
        }
    }
}
