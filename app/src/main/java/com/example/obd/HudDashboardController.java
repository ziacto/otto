package com.example.obd;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * HUD-style dashboard — modern Heads-Up Display alternative to the tile / gauge dashboards.
 *
 * Layout (top to bottom):
 *   1. Header: title + connection status
 *   2. Shift lights LED bar (F1 style)
 *   3. Big RPM readout (digital, with cyan glow)
 *   4. Speed + Gear (estimated) + Throttle big readouts
 *   5. 9 mini cells in 3 rows:
 *        Row 1: Coolant / Oil / MAF
 *        Row 2: Load / Intake / Battery
 *        Row 3: Timing / Torque / Lambda
 *   6. Fuel health panel: short trim / long trim / ambient temp
 *   7. Session performance panel: peak RPM, peak speed, distance, instant fuel, avg fuel, duration
 *   8. Module status grid (8 D-CAN modules as LED indicators) + SCAN button
 *   9. Reset Session / Read DTCs buttons
 *
 * Gear estimation uses ZF 6HP26 ratios for the E65 730li (final drive 3.46,
 * wheel circumference ~2.168 m). Shows "N" when below 3 km/h or ratio unmatched.
 */
public class HudDashboardController {

    // ZF 6HP26 gear ratios for E65 730li
    private static final float[] GEAR_RATIOS = {4.171f, 2.340f, 1.521f, 1.143f, 0.867f, 0.691f};
    private static final float FINAL_DRIVE   = 3.46f;
    private static final float WHEEL_CIRC_M  = 2.168f; // 245/50R18 front tyre
    // precomputed: WHEEL_CIRC_M * 3.6 / (60 * FINAL_DRIVE) = 0.037594
    private static final double GEAR_K = WHEEL_CIRC_M * 3.6 / (60.0 * FINAL_DRIVE);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final PerformanceTracker perf = new PerformanceTracker();

    private View root;
    private Context ctx;
    private ObdManagerFast manager;

    private TextView tvStatus, tvActionResult;
    private TextView tvRpm, tvSpeed, tvThrottle, tvGear;
    private TextView tvPeakRpm, tvPeakSpeed, tvSessionDistance, tvSessionDuration;
    private TextView tvInstantFuel, tvAvgFuel;
    private TextView tvStft, tvLtft, tvAmbient;
    private ShiftLightsView shiftLights;
    private ModuleStatusGridView moduleGrid;

    // Mini-cell holders (each include block has miniLabel / miniValue / miniUnit)
    private MiniCell coolant, oil, maf, load, intake, battery;
    private MiniCell timing, torque, lambda;

    // For gear estimation we need the last known speed + rpm
    private double lastRpm   = 0;
    private double lastSpeed = 0;

    // Staleness: the whole HUD polls one group, so all telemetry goes stale
    // together when the socket drops. Dim the readouts instead of freezing
    // them crisp on the last value — same bug class the tile dashboard's
    // "—" sweep fixed, applied here with alpha.
    private volatile long lastSampleAtMs = 0L;
    private static final long STALE_MS = 6_000L;
    private static final float STALE_ALPHA = 0.35f;

    private final Runnable perfRefresh = new Runnable() {
        @Override public void run() {
            if (root == null) return;
            refreshPerformancePanel();
            boolean stale = lastSampleAtMs == 0
                    || System.currentTimeMillis() - lastSampleAtMs > STALE_MS;
            setTelemetryAlpha(stale ? STALE_ALPHA : 1f);
            updateStatus();
            ui.postDelayed(this, 500);
        }
    };

    private void setTelemetryAlpha(float a) {
        TextView[] heroes = { tvRpm, tvSpeed, tvThrottle, tvGear, tvStft, tvLtft, tvAmbient };
        for (TextView tv : heroes) {
            if (tv != null && tv.getAlpha() != a) tv.setAlpha(a);
        }
        MiniCell[] cells = { coolant, oil, maf, load, intake, battery, timing, torque, lambda };
        for (MiniCell c : cells) {
            if (c != null && c.value != null && c.value.getAlpha() != a) c.value.setAlpha(a);
        }
    }

    public void attach(View view, ObdManagerFast manager) {
        this.root = view;
        this.ctx = view.getContext();
        this.manager = manager;
        this.perf.reset();
        lastRpm = 0;
        lastSpeed = 0;
        lastSampleAtMs = 0;
        lastGearCandidate = -1;
        stableGear = 0;

        bindViews();
        wireButtons();
        updateStatus();

        ui.postDelayed(perfRefresh, 500);
    }

