package com.example.obd;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Persists the last known OBD reading for each sensor to SharedPreferences.
 * Dashboard controllers read this on attach() to seed gauges with the most
 * recent values instead of zero — eliminates the blank-gauge feel between
 * "Connected" and the first new sample landing (~50-500ms gap).
 *
 * Disk writes are throttled (one per ~5s) so high-cadence poll loops don't
 * thrash SharedPreferences.
 */
public final class LastValuesCache {

    private static final String PREFS_NAME = "obd_last_values";
    private static final String KEY_TS_PREFIX = "ts_";
    private static final long PERSIST_INTERVAL_MS = 5_000L;

    private static volatile LastValuesCache instance;

    public static LastValuesCache get(Context ctx) {
        LastValuesCache i = instance;
        if (i == null) {
            synchronized (LastValuesCache.class) {
                i = instance;
                if (i == null) {
                    i = new LastValuesCache(ctx.getApplicationContext());
                    instance = i;
                }
            }
        }
        return i;
    }

    private final SharedPreferences prefs;
    private final Map<String, Double> cache = new HashMap<>();
    private volatile long lastPersistMs = 0L;
    private volatile boolean dirty = false;

    private LastValuesCache(Context appCtx) {
        this.prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadFromDisk();
    }

    /** Record a fresh reading; called from MainActivity.onValue on every sample. */
    public void record(String name, double value) {
        if (name == null || Double.isNaN(value) || Double.isInfinite(value)) return;
        synchronized (cache) {
            cache.put(name, value);
        }
        dirty = true;
        long now = System.currentTimeMillis();
        if (now - lastPersistMs > PERSIST_INTERVAL_MS) {
            lastPersistMs = now;
            // Persist on the SharedPreferences async writer thread; we don't block.
            persistAsync();
        }
    }

    /** Last known value for sensor, or {@code Double.NaN} if none. */
    public double get(String name) {
        synchronized (cache) {
            Double v = cache.get(name);
            return v == null ? Double.NaN : v;
        }
    }

    /** Snapshot of all cached sensors (for bulk seeding). */
    public Map<String, Double> snapshot() {
        synchronized (cache) {
            return new HashMap<>(cache);
        }
    }

    /** Wipe in-memory + on-disk cached values. Used by Clear Data. */
    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
        prefs.edit().clear().apply();
        lastPersistMs = 0L;
        dirty = false;
    }

    private void persistAsync() {
        Map<String, Double> snap;
        synchronized (cache) {
            snap = new HashMap<>(cache);
        }
        SharedPreferences.Editor ed = prefs.edit();
        long ts = System.currentTimeMillis();
        for (Map.Entry<String, Double> e : snap.entrySet()) {
            ed.putString(e.getKey(), Double.toString(e.getValue()));
        }
        ed.putLong(KEY_TS_PREFIX + "saved", ts);
        ed.apply(); // async — does not block
        dirty = false;
    }

    private void loadFromDisk() {
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (k.startsWith(KEY_TS_PREFIX)) continue;
            Object v = e.getValue();
            if (v instanceof String) {
                try {
                    cache.put(k, Double.parseDouble((String) v));
                } catch (NumberFormatException ignored) {}
            }
        }
    }
}
