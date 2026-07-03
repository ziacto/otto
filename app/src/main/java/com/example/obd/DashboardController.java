package com.example.obd;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class DashboardController {

    private final Map<String, View> cellByName = new HashMap<>();
    // Per-PID EMA-smoothed display value. Strips ECU jitter so cells don't flicker
    // between samples — alpha is chosen per signal in smoothingAlpha().
    private final Map<String, Double> smoothed = new HashMap<>();
    // Value currently displayed on screen — separate from `smoothed` so the
    // animation can interpolate from what the user is seeing to the new EMA
    // target rather than snapping. Keyed by sensor name.
    private final Map<String, Double> displayed = new HashMap<>();
    // Live tweeners, one per sensor. Cancelled when a new sample arrives so
    // we don't overlap ongoing animations.
    private final Map<String, ValueAnimator> activeAnimators = new HashMap<>();
    private static final long TWEEN_MS = 200L;
    // Wall-clock timestamp of the last real onValue() per sensor. If no
    // sample has arrived within STALE_MS (e.g. because the PID is NRC'd or
    // the socket dropped), the cell renders "—" instead of a cached zero
    // so users don't mistake stale data for real readings. Fuel Level was
    // the trigger for this — E65 DME doesn't expose 012F, so the cached
    // "0" from an earlier session bug was misread as "empty tank".
    private final Map<String, Long> lastRealSampleMs = new HashMap<>();
    private static final long STALE_MS = 6_000L;
    private final android.os.Handler staleTicker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable stalenessCheck = new Runnable() {
        @Override public void run() {
            if (root != null) {
                sweepStaleCells();
                staleTicker.postDelayed(this, 1500L);
            }
        }
    };
    private View root;
    private Activity activity;
    private ObdManagerFast obdManager;
    private CarEngineAnimationView engineAnim;
    private WarningLightsView warningLights;

    public void attach(View view, Activity act, ObdManagerFast obd) {
        this.root = view;
        this.activity = act;
        this.obdManager = obd;
        cellByName.clear();
        smoothed.clear();
        engineAnim = view.findViewById(R.id.engineAnim);

        // Warning lights row — populated as values come in. Build it programmatically
        // into the placeholder LinearLayout from the layout XML.
        View placeholder = view.findViewById(R.id.warningLightsRow);
        if (placeholder instanceof android.view.ViewGroup) {
            android.view.ViewGroup container = (android.view.ViewGroup) placeholder;
            container.removeAllViews();
            warningLights = new WarningLightsView(view.getContext());
            warningLights.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            container.addView(warningLights);
        }

        bindCell(R.id.cellCoolant, "Coolant Temp", "°C", "🌡  COOLANT");
        bindCell(R.id.cellOil,     "Oil Temp",      "°C", "🛢  OIL TEMP");
        bindCell(R.id.cellThrottle,"Throttle Position", "%",  "🎚  THROTTLE");
        bindCell(R.id.cellLoad,    "Engine Load",   "%",  "📊  LOAD");
        bindCell(R.id.cellMaf,     "Mass Air Flow", "g/s","💨  MAF");
        bindCell(R.id.cellIntake,  "Intake Air Temp","°C","🌬  INTAKE AIR");
        bindCell(R.id.cellBattery, "Battery Voltage","V",  "🔋  BATTERY");
        bindCell(R.id.cellFuel,    "Fuel Level",    "%",  "⛽  FUEL");

        // Seed gauges with the most-recent known values so they don't sit at zero
        // for the 50-500ms between attach and the first new sample. Pre-populating
        // the smoothed map also avoids a visible snap from 0 to (say) 90°C on the
        // first reading because the EMA starts from this prior.
        seedFromLastValues();

        View btnReset = view.findViewById(R.id.btnDashReset);
        View btnDtc   = view.findViewById(R.id.btnDashDtc);
        View btnVin   = view.findViewById(R.id.btnDashVin);

        btnReset.setOnClickListener(v -> confirmReset());
        btnDtc.setOnClickListener(v -> readDtc());
        btnVin.setOnClickListener(v -> readVin());

        renderVehicleCard(null);  // shows placeholder until a VIN is known
        loadVehicleFromDb();      // populate card from the last saved VIN, if any
        updateStatus();

        // Kick the staleness sweeper so PIDs the ECU never answers (fuel
        // level on E65 is a classic) don't show a lingering cached "0".
        staleTicker.removeCallbacks(stalenessCheck);
        staleTicker.postDelayed(stalenessCheck, 1500L);
    }

    /** Apply a (possibly null) VinDecoder.Result to the vehicle info card. */
    private void renderVehicleCard(VinDecoder.Result r) {
        if (root == null) return;
        TextView model  = root.findViewById(R.id.tvVehicleModel);
        TextView engine = root.findViewById(R.id.tvVehicleEngine);
        TextView vinTv  = root.findViewById(R.id.tvVehicleVin);
        // Any of the three could be null on inflation-edge cases (custom layouts,
        // tools previewing the screen). All-or-nothing render keeps this safe.
        if (model == null || engine == null || vinTv == null) return;
        if (r == null) {
            model.setText("BMW 730li (E65)");
            engine.setText("N52B30 inline-6 · 3.0L NA");
            vinTv.setText("VIN unknown — tap 'VIN' below to read");
            return;
        }
        model.setText(String.format("BMW %s", r.modelName));
        StringBuilder eng = new StringBuilder(r.engineHint == null ? "" : r.engineHint);
        if (r.modelYear > 0) {
            if (eng.length() > 0) eng.append(" · ");
            eng.append(r.modelYear);
        }
        if (r.plant != null && !r.plant.isEmpty()) {
            if (eng.length() > 0) eng.append(" · ");
            eng.append(r.plant);
        }
        engine.setText(eng.toString());
        vinTv.setText(r.vin);
    }

    /** Cheapest path to a saved VIN: the last VinProfile row in Room. */
    private void loadVehicleFromDb() {
        if (activity == null) return;
        WorkDispatcher.io(activity,
                () -> {
                    com.example.obd.db.AppDatabase db = com.example.obd.db.AppDatabase.get(activity);
                    java.util.List<com.example.obd.db.VinProfile> all = db.vins().all();
                    if (all.isEmpty() || all.get(0).vin == null) return null;
                    return VinDecoder.decode(all.get(0).vin);
                },
                result -> { if (result != null) renderVehicleCard(result); },
                e -> { /* silent — placeholder stays */ });
    }

    public void detach() {
        staleTicker.removeCallbacks(stalenessCheck);
        root = null;
        activity = null;
        engineAnim = null;
        warningLights = null;
        cellByName.clear();
        smoothed.clear();
        displayed.clear();
        lastRealSampleMs.clear();
        for (ValueAnimator a : activeAnimators.values()) {
            if (a != null) a.cancel();
        }
        activeAnimators.clear();
    }

    /**
     * Every ~1.5 s, look at all bound cells and replace the value with "—"
     * if we haven't received a real onValue() sample within STALE_MS.
     * Prevents the "cached 0 from a prior session" bug that made Fuel Level
     * look like an empty tank on the BMW E65 (whose DME simply doesn't
     * answer Mode 01 PID 2F).
     */
    private void sweepStaleCells() {
        if (root == null) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, View> e : cellByName.entrySet()) {
            Long last = lastRealSampleMs.get(e.getKey());
            boolean stale = (last == null) || (now - last > STALE_MS);
            if (!stale) continue;
            TextView tv = e.getValue().findViewById(R.id.tileValue);
            if (tv != null && !"—".contentEquals(tv.getText())) {
                tv.setText("—");
            }
        }
        // Hero RPM + Speed too
        Long rpmLast = lastRealSampleMs.get("RPM");
        if (rpmLast == null || now - rpmLast > STALE_MS) {
            TextView tv = root.findViewById(R.id.tvRpmValue);
            if (tv != null && !"—".contentEquals(tv.getText())) tv.setText("—");
        }
        Long speedLast = lastRealSampleMs.get("Vehicle Speed");
        if (speedLast == null || now - speedLast > STALE_MS) {
            TextView tv = root.findViewById(R.id.tvSpeedValue);
            if (tv != null && !"—".contentEquals(tv.getText())) tv.setText("—");
        }
    }

    public boolean isAttached() { return root != null; }

    /**
     * Pre-populate the smoothed EMA map AND render the last-known value into each
     * cell. Read from LastValuesCache, which MainActivity has been feeding on every
     * onValue. If the cache is cold (first-ever launch), this is a no-op.
     */
    private void seedFromLastValues() {
        if (root == null || activity == null) return;
        Map<String, Double> last = LastValuesCache.get(activity).snapshot();
        if (last.isEmpty()) return;
        for (Map.Entry<String, Double> e : last.entrySet()) {
            smoothed.put(e.getKey(), e.getValue());
            // Seed the display map too so the first real sample animates from
            // the last-known value instead of snapping into place.
            displayed.put(e.getKey(), e.getValue());
        }
        // Render directly without going through onValue() — keeps the seed step
        // synchronous and avoids re-smoothing a value that already came from cache.
        renderSeeded(R.id.tvRpmValue,   last.get("RPM"));
        renderSeeded(R.id.tvSpeedValue, last.get("Vehicle Speed"));
        for (Map.Entry<String, View> cellEntry : cellByName.entrySet()) {
            Double v = last.get(cellEntry.getKey());
            if (v == null) continue;
            TextView valueTv = cellEntry.getValue().findViewById(R.id.tileValue);
            if (valueTv != null) valueTv.setText(formatValue(cellEntry.getKey(), v));
        }
    }

    private void renderSeeded(int tvId, Double value) {
        if (value == null) return;
        TextView tv = root.findViewById(tvId);
        if (tv != null) tv.setText(String.format(Locale.US, "%.0f", value));
    }

    private void bindCell(int cellId, String sensorName, String unit, String label) {
        View cell = root.findViewById(cellId);
        if (cell == null) return;
        TextView labelTv = cell.findViewById(R.id.tileLabel);
        TextView unitTv = cell.findViewById(R.id.tileUnit);
        if (labelTv != null) labelTv.setText(label);
        if (unitTv != null) unitTv.setText(unit);
        cellByName.put(sensorName, cell);
    }

    /** Called by MainActivity.updateUI when dashboard is active. */
    public void onValue(String name, double value) {
        if (root == null) return;
        // Mark this sensor as "freshly reporting" so the staleness sweep
        // doesn't overwrite it with "—".
        lastRealSampleMs.put(name, System.currentTimeMillis());

        double display = smooth(name, value);

        // Forward raw (un-smoothed) targets to the animated engine view — it does
        // its own per-frame interpolation, so giving it raw samples preserves
        // responsiveness on RPM/MAF transients.
        if (engineAnim != null) {
            switch (name) {
                case "RPM":           engineAnim.setRpm(value); break;
                case "Coolant Temp":  engineAnim.setCoolantTemp(value); break;
                case "Mass Air Flow": engineAnim.setMaf(value); break;
                case "Oil Temp":      engineAnim.setOilTemp(value); break;
            }
        }

        // Warning lights — update from polled values
        if (warningLights != null) {
            switch (name) {
                case "MIL Status": {
                    boolean milOn = MilStatusCommand.isMilOn(value);
                    int count = MilStatusCommand.dtcCount(value);
                    warningLights.update(WarningLightsView.Light.MIL,
                            milOn ? WarningLightsView.State.FAULT : WarningLightsView.State.OK,
                            milOn ? "ON" : "OFF",
                            milOn ? "Check engine light illuminated on the cluster."
                                  : "Check engine light is OFF.");
                    warningLights.update(WarningLightsView.Light.DTC_COUNT,
                            count == 0 ? WarningLightsView.State.OK
                                       : (count > 5 ? WarningLightsView.State.FAULT : WarningLightsView.State.WARN),
                            String.valueOf(count),
                            count + " DTC(s) stored");
                    break;
                }
                case "Battery Voltage": {
                    WarningLightsView.State s = (value < 11.5) ? WarningLightsView.State.FAULT
                            : (value < 12.4 ? WarningLightsView.State.WARN
                            : (value > 14.7 ? WarningLightsView.State.WARN : WarningLightsView.State.OK));
                    warningLights.update(WarningLightsView.Light.BATTERY, s,
                            String.format(Locale.US, "%.1fV", value), null);
                    break;
                }
                case "Coolant Temp": {
                    WarningLightsView.State s = (value >= 110) ? WarningLightsView.State.FAULT
                            : (value >= 95 ? WarningLightsView.State.WARN
                            : (value >= 70 ? WarningLightsView.State.OK : WarningLightsView.State.UNKNOWN));
                    warningLights.update(WarningLightsView.Light.TEMP, s,
                            String.format(Locale.US, "%.0f°", value), null);
                    break;
                }
            }
        }
        if ("MIL Status".equals(name)) return;

        if ("RPM".equals(name)) {
            TextView tv = root.findViewById(R.id.tvRpmValue);
            if (tv != null) animateValue(name, tv, display);
            return;
        }
        if ("Vehicle Speed".equals(name)) {
            TextView tv = root.findViewById(R.id.tvSpeedValue);
            if (tv != null) animateValue(name, tv, display);
            return;
        }

        View cell = cellByName.get(name);
        if (cell == null) return;
        TextView valueTv = cell.findViewById(R.id.tileValue);
        if (valueTv != null) animateValue(name, valueTv, display);
    }

    /**
     * Tween the visible value from what the user sees now to the new EMA
     * target over {@link #TWEEN_MS}. Prevents the "digits snap on every poll"
     * effect and reads as a live gauge instead of a text log. Cancels any
     * in-flight animation for the same sensor so we never race.
     *
     * If this is the very first sample (nothing displayed yet), we skip the
     * tween — the seed step already rendered a starting value from the last
     * session, so animating from that to the fresh sample is exactly right.
     */
    private void animateValue(String name, TextView tv, double target) {
        Double fromBoxed = displayed.get(name);
        // No prior value on screen — write immediately so the user sees the
        // first sample within one frame of it arriving.
        if (fromBoxed == null || Double.isNaN(fromBoxed)) {
            displayed.put(name, target);
            tv.setText(formatValue(name, target));
            return;
        }
        double from = fromBoxed;
        // Cancel any running tween for this same sensor so its frames don't
        // fight the new one.
        ValueAnimator running = activeAnimators.remove(name);
        if (running != null) running.cancel();
        // Very small delta — snap without animating. Cheap and avoids the
        // extra vsync frame when idle.
        if (Math.abs(target - from) < snapThreshold(name)) {
            displayed.put(name, target);
            tv.setText(formatValue(name, target));
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat((float) from, (float) target);
        anim.setDuration(TWEEN_MS);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            double cur = ((Number) a.getAnimatedValue()).doubleValue();
            displayed.put(name, cur);
            tv.setText(formatValue(name, cur));
        });
        activeAnimators.put(name, anim);
        anim.start();
    }

    /** Below this delta we don't spend cycles animating — the tick is invisible. */
    private static double snapThreshold(String name) {
        switch (name) {
            case "RPM": return 2.0;
            case "Vehicle Speed": return 0.5;
            case "Battery Voltage": return 0.05;
            case "Coolant Temp":
            case "Oil Temp":
            case "Intake Air Temp": return 0.5;
            default: return 0.2;
        }
    }

    /**
     * Exponential moving average per sensor. Temperatures get a heavy filter
     * (α=0.15) because they're physically slow; RPM/throttle/MAF get a lighter
     * filter (α=0.35) so transients still feel snappy. The strict raw value is
     * shown if this is the first sample for that sensor.
     */
    private double smooth(String name, double value) {
        double alpha = smoothingAlpha(name);
        Double prev = smoothed.get(name);
        double next = (prev == null) ? value : prev + (value - prev) * alpha;
        smoothed.put(name, next);
        return next;
    }

    private static double smoothingAlpha(String name) {
        switch (name) {
            case "Coolant Temp":
            case "Oil Temp":
            case "Intake Air Temp":
                return 0.15;
            case "Battery Voltage":
            case "Fuel Level":
                return 0.25;
            default:
                return 0.35; // RPM, Speed, MAF, throttle, load
        }
    }

    private String formatValue(String name, double value) {
        switch (name) {
            // Whole-number readings — a real instrument cluster never shows a
            // fractional rev count or speed. Keeping RPM/Speed integer also stops
            // the hero number ("1894.8") from wrapping to a second line.
            case "RPM":
            case "Vehicle Speed":
            case "Coolant Temp":
            case "Oil Temp":
            case "Intake Air Temp":
            case "Ambient Temp":
            case "Engine Load":
            case "Throttle Position":
            case "Fuel Level":
                return String.format(Locale.US, "%.0f", value);
            default:
                return String.format(Locale.US, "%.1f", value);
        }
    }

    public void updateStatus() {
        if (root == null) return;
        TextView statusTv = root.findViewById(R.id.tvDashStatus);
        if (statusTv == null) return;
        if (obdManager != null && obdManager.isConnected()) {
            statusTv.setText("● Connected");
            statusTv.setTextColor(Color.parseColor("#03DAC5"));
        } else {
            statusTv.setText("● Disconnected");
            statusTv.setTextColor(Color.parseColor("#FF6E6E"));
        }
    }

    private void confirmReset() {
        if (notConnected()) return;
        new AlertDialog.Builder(activity)
                .setTitle("Reset (Clear DTCs)?")
                .setMessage("Sends OBD-II Mode 04. Erases stored fault codes and freeze-frame data.\n\n"
                        + "Won't fix underlying problems — codes will return if the fault persists.")
                .setPositiveButton("Clear", (d, w) -> doReset())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doReset() {
        showResult("Clearing DTCs…", "#FFD600");
        WorkDispatcher.io(activity,
                () -> obdManager.clearDtcs(),
                ok -> showResult(
                        ok ? "✓ DTCs cleared. Re-read in a minute to confirm."
                           : "Clear request sent but no ACK from ECU.",
                        ok ? "#03DAC5" : "#FFD600"),
                e -> showResult("Reset failed: " + e.getMessage(), "#F44336"));
    }

    private void readDtc() {
        if (notConnected()) return;
        showResult("Reading fault codes…", "#FFD600");
        WorkDispatcher.io(activity,
                () -> obdManager.readDtcs(3),
                codes -> {
                    if (codes.isEmpty()) {
                        showResult("✓ No fault codes stored.", "#03DAC5");
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append(codes.size()).append(" code(s):\n");
                        for (String c : codes) {
                            sb.append("• ").append(c);
                            String desc = DtcUtil.lookupDescription(c);
                            if (desc != null) sb.append("  ").append(desc);
                            sb.append('\n');
                        }
                        sb.append("Open 'Fault Codes' for full controls.");
                        showResult(sb.toString(), "#FFD600");
                    }
                },
                e -> showResult("DTC read failed: " + e.getMessage(), "#F44336"));
    }

    private void readVin() {
        if (notConnected()) return;
        showResult("Reading VIN…", "#FFD600");
        final Activity act = activity;  // snapshot — activity field may be nulled by detach
        WorkDispatcher.io(act,
                () -> {
                    String vin = obdManager.readVin();
                    if (vin != null && !vin.isEmpty()) {
                        if (act == null || act.isDestroyed()) return vin;
                        // Persist immediately so other screens can pick it up.
                        com.example.obd.db.AppDatabase db =
                                com.example.obd.db.AppDatabase.get(act.getApplicationContext());
                        com.example.obd.db.VinProfile p = db.vins().byVin(vin);
                        if (p == null) {
                            p = new com.example.obd.db.VinProfile();
                            p.vin = vin;
                            p.createdAt = System.currentTimeMillis();
                        }
                        p.lastSeenAt = System.currentTimeMillis();
                        VinDecoder.Result d = VinDecoder.decode(vin);
                        if (d != null) {
                            p.model = d.modelName;
                            if (d.modelYear > 0) p.year = d.modelYear;
                            p.displayName = "BMW " + d.modelName
                                    + (d.modelYear > 0 ? " " + d.modelYear : "");
                        }
                        db.vins().upsert(p);
                    }
                    return vin;
                },
                vin -> {
                    if (vin == null) {
                        showResult("VIN not available from this ECU.", "#FFD600");
                    } else {
                        showResult("VIN: " + vin, "#03DAC5");
                        renderVehicleCard(VinDecoder.decode(vin));
                    }
                },
                e -> showResult("VIN read failed: " + e.getMessage(), "#F44336"));
    }

    private boolean notConnected() {
        if (obdManager == null || !obdManager.isConnected()) {
            Toast.makeText(activity, "Not connected", Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private void runOnUi(Runnable r) {
        if (activity != null) activity.runOnUiThread(r);
    }

    private void showResult(String text, String colorHex) {
        if (root == null) return;
        TextView tv = root.findViewById(R.id.tvDashActionResult);
        if (tv != null) {
            tv.setText(text);
            tv.setTextColor(Color.parseColor(colorHex));
            tv.setVisibility(View.VISIBLE);
        }
    }
}
