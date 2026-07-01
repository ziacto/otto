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

    private static volatile OdometerTracker instance;

    private final SharedPreferences prefs;

    private OdometerTracker(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
     * using the trapezoid rule (avg of two speeds × time delta).
     */
    public synchronized void recordSpeed(double kmh) {
        long now = System.currentTimeMillis();
        if (kmh < 0 || kmh > MAX_KMH) return;

        long lastT = prefs.getLong(K_LAST_T, 0L);
        float lastV = prefs.getFloat(K_LAST_V, 0f);

        if (lastT > 0 && (now - lastT) <= MAX_GAP_MS) {
            double dtHours = (now - lastT) / 3_600_000.0;
            double avgKmh = (lastV + kmh) / 2.0;
            double deltaMeters = avgKmh * 1000.0 * dtHours;
            long totalM = prefs.getLong(K_TOTAL_M, 0L);
            prefs.edit()
                    .putLong(K_TOTAL_M, totalM + (long) deltaMeters)
                    .putLong(K_LAST_T, now)
                    .putFloat(K_LAST_V, (float) kmh)
                    .apply();
        } else {
            // First sample or stale — reset the integration window without adding distance
            prefs.edit()
                    .putLong(K_LAST_T, now)
                    .putFloat(K_LAST_V, (float) kmh)
                    .apply();
        }
    }

    /** Total kilometres tracked since this app first started. */
    public double getTotalKm() {
        return prefs.getLong(K_TOTAL_M, 0L) / 1000.0;
    }

    /** Reset the counter back to zero (with confirmation in the UI). */
    public synchronized void reset() {
        prefs.edit()
                .putLong(K_TOTAL_M, 0L)
                .remove(K_LAST_T)
                .remove(K_LAST_V)
                .apply();
    }

    /** Set the counter to a specific value — e.g. seed it with the cluster odometer reading. */
    public synchronized void setTotalKm(double km) {
        if (km < 0) return;
        prefs.edit()
                .putLong(K_TOTAL_M, (long) (km * 1000))
                .apply();
    }
}
