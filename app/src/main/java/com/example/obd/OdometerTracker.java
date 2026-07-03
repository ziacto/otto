package com.example.obd;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * App-tracked distance counter — independent of the cluster odometer.
 *
 * Integrates vehicle speed (km/h) over time, accumulates kilometres, and persists
 * to SharedPreferences. This is the only odometer-style number the app can give
 * you for an E65 over ELM327: the real KOMBI odometer is on K-CAN and unreachable.
 *
 * Storage layout (file "odometer"):
 *   - total_meters (long)   — lifetime distance covered while app was logging
 *   - last_update_ms (long) — timestamp of last speed sample (for integration)
 *   - last_speed_kmh (float) — last speed observed (for trapezoid integration)
 *
 * State lives in memory and is flushed to prefs every ~10s — samples arrive at
 * ~2 Hz and rewriting the prefs XML on every one thrashes disk for hours-long
 * drives. At most ~10s of distance is lost on process death; callers should
 * invoke {@link #flush()} on disconnect to shrink that window to zero.
 *
 * Thread-safe via synchronized blocks. Designed to be called from the polling
 * thread on every "Vehicle Speed" sample.
 */
public class OdometerTracker {

    private static final String PREFS = "odometer";
    private static final String K_TOTAL_M = "total_meters";
    private static final String K_LAST_T = "last_update_ms";
    private static final String K_LAST_V = "last_speed_kmh";

    /** Ignore samples more than 30s apart — likely paused / parked / app foregrounded. */
    private static final long MAX_GAP_MS = 30_000;

    /** Ignore implausibly-high speeds (sensor glitch). */
    private static final float MAX_KMH = 300f;

    /** How often accumulated state is persisted while samples keep arriving. */
    private static final long FLUSH_INTERVAL_MS = 10_000;

    private static volatile OdometerTracker instance;

    private final SharedPreferences prefs;

    // In-memory working state — the prefs copy only refreshes on flush.
    private long totalMeters;
    private long lastUpdateMs;   // 0 = no previous sample
    private float lastSpeedKmh;
    private long lastFlushMs;

    private OdometerTracker(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.totalMeters = prefs.getLong(K_TOTAL_M, 0L);
        this.lastUpdateMs = prefs.getLong(K_LAST_T, 0L);
        this.lastSpeedKmh = prefs.getFloat(K_LAST_V, 0f);
    }

    public static OdometerTracker get(Context ctx) {
        OdometerTracker local = instance;
        if (local == null) {
            synchronized (OdometerTracker.class) {
                if (instance == null) instance = new OdometerTracker(ctx);
                local = instance;
            }
        }
        return local;
    }

    /**
     * Record a new vehicle speed sample. Integrates against the previous sample
     * using the trapezoid rule (avg of two speeds × time delta). Accumulates in
     * memory; disk is touched at most once per {@link #FLUSH_INTERVAL_MS}.
     */
    public synchronized void recordSpeed(double kmh) {
        long now = System.currentTimeMillis();
        if (kmh < 0 || kmh > MAX_KMH) return;

        if (lastUpdateMs > 0 && (now - lastUpdateMs) <= MAX_GAP_MS) {
            double dtHours = (now - lastUpdateMs) / 3_600_000.0;
            double avgKmh = (lastSpeedKmh + kmh) / 2.0;
            double deltaMeters = avgKmh * 1000.0 * dtHours;
            totalMeters += (long) deltaMeters;
        }
        // First sample or stale gap: just reset the integration window without
        // adding distance — same branch as before, now only touching memory.
        lastUpdateMs = now;
        lastSpeedKmh = (float) kmh;

        if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            lastFlushMs = now;
            persist();
        }
    }

    /**
     * Persist the in-memory state to prefs now. Call on disconnect / app
     * background so the last few seconds of distance survive process death.
     */
    public synchronized void flush() {
        lastFlushMs = System.currentTimeMillis();
        persist();
    }

    /** Total kilometres tracked since this app first started. */
    public synchronized double getTotalKm() {
        return totalMeters / 1000.0;
    }

    /** Reset the counter back to zero (with confirmation in the UI). */
    public synchronized void reset() {
        totalMeters = 0L;
        lastUpdateMs = 0L;
        lastSpeedKmh = 0f;
        prefs.edit()
                .putLong(K_TOTAL_M, 0L)
                .remove(K_LAST_T)
                .remove(K_LAST_V)
                .apply();
    }

    /** Set the counter to a specific value — e.g. seed it with the cluster odometer reading. */
    public synchronized void setTotalKm(double km) {
        if (km < 0) return;
        totalMeters = (long) (km * 1000);
        persist();
    }

    /** Must be called with the instance lock held. apply() is async — no disk on caller. */
    private void persist() {
        prefs.edit()
                .putLong(K_TOTAL_M, totalMeters)
                .putLong(K_LAST_T, lastUpdateMs)
                .putFloat(K_LAST_V, lastSpeedKmh)
                .apply();
    }
}
