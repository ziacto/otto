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

    public static final class Result {
        public final int diagFilesDeleted;
        public final int crashFilesDeleted;
        public final boolean cacheCleared;
        public final int trendPointsCleared;
        public final int sessionEntriesCleared;
        Result(int d, int c, boolean cc, int tp, int se) {
            this.diagFilesDeleted = d;
            this.crashFilesDeleted = c;
            this.cacheCleared = cc;
            this.trendPointsCleared = tp;
            this.sessionEntriesCleared = se;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder("Cleared:\n");
            sb.append("  • ").append(diagFilesDeleted).append(" diag log file(s)\n");
            sb.append("  • ").append(crashFilesDeleted).append(" crash report(s)\n");
            sb.append("  • ").append(sessionEntriesCleared).append(" in-memory log entries\n");
            sb.append("  • Last-values cache: ").append(cacheCleared ? "yes" : "no").append('\n');
            sb.append("  • ").append(trendPointsCleared).append(" trend samples");
            return sb.toString();
        }
    }

    /**
     * Deletes: diag log + rotations, obd-crash-*.log files, LastValuesCache
     * prefs, in-memory ObdLogger ring, DataLogger series, TrendEngine samples.
     * Does NOT touch the Room DB or the app's SharedPreferences that hold the
     * saved OBD MAC (BluetoothHelper's "obd_prefs").
     */
    public static Result clearAll(Context ctx) {
        int sessionEntries = ObdLogger.get().snapshot().size();
        ObdLogger.get().clear();
        int diag = ObdLogger.get().deleteFiles();

        int crash = 0;
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        if (dir != null && dir.exists()) {
            File[] children = dir.listFiles((d, n) -> n.startsWith(CrashHandler.CRASH_PREFIX));
            if (children != null) {
                for (File c : children) {
                    if (c.delete()) crash++;
                }
            }
        }

        LastValuesCache.get(ctx).clear();

        int trendCleared = 0;
        try {
            trendCleared = TrendEngine.clearAll(ctx);
        } catch (Throwable ignored) {
            // TrendEngine may not expose clearAll on some builds; ignore.
        }

        try {
            DataLogger.getInstance().clear();
        } catch (Throwable ignored) {}

        // Re-open the session so subsequent activity has fresh context.
        ObdLogger.get().log(ObdLogger.Level.INFO,
                "Cleared app data: " + diag + " diag, " + crash + " crash, "
                        + trendCleared + " trend, " + sessionEntries + " session entries");

        return new Result(diag, crash, true, trendCleared, sessionEntries);
    }
}