    public void detach() {
        ui.removeCallbacks(perfRefresh);
        root = null;
        ctx = null;
        manager = null;
        tvStatus = tvActionResult = tvRpm = tvSpeed = tvThrottle = tvGear = null;
        tvPeakRpm = tvPeakSpeed = tvSessionDistance = tvSessionDuration = null;
        tvInstantFuel = tvAvgFuel = null;
        tvStft = tvLtft = tvAmbient = null;
        shiftLights = null;
        moduleGrid = null;
        coolant = oil = maf = load = intake = battery = null;
        timing = torque = lambda = null;
    }

    public boolean isAttached() { return root != null; }

    private void bindViews() {
        tvStatus      = root.findViewById(R.id.hudStatus);
        tvActionResult = root.findViewById(R.id.hudActionResult);
        tvRpm         = root.findViewById(R.id.hudRpm);
        tvSpeed       = root.findViewById(R.id.hudSpeed);
        tvThrottle    = root.findViewById(R.id.hudThrottle);
        tvGear        = root.findViewById(R.id.hudGear);
        tvPeakRpm     = root.findViewById(R.id.hudPeakRpm);
        tvPeakSpeed   = root.findViewById(R.id.hudPeakSpeed);
        tvSessionDistance = root.findViewById(R.id.hudSessionDistance);
        tvSessionDuration = root.findViewById(R.id.hudSessionDuration);
        tvInstantFuel = root.findViewById(R.id.hudInstantFuel);
        tvAvgFuel     = root.findViewById(R.id.hudAvgFuel);
        tvStft        = root.findViewById(R.id.hudStft);
        tvLtft        = root.findViewById(R.id.hudLtft);
        tvAmbient     = root.findViewById(R.id.hudAmbient);
        shiftLights   = root.findViewById(R.id.hudShiftLights);
        moduleGrid    = root.findViewById(R.id.hudModuleGrid);

        if (shiftLights != null) shiftLights.setRedline(6700f);

        coolant = bindCell(R.id.hudCellCoolant, "🌡  COOLANT",  "°C");
        oil     = bindCell(R.id.hudCellOil,     "🛢  OIL",      "°C");
        maf     = bindCell(R.id.hudCellMaf,     "💨  MAF",      "g/s");
        load    = bindCell(R.id.hudCellLoad,    "📊  LOAD",     "%");
        intake  = bindCell(R.id.hudCellIntake,  "🌬  INTAKE",   "°C");
        battery = bindCell(R.id.hudCellBattery, "🔋  BATTERY",  "V");
        timing  = bindCell(R.id.hudCellTiming,  "⚡  TIMING",   "° BTDC");
        torque  = bindCell(R.id.hudCellTorque,  "💪  TORQUE",   "% max");
        lambda  = bindCell(R.id.hudCellLambda,  "⚗  LAMBDA",   "λ");
    }

    private MiniCell bindCell(int id, String label, String unit) {
        View cell = root.findViewById(id);
        if (cell == null) return null;
        MiniCell c = new MiniCell();
        c.value = cell.findViewById(R.id.miniValue);
        TextView labelTv = cell.findViewById(R.id.miniLabel);
        TextView unitTv  = cell.findViewById(R.id.miniUnit);
        if (labelTv != null) labelTv.setText(label);
        if (unitTv  != null) unitTv.setText(unit);
        return c;
    }

    private void wireButtons() {
        Button scan  = root.findViewById(R.id.hudScanModules);
        Button reset = root.findViewById(R.id.hudResetSession);
        Button dtc   = root.findViewById(R.id.hudReadDtcs);

        scan.setOnClickListener(v -> scanModules());
        reset.setOnClickListener(v -> {
            perf.reset();
            lastRpm = 0;
            lastSpeed = 0;
            refreshPerformancePanel();
            Toast.makeText(ctx, "Session reset", Toast.LENGTH_SHORT).show();
        });
        dtc.setOnClickListener(v -> readDtcs());
    }

