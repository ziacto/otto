package com.example.obd;

import android.content.Context;

import com.example.obd.db.AppDatabase;
import com.example.obd.db.PidSample;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Persists incoming PID samples and produces two diagnostic signals:
 *
 *   1. **Battery health forecast** — tracks Battery Voltage samples and flags when
 *      the 7-day mean drops more than 0.3 V below the 30-day mean. Predicts dying
 *      battery weeks before an actual no-start event.
 *   2. **Predictive trend alerts** — for any sampled PID, compares the latest
 *      30-minute mean to the 30-day baseline. Returns a description string when
 *      the deviation exceeds {@link #SIGMA_THRESHOLD} standard deviations.
 *
 * Storage: Room {@link com.example.obd.db.PidSample} table.
 * Sampling: hooked from {@link MainActivity#updateUI} via {@link #record(Context, String, double)}.
 *
 * Heavy lifting (variance calc) happens on a background thread; UI just polls
 * {@link #latestWarnings(Context)} when the user opens the Garage screen.
 */
public final class TrendEngine {

    public static final double SIGMA_THRESHOLD = 2.0;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    private TrendEngine() {}

    /** Wipe every persisted sample. Returns the count that was removed. */
    public static int clearAll(Context ctx) {
        AppDatabase db = AppDatabase.get(ctx);
        int before = db.samples().total();
        db.samples().deleteAll();
        return before;
    }

    /** Append a sample. Cheap; non-blocking off the caller. */
    public static void record(Context ctx, String name, double value) {
        if (name == null || Double.isNaN(value) || Double.isInfinite(value)) return;
        AppDatabase db = AppDatabase.get(ctx);
        new Thread(() -> {
            try {
                PidSample s = new PidSample();
                s.vin = "default";
                s.name = name;
                s.value = value;
                s.ts = System.currentTimeMillis();
                db.samples().insert(s);
            } catch (Exception ignored) { /* DB write failures non-fatal */ }
        }, "PidSampleWriter").start();
    }

    /** Battery-health verdict. Null if not enough data. */
    public static String batteryForecast(Context ctx) {
        AppDatabase db = AppDatabase.get(ctx);
        long now = System.currentTimeMillis();
        Double mean30 = db.samples().averageSince("Battery Voltage", now - 30 * DAY);
        Double mean7  = db.samples().averageSince("Battery Voltage", now - 7  * DAY);
        if (mean30 == null || mean7 == null) return null;
        if (db.samples().countSince("Battery Voltage", now - 30 * DAY) < 50) return null;
        double delta = mean7 - mean30;
        if (delta < -0.3) {
            return String.format(java.util.Locale.US,
                    "Battery: 7-day mean %.2fV is %.2fV below 30-day baseline (%.2fV). "
                  + "Plan replacement in the next 2-4 weeks.",
                    mean7, -delta, mean30);
        }
        if (delta < -0.15) {
            return String.format(java.util.Locale.US,
                    "Battery: 7-day mean %.2fV trending below baseline (%.2fV). Watch it.",
                    mean7, mean30);
        }
        return null;
    }

    /**
     * Run the trend check across the canonical sensitive PIDs and return one warning
     * per drift. Empty list if nothing exceeds threshold or data is insufficient.
     */
    public static List<String> latestWarnings(Context ctx) {
        AppDatabase db = AppDatabase.get(ctx);
        long now = System.currentTimeMillis();
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        String[] watched = {
                "Coolant Temp", "Oil Temp", "Battery Voltage",
                "O2 Sensor", "Engine Load", "Long Term Fuel Trim"
        };
        for (String name : watched) {
            List<PidSample> baselineSamples = db.samples()
                    .samplesSince(name, now - 30 * DAY);
            if (baselineSamples.size() < 200) continue;
            double mean = 0;
            for (PidSample s : baselineSamples) mean += s.value;
            mean /= baselineSamples.size();
            double sumSq = 0;
            for (PidSample s : baselineSamples) { double d = s.value - mean; sumSq += d * d; }
            double stddev = Math.sqrt(sumSq / baselineSamples.size());
            if (stddev < 1e-6) continue;

            Double recent = db.samples().averageSince(name, now - 30 * 60_000L);
            if (recent == null) continue;
            double sigma = (recent - mean) / stddev;
            if (Math.abs(sigma) >= SIGMA_THRESHOLD) {
                out.add(String.format(java.util.Locale.US,
                        "%s drifting %.1fσ (recent %.2f vs baseline %.2f ± %.2f)",
                        name, sigma, recent, mean, stddev));
            }
        }
        String battery = batteryForecast(ctx);
        if (battery != null) out.add(0, battery);
        return out;
    }
}
