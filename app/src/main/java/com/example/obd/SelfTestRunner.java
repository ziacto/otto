package com.example.obd;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Boots the app against the simulator, listens to the poll loop for a fixed
 * window, and grades every dashboard PID: did it deliver at least one plausible
 * sample? Result is a text report the drawer action drops into a dialog.
 *
 * This is the "dry-run QA" the user asked for — no adapter, no car, no manual
 * navigation. Just tap the drawer and see if the whole stack (transport, init,
 * NRC filter, parsers, listener, cache) is healthy end-to-end.
 */
public final class SelfTestRunner {

    private SelfTestRunner() {}

    /** Result bundle — report text ready for a dialog, plus a pass/fail flag. */
    public static final class Report {
        public final boolean allPassed;
        public final String text;
        Report(boolean p, String t) { this.allPassed = p; this.text = t; }
    }

    private static final long RUN_MS = 12_000L; // long enough for two dashboard cycles

    /** Range check per PID name. Values outside this window count as a failure. */
    private static final Map<String, double[]> RANGES = new LinkedHashMap<>();
    static {
        RANGES.put("RPM",              new double[]{ 0,   9000  });
        RANGES.put("Vehicle Speed",    new double[]{ 0,   300   });
        RANGES.put("Coolant Temp",     new double[]{ -40, 215   });
        RANGES.put("Oil Temp",         new double[]{ -40, 215   });
        RANGES.put("Throttle Position",new double[]{ 0,   100   });
        RANGES.put("Engine Load",      new double[]{ 0,   100   });
        RANGES.put("Battery Voltage",  new double[]{ 8,   17    });
        RANGES.put("Fuel Level",       new double[]{ 0,   100   });
        RANGES.put("Mass Air Flow",    new double[]{ 0,   655   });
        RANGES.put("Intake Air Temp",  new double[]{ -40, 215   });
        RANGES.put("MIL Status",       new double[]{ 0,   2000  });
    }

    /**
     * Blocking — call off the UI thread. Owns the OBD manager for the duration,
     * so any live connection is disconnected first and restored is left to the
     * caller if needed (typical: after self-test the user reconnects via the
     * connect flow).
     */
    public static Report run(Context ctx) {
        // ConcurrentHashMap: the poll thread writes these while this (caller)
        // thread reads them after the latch — plain HashMaps had no visibility
        // guarantee. Report ordering comes from iterating RANGES, so losing
        // insertion order here doesn't matter.
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        Map<String, Double> lastValue = new ConcurrentHashMap<>();
        Map<String, Boolean> inRange = new ConcurrentHashMap<>();
        for (String n : RANGES.keySet()) { counts.put(n, 0); inRange.put(n, false); }

        // Trips as soon as every PID has produced an in-range sample, so a healthy
        // stack finishes early instead of always sitting out the full window.
        CountDownLatch endLatch = new CountDownLatch(1);
        SpeedPollerListener listener = new SpeedPollerListener() {
            @Override public void onValue(String name, double value, String unit) {
                if (counts.containsKey(name)) counts.merge(name, 1, Integer::sum);
                lastValue.put(name, value);
                double[] r = RANGES.get(name);
                if (r != null && value >= r[0] && value <= r[1]) inRange.put(name, true);
                if (!inRange.containsValue(false)) endLatch.countDown();
            }
            @Override public void onError(String msg) { /* recorded via log */ }
        };

        ObdManagerFast mgr = new ObdManagerFast(
                ctx, listener,
                () -> 500,
                () -> java.util.Arrays.asList(
                        new RpmCommand(), new SpeedCommand(),
                        new CoolantTempCommand(), new OilTempCommand(),
                        new ThrottlePositionCommand(), new EngineLoadCommand(),
                        new BatteryVoltageCommand(), new FuelLevelCommand(),
                        new MassAirFlowCommand(), new IntakeAirTempCommand(),
                        new MilStatusCommand()));

        try {
            ObdLogger.get().log(ObdLogger.Level.INFO, "Self-test start");
            mgr.connectSimulator();
        } catch (Exception e) {
            return new Report(false, "FAILED — could not open simulator: " + e.getMessage());
        }

        // Let the poll loop churn until every PID passed (latch) or RUN_MS elapses,
        // then tear everything down.
        long startedAt = System.currentTimeMillis();
        try {
            endLatch.await(RUN_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        long elapsedS = Math.max(1, (System.currentTimeMillis() - startedAt) / 1000);
        mgr.disconnect();

        // Build report.
        int passed = 0, total = RANGES.size();
        StringBuilder sb = new StringBuilder();
        for (String name : RANGES.keySet()) {
            int c = counts.get(name);
            boolean ok = c > 0 && Boolean.TRUE.equals(inRange.get(name));
            if (ok) passed++;
            sb.append(ok ? "PASS  " : "FAIL  ");
            sb.append(name);
            sb.append("  ×").append(c);
            Double v = lastValue.get(name);
            if (v != null) sb.append("  last=").append(String.format(java.util.Locale.US, "%.1f", v));
            if (!ok) {
                if (c == 0) sb.append("  (no samples received)");
                else if (!Boolean.TRUE.equals(inRange.get(name))) sb.append("  (out of range)");
            }
            sb.append('\n');
        }
        String summary = String.format("%d / %d PIDs passed in %ds\n\n", passed, total, elapsedS);
        ObdLogger.get().log(ObdLogger.Level.INFO,
                "Self-test done: " + passed + "/" + total);
        return new Report(passed == total, summary + sb);
    }
}