    /** Called from MainActivity.updateUI on every polled value. */
    public void onValue(String name, double value) {
        if (root == null) return;
        lastSampleAtMs = System.currentTimeMillis();
        switch (name) {
            case "RPM":
                if (tvRpm != null) tvRpm.setText(String.format(Locale.US, "%.0f", value));
                if (shiftLights != null) shiftLights.setRpm((float) value);
                perf.onRpm((float) value);
                lastRpm = value;
                updateGear();
                break;
            case "Vehicle Speed":
                if (tvSpeed != null) tvSpeed.setText(String.format(Locale.US, "%.0f", value));
                perf.onSpeed((float) value);
                lastSpeed = value;
                updateGear();
                break;
            case "Throttle Position":
                if (tvThrottle != null) tvThrottle.setText(String.format(Locale.US, "%.0f", value));
                break;
            case "Coolant Temp":
                setCell(coolant, value, "%.0f", value, 110, 95);
                break;
            case "Oil Temp":
                setCell(oil, value, "%.0f", value, 130, 110);
                break;
            case "Mass Air Flow":
                setCell(maf, value, "%.0f", -1, 0, 0);
                perf.onMaf((float) value);
                break;
            case "Engine Load":
                setCell(load, value, "%.0f", value, 95, 75);
                perf.onEngineLoad((float) value);
                break;
            case "Intake Air Temp":
                setCell(intake, value, "%.0f", value, 75, 65);
                perf.onIntakeTemp((float) value);
                break;
            case "Battery Voltage":
                // warn under 12.4, fault under 11.5 — inverted thresholds
                if (battery != null && battery.value != null) {
                    battery.value.setText(String.format(Locale.US, "%.1f", value));
                    if (value < 11.5)      battery.value.setTextColor(0xFFF44336);
                    else if (value < 12.4) battery.value.setTextColor(0xFFFFB300);
                    else                   battery.value.setTextColor(0xFFFFFFFF);
                }
                break;
            case "Timing Advance":
                if (timing != null && timing.value != null)
                    timing.value.setText(String.format(Locale.US, "%.1f", value));
                break;
            case "Engine Torque":
                // PID 0x62: actual engine percent torque (−125 % to +130 %)
                setCell(torque, value, "%.0f", value < 0 ? 0 : value, 90, 70);
                break;
            case "Lambda":
                // 1.000 = stoichiometric; colour green near 1.0, amber/red when
                // far off. Red must be tested FIRST — with amber (>0.05) first,
                // the >0.10 red branch was unreachable.
                if (lambda != null && lambda.value != null) {
                    lambda.value.setText(String.format(Locale.US, "%.3f", value));
                    double deviation = Math.abs(value - 1.0);
                    if (deviation > 0.10)      lambda.value.setTextColor(0xFFF44336);
                    else if (deviation > 0.05) lambda.value.setTextColor(0xFFFFB300);
                    else                       lambda.value.setTextColor(0xFF03DAC5);
                }
                break;
            case "Short Term Fuel Trim":
                setFuelTrim(tvStft, value);
                break;
            case "Long Term Fuel Trim":
                setFuelTrim(tvLtft, value);
                break;
            case "Ambient Air Temperature":
                if (tvAmbient != null)
                    tvAmbient.setText(String.format(Locale.US, "%.0f", value));
                break;
        }
    }

    // Debounce: adjacent ZF ratios are only ~12-25% apart, so measurement
    // noise near a midpoint used to flicker the display between two gears.
    // A candidate must repeat on two consecutive samples before it shows.
    private int lastGearCandidate = -1;
    private int stableGear = 0;

    /** Estimate current gear from last known RPM + speed using ZF 6HP26 ratios. */
    private void updateGear() {
        if (tvGear == null) return;
        int candidate = estimateGear(lastRpm, lastSpeed);
        if (candidate == lastGearCandidate) {
            stableGear = candidate;
        }
        lastGearCandidate = candidate;
        int gear = stableGear;
        if (gear == 0) {
            tvGear.setText("N");
            tvGear.setTextColor(0xFF888888);
        } else {
            tvGear.setText(String.valueOf(gear));
            // low gears amber (aggressive), high gears cyan (cruising)
            if (gear <= 2)      tvGear.setTextColor(0xFFFFB300);
            else if (gear <= 4) tvGear.setTextColor(0xFFFFFFFF);
            else                tvGear.setTextColor(0xFF03DAC5);
        }
    }

    /**
     * Returns 1–6 for a matched ZF 6HP26 gear, or 0 if below speed threshold /
     * unmatched. Tolerance is ±9%: adjacent ratios differ by as little as ~25%
     * (5th 0.867 vs 6th 0.691), so the old ±20% window overlapped neighbours
     * and let 5th/6th mis-identify; 9% stays under half the smallest gap while
     * absorbing tyre slip and OBD speed rounding.
     */
    private static int estimateGear(double rpm, double speedKmh) {
        if (speedKmh < 3 || rpm < 500) return 0;
        double calcRatio = rpm * GEAR_K / speedKmh;
        int best = 0;
        double bestErr = Double.MAX_VALUE;
        for (int i = 0; i < GEAR_RATIOS.length; i++) {
            double err = Math.abs(calcRatio - GEAR_RATIOS[i]) / GEAR_RATIOS[i];
            if (err < bestErr) { bestErr = err; best = i + 1; }
        }
        return bestErr < 0.09 ? best : 0;
    }

    private void setCell(MiniCell cell, double v, String fmt, double colorVal, double hot, double warn) {
        if (cell == null || cell.value == null) return;
        cell.value.setText(String.format(Locale.US, fmt, v));
        if (colorVal < 0)          cell.value.setTextColor(0xFFFFFFFF);
        else if (colorVal >= hot)  cell.value.setTextColor(0xFFF44336);
        else if (colorVal >= warn) cell.value.setTextColor(0xFFFFB300);
        else                       cell.value.setTextColor(0xFFFFFFFF);
    }

