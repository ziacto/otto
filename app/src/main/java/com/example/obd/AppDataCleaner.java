package com.example.obd;

import android.content.Context;

import java.io.File;

/**
 * One-stop wipe for cached readings, diagnostic logs and crash reports. Used by
 * the drawer's "Clear Data" action. Vehicle profiles in Room DB are left
 * intact — losing the saved VIN + decoded make/model on every clear would
 * force the user to re-scan every time. If they want that too, they can wipe
 * the app storage from system settings.
 */
public final class AppDataCleaner {

    private AppDataCleaner() {}

    /** Sentinel for counts that are still being computed on the background thread. */
    private static final int PENDING = -1;

    public static final class Result {
        public final int diagFilesDeleted;
        public final int crashFilesDeleted;
        public final boolean cacheCleared;
        public final int trendPointsCleared;
        public final int sessionEntriesCleared;
        public final boolean eventLogDeleted;
        Result(int d, int c, boolean cc, int tp, int se, boolean ev) {
            this.diagFilesDeleted = d;
            this.crashFilesDeleted = c;
            this.cacheCleared = cc;
            this.trendPointsCleared = tp;
            this.sessionEntriesCleared = se;
            this.eventLogDeleted = ev;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder("Cleared:\n");
            sb.append("  • ").append(diagFilesDeleted).append(" diag log file(s)\n");
            sb.append("  • ").append(crashFilesDeleted).append(" crash report(s)\n");
            sb.append("  • DTC event log: ").append(eventLogDeleted ? "yes" : "none").append('\n');
            sb.append("  • ").append(sessionEntriesCleared).append(" in-memory log entries\n");
            sb.append("  • Last-values cache: ").append(cacheCleared ? "yes" : "no").append('\n');
            if (trendPointsCleared == PENDING) {
                // Trend samples live in Room — the wipe runs in the background, so
                // the exact row count isn't known when this dialog appears.
                sb.append("  • Trend samples (clearing in background)");
            } else {
                sb.append("  • ").append(trendPointsCleared).append(" trend samples");
            }
            return sb.toString();
        }
    }

    /**
     * Deletes: diag log + rotations, obd-crash-*.log files, the obd-events.log
     * DTC diary (VIN + GPS — the most sensitive file the app writes),
     * LastValuesCache prefs, in-memory ObdLogger ring, DataLogger series,
     * TrendEngine samples. Does NOT touch the rest of the Room DB or the app's
     * SharedPreferences that hold the saved OBD MAC (BluetoothHelper's "obd_prefs").
     *
     * Called from a dialog click on the main thread, so only the cheap in-memory
     * work and a handful of stat() pre-counts happen here — the actual file
     * deletes and the Room wipe run on {@link WorkDispatcher}. The returned
     * counts come from the pre-scan; they match reality unless a delete fails
     * mid-flight, which is acceptable for a summary dialog.
     */
    public static Result clearAll(Context ctx) {
        Context app = ctx.getApplicationContext();

        int sessionEntries = ObdLogger.get().snapshot().size();
        ObdLogger.get().clear();

        // Pre-count what the background pass will remove, so the caller's summary
        // dialog can show real numbers without waiting on disk I/O.
        int diag = countDiagFiles();
        int crash = 0;
        boolean events = false;
        File dir = app.getExternalFilesDir(null);
        if (dir == null) dir = app.getFilesDir();
        if (dir != null && dir.exists()) {
            File[] children = dir.listFiles((d, n) -> n.startsWith(CrashHandler.CRASH_PREFIX));
            if (children != null) crash = children.length;
            events = new File(dir, EventLogger.EVENTS_FILENAME).exists();
        }
        final File filesDir = dir;
        final int crashExpected = crash;

        LastValuesCache.get(app).clear();

        try {
            DataLogger.getInstance().clear();
        } catch (Throwable ignored) {}

        WorkDispatcher.io(() -> {
            int diagDeleted = ObdLogger.get().deleteFiles();

            int crashDeleted = 0;
            boolean eventsDeleted = false;
            if (filesDir != null && filesDir.exists()) {
                File[] children = filesDir.listFiles(
                        (d, n) -> n.startsWith(CrashHandler.CRASH_PREFIX));
                if (children != null) {
                    for (File c : children) {
                        if (c.delete()) crashDeleted++;
                    }
                }
                File eventLog = new File(filesDir, EventLogger.EVENTS_FILENAME);
                if (eventLog.exists()) eventsDeleted = eventLog.delete();
            }

            int trendCleared = 0;
            try {
                trendCleared = TrendEngine.clearAll(app);
            } catch (Throwable ignored) {
                // TrendEngine may not expose clearAll on some builds; ignore.
            }

            // Re-open the session so subsequent activity has fresh context.
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "Cleared app data: " + diagDeleted + " diag, " + crashDeleted + " crash, "
                            + (eventsDeleted ? "events log, " : "")
                            + trendCleared + " trend, " + sessionEntries + " session entries");
        });

        return new Result(diag, crashExpected, true, PENDING, sessionEntries, events);
    }

    /** Stat-only pre-count of the diag log + its rotations. */
    private static int countDiagFiles() {
        File f = ObdLogger.get().getDiagFile();
        if (f == null) return 0;
        File parent = f.getParentFile();
        if (parent == null) return 0;
        int n = 0;
        String[] targets = {
                ObdLogger.DIAG_FILENAME,
                ObdLogger.DIAG_FILENAME + ".1",
                ObdLogger.DIAG_FILENAME + ".2"
        };
        for (String name : targets) {
            if (new File(parent, name).exists()) n++;
        }
        return n;
    }
}
