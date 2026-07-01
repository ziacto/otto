package com.example.obd;

/**
 * Tracks peaks and rolling averages within the current driving session.
 *
 * "Session" = lifetime of the controller it's attached to (HUD dashboard). When
 * the screen detaches/reattaches the tracker resets — peaks are per-session, not
 * lifetime. For lifetime stats we have {@link OdometerTracker} and {@link DataLogger}.
 *
 * Tracks:
 *   - peakRpm — highest engine speed seen
 *   - peakSpeed — highest road speed seen
 *   - peakLoad — highest engine load %
 *   - peakIntakeTemp — highest IAT (heat soak monitoring)
 *   - sessionDistanceKm — integrated from speed samples
 *   - sessionDurationMs — wall-clock since first sample
 *   - instantFuelLper100km — instantaneous fuel consumption estimate from MAF
 *   - avgFuelLper100km — rolling average over the session
 *
 * Fuel consumption math: MAF (g/s) × 14.7 (stoichiometric AFR) ÷ 730 (petrol density g/L)
 * × 3600 (s/h) = L/h. Divided by speed (km/h) gives L/100km × 100.
 */
public class PerformanceTracker {

    private float peakRpm = 0f;
    private float peakSpeed = 0f;
    private float peakLoad = 0f;
    private float peakIntakeTemp = -100f;

    private long firstSampleMs = 0L;
    private long lastSpeedSampleMs = 0L;
    private float lastSpeedKmh = 0f;
    private double sessionDistanceKm = 0.0;

    private float lastMafGs = 0f;
    private float lastSpeedForFuel = 0f;
    private double fuelLitresAccum = 0.0;
    private long lastMafSampleMs = 0L;

    public synchronized void onRpm(float rpm) {
        if (rpm > peakRpm) peakRpm = rpm;
        touch();
    }

    public synchronized void onSpeed(float kmh) {
        if (kmh > peakSpeed) peakSpeed = kmh;
        long now = System.currentTimeMillis();
        if (lastSpeedSampleMs > 0 && (now - lastSpeedSampleMs) < 5000) {
            double dtHours = (now - lastSpeedSampleMs) / 3_600_000.0;
            double avgKmh = (lastSpeedKmh + kmh) / 2.0;
            sessionDistanceKm += avgKmh * dtHours;
        }
        lastSpeedSampleMs = now;
        lastSpeedKmh = kmh;
        lastSpeedForFuel = kmh;
        touch();
    }

    public synchronized void onEngineLoad(float pct) {
        if (pct > peakLoad) peakLoad = pct;
        touch();
    }

    public synchronized void onIntakeTemp(float c) {
        if (c > peakIntakeTemp) peakIntakeTemp = c;
        touch();
    }

    public synchronized void onMaf(float gs) {
        long now = System.currentTimeMillis();
        if (lastMafSampleMs > 0 && (now - lastMafSampleMs) < 5000) {
            // Average MAF over the interval, then convert to litres
            float avgMaf = (lastMafGs + gs) / 2f;
            // L/s = (MAF g/s × 14.7 / 730)
            double litresPerSecond = (avgMaf * 14.7) / 730.0;
            double dtSeconds = (now - lastMafSampleMs) / 1000.0;
            fuelLitresAccum += litresPerSecond * dtSeconds;
        }
        lastMafSampleMs = now;
        lastMafGs = gs;
        touch();
    }

    private void touch() {
        if (firstSampleMs == 0) firstSampleMs = System.currentTimeMillis();
    }

    public synchronized void reset() {
        peakRpm = 0f;
        peakSpeed = 0f;
        peakLoad = 0f;
        peakIntakeTemp = -100f;
        firstSampleMs = 0L;
        lastSpeedSampleMs = 0L;
        lastMafSampleMs = 0L;
        lastSpeedKmh = 0f;
        lastMafGs = 0f;
        lastSpeedForFuel = 0f;
        sessionDistanceKm = 0.0;
        fuelLitresAccum = 0.0;
    }

    public synchronized float getPeakRpm() { return peakRpm; }
    public synchronized float getPeakSpeed() { return peakSpeed; }
    public synchronized float getPeakLoad() { return peakLoad; }
    public synchronized float getPeakIntakeTemp() { return peakIntakeTemp; }
    public synchronized double getSessionDistanceKm() { return sessionDistanceKm; }
    public synchronized double getSessionFuelLitres() { return fuelLitresAccum; }

    public synchronized long getSessionDurationMs() {
        return firstSampleMs == 0 ? 0 : (System.currentTimeMillis() - firstSampleMs);
    }

    /** Instantaneous L/100km estimate from the latest MAF + speed samples. */
    public synchronized double getInstantFuelLper100km() {
        if (lastMafGs <= 0 || lastSpeedForFuel <= 1) return 0.0;
        double litresPerHour = (lastMafGs * 14.7 / 730.0) * 3600.0;
        return (litresPerHour / lastSpeedForFuel) * 100.0;
    }

    /** Session average L/100km using accumulated fuel and distance. */
    public synchronized double getAverageFuelLper100km() {
        if (sessionDistanceKm < 0.1) return 0.0;
        return (fuelLitresAccum / sessionDistanceKm) * 100.0;
    }
}
