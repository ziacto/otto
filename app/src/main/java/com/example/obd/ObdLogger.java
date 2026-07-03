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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Thread-safe ring-buffer log for OBD events.
 * Only one-shot commands (DTC/VIN/clear), init, connect/disconnect, and errors are logged —
 * polled sensor reads are skipped to avoid drowning the buffer.
 *
 * If {@link #initFileSink(Context)} has been called, every entry is also appended to
 * obd-diag.log in the app's external files dir so it survives crashes and can be
 * retrieved via `adb pull`.
 *
 * The in-memory ring is updated synchronously (so CrashHandler can render the last
 * entries even mid-crash), but file appends are handed to a single background writer
 * thread — callers on the main thread never touch the disk, and a slow write can't
 * stall every logging thread behind the class lock.
 */
public final class ObdLogger {

    public enum Level { TX, RX, INFO, ERROR }

    private static final String TAG = "ObdLogger";
    private static final int MAX_ENTRIES = 250;
    private static final long MAX_FILE_BYTES = 500_000L; // ~500 KB; rotates when exceeded
    // Bounded so a wedged disk can't grow the queue without limit; overflow drops
    // to logcat only — the logger must never block or throw on the caller.
    private static final int MAX_QUEUED_WRITES = 1_000;
    public static final String DIAG_FILENAME = "obd-diag.log";

    private static final ObdLogger INSTANCE = new ObdLogger();
    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FILE_TS_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final BlockingQueue<Entry> writeQueue = new LinkedBlockingQueue<>(MAX_QUEUED_WRITES);
    // Guards every touch of the on-disk files (append, rotate, delete) so
    // deleteFiles() can't race the writer thread mid-append.
    private final Object fileLock = new Object();
    private volatile File diagFile;
    private Thread writerThread; // guarded by "this"; created once in initFileSink

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
        if (writerThread == null) {
            writerThread = new Thread(this::writerLoop, "ObdLogWriter");
            writerThread.setDaemon(true);
            writerThread.setPriority(Thread.MIN_PRIORITY);
            writerThread.start();
        }
        log(Level.INFO, "---- log session start (pid=" + android.os.Process.myPid() + ") ----");
    }

    /** Absolute path of the persistent diag log, or null if file sink not initialized. */
    public File getDiagFile() { return diagFile; }

    public void log(Level lvl, String msg) {
        Entry e = new Entry(System.currentTimeMillis(), lvl, msg == null ? "" : msg);
        synchronized (this) {
            if (entries.size() >= MAX_ENTRIES) entries.pollFirst();
            entries.addLast(e);
        }
        if (diagFile != null && !writeQueue.offer(e)) {
            // Queue full — disk is wedged. Drop the file copy (ring still has it).
            Log.w(TAG, "diag write queue full, dropped: " + e.text);
        }
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
    public int deleteFiles() {
        int removed = 0;
        File f = diagFile;
        if (f == null) return 0;
        File parent = f.getParentFile();
        if (parent == null) return 0;
        synchronized (fileLock) {
            // Drop anything still queued — otherwise the writer would resurrect
            // pre-clear entries into the freshly deleted file moments later.
            writeQueue.clear();
            String[] targets = { DIAG_FILENAME, DIAG_FILENAME + ".1", DIAG_FILENAME + ".2" };
            for (String name : targets) {
                File c = new File(parent, name);
                if (c.exists() && c.delete()) removed++;
            }
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

    /**
     * Background writer: blocks on the queue, then drains whatever else is pending
     * so bursts share one open-write-close cycle instead of one per entry.
     */
    private void writerLoop() {
        List<Entry> batch = new ArrayList<>();
        while (true) {
            batch.clear();
            try {
                batch.add(writeQueue.take());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            writeQueue.drainTo(batch);
            synchronized (fileLock) {
                appendToFile(batch);
            }
        }
    }

    private void appendToFile(List<Entry> batch) {
        File f = diagFile;
        if (f == null || batch.isEmpty()) return;
        try {
            // Two-tier rotation: .1 (previous session) and .2 (older). When the active
            // file exceeds MAX_FILE_BYTES, drop .2, promote .1 -> .2, current -> .1.
            // This keeps at most 1.5 MB on disk while preserving one full backlog.
            if (f.length() > MAX_FILE_BYTES) {
                File rotated2 = new File(f.getParentFile(), DIAG_FILENAME + ".2");
                if (rotated2.exists()) rotated2.delete();
                File rotated1 = new File(f.getParentFile(), DIAG_FILENAME + ".1");
                if (rotated1.exists()) rotated1.renameTo(rotated2);
                if (!f.renameTo(rotated1)) {
                    // renameTo can fail (e.g. open handles, odd vendor FS). Without a
                    // fallback the active file grows unbounded — truncate it instead so
                    // the size cap always holds, even at the cost of losing the backlog.
                    Log.w(TAG, "diag rotate failed, truncating " + f.getName());
                    try (FileWriter truncate = new FileWriter(f, false)) {
                        // opening in overwrite mode truncates; nothing to write
                    }
                }
            }
            try (BufferedWriter w = new BufferedWriter(new FileWriter(f, true))) {
                for (Entry e : batch) {
                    w.write(FILE_TS_FMT.format(new Date(e.timestamp)));
                    w.write("  [");
                    w.write(e.level.name());
                    w.write("]  ");
                    w.write(e.text);
                    w.newLine();
                }
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