    private void setFuelTrim(TextView tv, double pct) {
        if (tv == null) return;
        tv.setText(String.format(Locale.US, "%+.1f", pct));
        // ±5% normal, ±10% marginal, beyond that fault
        double abs = Math.abs(pct);
        if (abs > 10)      tv.setTextColor(0xFFF44336);
        else if (abs > 5)  tv.setTextColor(0xFFFFB300);
        else               tv.setTextColor(0xFF03DAC5);
    }

    private void refreshPerformancePanel() {
        if (tvPeakRpm == null) return;
        tvPeakRpm.setText(String.format(Locale.US, "%.0f", perf.getPeakRpm()));
        tvPeakSpeed.setText(String.format(Locale.US, "%.0f", perf.getPeakSpeed()));
        tvSessionDistance.setText(String.format(Locale.US, "%.1f", perf.getSessionDistanceKm()));

        double instant = perf.getInstantFuelLper100km();
        tvInstantFuel.setText(instant > 0 && instant < 50
                ? String.format(Locale.US, "%.1f", instant) : "—");

        double avg = perf.getAverageFuelLper100km();
        tvAvgFuel.setText(avg > 0 && avg < 50
                ? String.format(Locale.US, "%.1f", avg) : "—");

        long ms  = perf.getSessionDurationMs();
        long min = ms / 60_000;
        long sec = (ms / 1000) % 60;
        tvSessionDuration.setText(String.format(Locale.US, "%d:%02d", min, sec));
    }

    public void updateStatus() {
        if (tvStatus == null) return;
        if (manager != null && manager.isConnected()) {
            tvStatus.setText("● ONLINE");
            tvStatus.setTextColor(Color.parseColor("#03DAC5"));
        } else {
            tvStatus.setText("● OFFLINE");
            tvStatus.setTextColor(Color.parseColor("#FF6E6E"));
        }
    }

    // ===================================================================
    // Module scan + DTC actions
    // ===================================================================

    private void scanModules() {
        if (!checkConnected()) return;
        showActionResult("Scanning D-CAN modules...", "#FFD600");
        if (moduleGrid != null) moduleGrid.resetAll();

        new Thread(() -> {
            int total = 0;
            for (BmwModule m : BmwModule.dCanModules()) {
                try {
                    List<String> codes;
                    if (m == BmwModule.DME) {
                        codes = manager.readDtcs(3);
                    } else {
                        codes = manager.readModuleDtcs(m);
                    }
                    ModuleStatusGridView.State state = codes.isEmpty()
                            ? ModuleStatusGridView.State.ONLINE
                            : ModuleStatusGridView.State.FAULT;
                    total += codes.size();
                    final BmwModule fm = m;
                    ui.post(() -> { if (moduleGrid != null) moduleGrid.update(fm, state); });
                } catch (Exception e) {
                    final BmwModule fm = m;
                    ui.post(() -> { if (moduleGrid != null) moduleGrid.update(fm, ModuleStatusGridView.State.OFFLINE); });
                }
            }
            final int totalCodes = total;
            ui.post(() -> showActionResult(
                    "Scan complete. " + totalCodes + " fault code(s) across all modules.",
                    totalCodes == 0 ? "#03DAC5" : "#FFB300"));
        }, "HudScan").start();
    }

    private void readDtcs() {
        if (!checkConnected()) return;
        showActionResult("Reading DME DTCs...", "#FFD600");
        new Thread(() -> {
            try {
                List<String> codes = manager.readDtcs(3);
                StringBuilder sb = new StringBuilder();
                if (codes.isEmpty()) sb.append("✓ No DTCs in DME");
                else {
                    sb.append(codes.size()).append(" DTC(s):\n");
                    for (String c : codes) {
                        sb.append("  • ").append(c);
                        DtcDictionary.Entry e = DtcDictionary.get().lookup(c);
                        if (e != null) sb.append("  ").append(e.title);
                        sb.append('\n');
                    }
                }
                String result = sb.toString();
                String color  = codes.isEmpty() ? "#03DAC5" : "#FFB300";
                ui.post(() -> showActionResult(result, color));
            } catch (Exception e) {
                final String err = e.getMessage();
                ui.post(() -> showActionResult("✗ Read failed: " + err, "#F44336"));
            }
        }, "HudReadDtcs").start();
    }

    private void showActionResult(String text, String colorHex) {
        if (tvActionResult == null) return;
        tvActionResult.setText(text);
        tvActionResult.setTextColor(Color.parseColor(colorHex));
        tvActionResult.setVisibility(View.VISIBLE);
    }

    private boolean checkConnected() {
        if (manager == null || !manager.isConnected()) {
            Toast.makeText(ctx, "Connect to the car first", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    /** Simple struct holding the value TextView of a mini cell. */
    private static class MiniCell {
        TextView value;
    }
}
