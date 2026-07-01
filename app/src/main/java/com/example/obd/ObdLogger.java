package com.example.obd;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Thread-safe ring-buffer log for OBD events.
 * Only one-shot commands (DTC/VIN/clear), init, connect/disconnect, and errors are logged —
 * polled sensor reads are skipped to avoid drowning the buffer.
 *
 * If {@link #initFileSink(Context)} has been called, every entry is also appended to
 * obd-diag.log in the app's external files dir so it survives crashes and can be
 * retrieved via `adb pull`.
 */
public final class ObdLogger {

    public enum Level { TX, RX, INFO, ERROR }

    private static final String TAG = "ObdLogger";
    private static final int MAX_ENTRIES = 250;
    private static final long MAX_FILE_BYTES = 500_000L; // ~500 KB; rotates when exceeded
    public static final String DIAG_FILENAME = "obd-diag.log";

    private static final ObdLogger INSTANCE = new ObdLogger();
    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FILE_TS_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private final Deque<Entry> entries = new ArrayDeque<>();
    private volatile File diagFile;

    private ObdLogger() {}

    public static ObdLogger get() { return INSTANCE; }

    /**
     * Enable file-backed logging. Safe to call multiple times; subsequent calls
     * are no-ops. Should be called once from Application.onCreate.
     */
    public synchronized void initFileSink(Context ctx) {
        if (diagFile != null) return;
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        diagFile = new File(dir, DIAG_FILENAME);
        log(Level.INFO, "---- log session start (pid=" + android.os.Process.myPid() + ") ----");
    }

    /** Absolute path of the persistent diag log, or null if file sink not initialized. */
    public File getDiagFile() { return diagFile; }

    public synchronized void log(Level lvl, String msg) {
        Entry e = new Entry(System.currentTimeMillis(), lvl, msg == null ? "" : msg);
        if (entries.size() >= MAX_ENTRIES) entries.pollFirst();
        entries.addLast(e);
        appendToFile(e);
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public synchronized void clear() {
        entries.clear();
        // Intentionally do NOT delete the on-disk file — that history is what makes
        // post-mortem debugging possible. User can clear it manually if needed.
    }

    /**
     * Nuke the on-disk diag log and its rotated backups. Used by the Clear Data
     * drawer action. In-memory ring stays until {@link #clear} is called
     * separately so the current session's context isn't lost mid-tap.
     */
    public synchronized int deleteFiles() {
        int removed = 0;
        File f = diagFile;
        if (f == null) return 0;
        File parent = f.getParentFile();
        if (parent == null) return 0;
        String[] targets = { DIAG_FILENAME, DIAG_FILENAME + ".1", DIAG_FILENAME + ".2" };
        for (String name : targets) {
            File c = new File(parent, name);
            if (c.exists() && c.delete()) removed++;
        }
        return removed;
    }

    public synchronized String render() {
        StringBuilder sb = new StringBuilder(entries.size() * 80);
        for (Entry e : entries) {
            sb.append(TS_FMT.format(new Date(e.timestamp)))
              .append("  [").append(e.level).append("]  ")
              .append(e.text).append('\n');
        }
        return sb.toString();
    }

    private void appendToFile(Entry e) {
        File f = diagFile;
        if (f == null) return;
        try {
            // Two-tier rotation: .1 (previous session) and .2 (older). When the active
            // file exceeds MAX_FILE_BYTES, drop .2, promote .1 -> .2, current -> .1.
            // This keeps at most 1.5 MB on disk while preserving one full backlog.
            if (f.length() > MAX_FILE_BYTES) {
                File rotated2 = new File(f.getParentFile(), DIAG_FILENAME + ".2");
                if (rotated2.exists()) rotated2.delete();
                File rotated1 = new File(f.getParentFile(), DIAG_FILENAME + ".1");
                if (rotated1.exists()) rotated1.renameTo(rotated2);
                f.renameTo(rotated1);
            }
            try (BufferedWriter w = new BufferedWriter(new FileWriter(f, true))) {
                w.write(FILE_TS_FMT.format(new Date(e.timestamp)));
                w.write("  [");
                w.write(e.level.name());
                w.write("]  ");
                w.write(e.text);
                w.newLine();
            }
        } catch (IOException ioe) {
            // Logger must never throw — fall back to logcat only.
            Log.w(TAG, "diag write failed: " + ioe.getMessage());
        }
    }

    public static final class Entry {
        public final long timestamp;
        public final Level level;
        public final String text;
        Entry(long ts, Level l, String t) { this.timestamp = ts; this.level = l; this.text = t; }
    }
}
