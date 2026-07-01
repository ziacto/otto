package com.example.obd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * BMW-style gauge dashboard.
 *
 * On-screen elements:
 *   - Header bar with title + connection status
 *   - Warning lights row (cluster-style icons) — MIL, DTC count, DSC, EGS, Battery, Temp
 *   - Action buttons: Read DTCs, Clear DTCs, Read VIN
 *   - Action result chip (populated when buttons fire)
 *   - Big circular gauges: RPM, Speed
 *   - Small circular gauges: Engine Load, Throttle
 *   - Bar gauges: Coolant, Oil, Intake Air, Fuel
 *   - Battery + Odometer mini-cards
 *
 * Every gauge is clickable — tap shows a dialog with what the value is, normal
 * range, warning conditions, and why it matters. Content loaded from
 * assets/knowledge/sensor_info.json.
 *
 * Warning lights are updated from polled values:
 *   - MIL: from MilStatusCommand (Mode 01 PID 0x01)
 *   - DTC count: from same PID
 *   - Battery: from polled Battery Voltage
 *   - Temp: from polled Coolant Temp
 *   - DSC / EGS: must be triggered by reading their modules separately
 *     (we do this as part of the "Read DTCs" button to refresh the lights)
 */
public class GaugeDashboardController {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private View root;
    private Context ctx;
    private Activity activity;
    private ObdManagerFast manager;
    private JSONObject sensorInfo;

    private CircularGaugeView gRpm, gSpeed, gLoad, gThrottle;
    private BarGaugeView gCoolant, gOil, gIntake, gFuel;
    private TextView tvBattery, tvOdometer, tvStatus, tvActionResult;
    private WarningLightsView warningLights;
    private View cardBattery, cardOdometer;

    public void attach(View view, ObdManagerFast manager) {
        this.root = view;
        this.ctx = view.getContext();
        if (ctx instanceof Activity) this.activity = (Activity) ctx;
        this.manager = manager;
        this.sensorInfo = loadSensorInfo();

        bindViews();
        configureGauges();
        wireTapForInfo();
        wireActionButtons();
        wireWarningLightsTaps();

        updateOdometerDisplay();
        updateStatus();
    }

    private void bindViews() {
        gRpm      = root.findViewById(R.id.gaugeRpm);
        gSpeed    = root.findViewById(R.id.gaugeSpeed);
        gLoad     = root.findViewById(R.id.gaugeLoad);
        gThrottle = root.findViewById(R.id.gaugeThrottle);
        gCoolant  = root.findViewById(R.id.gaugeCoolant);
        gOil      = root.findViewById(R.id.gaugeOil);
        gIntake   = root.findViewById(R.id.gaugeIntake);
        gFuel     = root.findViewById(R.id.gaugeFuel);
        tvBattery = root.findViewById(R.id.gaugeBattery);
        tvOdometer = root.findViewById(R.id.gaugeOdometer);
        tvStatus  = root.findViewById(R.id.gaugeStatus);
        tvActionResult = root.findViewById(R.id.gaugeActionResult);
        warningLights = root.findViewById(R.id.warningLights);
        cardBattery = root.findViewById(R.id.cardBattery);
        cardOdometer = root.findViewById(R.id.cardOdometer);
    }

    private void configureGauges() {
        gRpm.setLabel("RPM");
        gRpm.setUnit("1/min");
        gRpm.setRange(0, 7000);
        gRpm.setRedlineStart(6500);
        gRpm.setValueDecimals(0);

        gSpeed.setLabel("SPEED");
        gSpeed.setUnit("km/h");
        gSpeed.setRange(0, 260);
        gSpeed.setValueDecimals(0);

        gLoad.setLabel("LOAD");
        gLoad.setUnit("%");
        gLoad.setRange(0, 100);
        gLoad.setRedlineStart(90);
        gLoad.setValueDecimals(0);

        gThrottle.setLabel("THROTTLE");
        gThrottle.setUnit("%");
        gThrottle.setRange(0, 100);
        gThrottle.setValueDecimals(0);

        gCoolant.setLabel("🌡  COOLANT");
        gCoolant.setUnit("°C");
        gCoolant.setRange(60, 115);
        gCoolant.setThresholds(75, 95, 105);
        gCoolant.setValueDecimals(1);

        gOil.setLabel("🛢  OIL");
        gOil.setUnit("°C");
        gOil.setRange(60, 135);
        gOil.setThresholds(80, 110, 125);
        gOil.setValueDecimals(1);

        gIntake.setLabel("🌬  INTAKE AIR");
        gIntake.setUnit("°C");
        gIntake.setRange(0, 80);
        gIntake.setThresholds(Float.NaN, 65, 75);
        gIntake.setValueDecimals(1);

        gFuel.setLabel("⛽  FUEL");
        gFuel.setUnit("%");
        gFuel.setRange(0, 100);
        gFuel.setValueDecimals(0);
    }

    private void wireTapForInfo() {
        gRpm.setOnClickListener(v -> showInfo("RPM"));
        gSpeed.setOnClickListener(v -> showInfo("Vehicle Speed"));
        gLoad.setOnClickListener(v -> showInfo("Engine Load"));
        gThrottle.setOnClickListener(v -> showInfo("Throttle Position"));
        gCoolant.setOnClickListener(v -> showInfo("Coolant Temp"));
        gOil.setOnClickListener(v -> showInfo("Oil Temp"));
        gIntake.setOnClickListener(v -> showInfo("Intake Air Temp"));
        gFuel.setOnClickListener(v -> showInfo("Fuel Level"));
        if (cardBattery != null) cardBattery.setOnClickListener(v -> showInfo("Battery Voltage"));
        if (cardOdometer != null) cardOdometer.setOnClickListener(v -> showOdometerInfo());
    }

    private void wireActionButtons() {
        Button btnDtc = root.findViewById(R.id.btnGaugeDtc);
        Button btnClear = root.findViewById(R.id.btnGaugeClear);
        Button btnVin = root.findViewById(R.id.btnGaugeVin);

        btnDtc.setOnClickListener(v -> readDtcs());
        btnClear.setOnClickListener(v -> confirmClearDtcs());
        btnVin.setOnClickListener(v -> readVin());
    }

    private void wireWarningLightsTaps() {
        if (warningLights == null) return;
        warningLights.setOnLightClickListener((light, state, detail) -> {
            String title;
            String why;
            switch (light) {
                case MIL:
                    title = "Check Engine (MIL)";
                    why = state == WarningLightsView.State.FAULT
                            ? "MIL is illuminated on the cluster. The DME has stored at least one emissions-related fault.\n\nNext steps:\n• Tap READ DTCs to see exactly which fault(s)\n• Each code's description and likely cause is shown in the dialog\n• Fix the root cause before clearing (codes will return if the fault persists)"
                            : "MIL is OFF — no active emissions faults.";
                    break;
                case DTC_COUNT:
                    title = "Stored DTC Count";
                    why = detail != null && !detail.isEmpty()
                            ? detail + "\n\nTap READ DTCs to read the codes themselves."
                            : "No DTCs stored.";
                    break;
                case DSC:
                    title = "DSC / ABS Status";
                    why = "Reads DTCs from the DSC module via D-CAN.\n\n" +
                            (detail.isEmpty() ? "No scan run yet — tap READ DTCs to scan." : detail);
                    break;
                case EGS:
                    title = "Transmission (EGS) Status";
                    why = "Reads DTCs from the ZF GA6HP19Z transmission controller via D-CAN.\n\n" +
                            (detail.isEmpty() ? "No scan run yet — tap READ DTCs to scan." : detail);
                    break;
                case BATTERY:
                    title = "Battery / Charging";
                    why = state == WarningLightsView.State.FAULT
                            ? "Battery voltage is critically low (<11.5 V). Charging system likely failing. Test the alternator."
                            : state == WarningLightsView.State.WARN
                                ? "Voltage borderline. Could be: weak battery (GCC heat shortens life to 5-7 years), unregistered new battery, or alternator weakening."
                                : "Charging system OK.";
                    break;
                case TEMP:
                    title = "Engine Temperature";
                    why = state == WarningLightsView.State.FAULT
                            ? "Coolant >105 °C — overheating. Pull over safely. Check coolant level when cold."
                            : state == WarningLightsView.State.WARN
                                ? "Coolant >95 °C — hot. Common in stop-go GCC traffic. Watch for further rise."
                                : "Temperature normal.";
                    break;
                default:
                    title = light.label;
                    why = detail == null ? "" : detail;
            }
            new AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setMessage(why)
                    .setPositiveButton("Close", null)
                    .show();
        });
    }

    /** Bottom-sheet style info dialog for a tapped gauge. */
    private void showInfo(String sensorName) {
        if (sensorInfo == null) return;
        JSONObject sensors = sensorInfo.optJSONObject("sensors");
        if (sensors == null) return;
        JSONObject s = sensors.optJSONObject(sensorName);
        if (s == null) {
            Toast.makeText(ctx, "No info available for " + sensorName, Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder body = new StringBuilder();
        body.append("WHAT IT IS\n").append(s.optString("what", "")).append("\n\n");
        body.append("NORMAL RANGE\n").append(s.optString("normal", "")).append("\n\n");
        body.append("WHEN TO WORRY\n").append(s.optString("warn", "")).append("\n\n");
        body.append("WHY IT MATTERS\n").append(s.optString("help", ""));

        new AlertDialog.Builder(ctx)
                .setTitle(s.optString("title", sensorName))
                .setMessage(body.toString())
                .setPositiveButton("Close", null)
                .show();
    }

    private void showOdometerInfo() {
        new AlertDialog.Builder(ctx)
                .setTitle("App-tracked Odometer")
                .setMessage("Distance the app has integrated from your speed samples since you first connected.\n\n"
                        + "The real cluster odometer lives in the KOMBI on K-CAN — your ELM327 cannot reach it.\n\n"
                        + "To seed this number with your cluster reading, open the drawer → Odometer → 'Seed from cluster'.")
                .setPositiveButton("Close", null)
                .show();
    }

    // ===================================================================
    // Action buttons
    // ===================================================================

    private void readDtcs() {
        if (!checkConnected()) return;
        showActionResult("Reading DTCs…", "#FFD600");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            int totalCodes = 0;
            try {
                java.util.List<String> dme = manager.readDtcs(3);
                totalCodes += dme.size();
                if (dme.isEmpty()) {
                    sb.append("✓ DME: no codes\n");
                } else {
                    sb.append("DME (").append(dme.size()).append("):\n");
                    for (String c : dme) {
                        sb.append("  • ").append(c);
                        String desc = DtcUtil.lookupDescription(c);
                        if (desc == null) {
                            DtcDictionary.get().loadIfNeeded(ctx);
                            DtcDictionary.Entry e = DtcDictionary.get().lookup(c);
                            if (e != null) desc = e.title;
                        }
                        if (desc != null) sb.append("  ").append(desc);
                        sb.append('\n');
                    }
                }

                // Module scans — also update warning lights
                java.util.List<String> dsc = safeModuleScan(BmwModule.DSC);
                if (!dsc.isEmpty()) {
                    sb.append("DSC (").append(dsc.size()).append("):\n");
                    for (String c : dsc) sb.append("  • ").append(c).append('\n');
                    totalCodes += dsc.size();
                }
                ui.post(() -> warningLights.update(WarningLightsView.Light.DSC,
                        dsc.isEmpty() ? WarningLightsView.State.OK : WarningLightsView.State.FAULT,
                        dsc.isEmpty() ? "" : String.valueOf(dsc.size()),
                        dsc.isEmpty() ? "No DSC faults" : "DSC fault codes:\n  " + String.join("\n  ", dsc)));

                java.util.List<String> egs = safeModuleScan(BmwModule.EGS);
                if (!egs.isEmpty()) {
                    sb.append("EGS (").append(egs.size()).append("):\n");
                    for (String c : egs) sb.append("  • ").append(c).append('\n');
                    totalCodes += egs.size();
                }
                ui.post(() -> warningLights.update(WarningLightsView.Light.EGS,
                        egs.isEmpty() ? WarningLightsView.State.OK : WarningLightsView.State.FAULT,
                        egs.isEmpty() ? "" : String.valueOf(egs.size()),
                        egs.isEmpty() ? "No EGS faults" : "EGS fault codes:\n  " + String.join("\n  ", egs)));

                final int total = totalCodes;
                final String text = total == 0 ? "✓ No fault codes in DME/DSC/EGS" : sb.toString();
                ui.post(() -> showActionResult(text, total == 0 ? "#03DAC5" : "#FFD600"));
            } catch (Exception e) {
                final String err = e.getMessage();
                ui.post(() -> showActionResult("✗ Read failed: " + err, "#F44336"));
            }
        }, "GaugeReadDtcs").start();
    }

    private java.util.List<String> safeModuleScan(BmwModule mod) {
        try { return manager.readModuleDtcs(mod); }
        catch (Exception e) { return java.util.Collections.emptyList(); }
    }

    private void confirmClearDtcs() {
        if (!checkConnected()) return;
        new AlertDialog.Builder(ctx)
                .setTitle("Clear DTCs?")
                .setMessage("Sends OBD-II Mode 04 to erase stored fault codes and freeze-frame data.\n\n"
                        + "Won't fix underlying problems — codes will return if the fault persists. "
                        + "Will also reset readiness monitors (emissions inspection won't pass until they re-complete).")
                .setPositiveButton("Clear", (d, w) -> doClearDtcs())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doClearDtcs() {
        showActionResult("Clearing DTCs…", "#FFD600");
        new Thread(() -> {
            String msg;
            String color;
            try {
                boolean ok = manager.clearDtcs();
                msg = ok ? "✓ DTCs cleared. Re-read in a minute to confirm they don't come back."
                         : "Clear sent but DME did not acknowledge.";
                color = ok ? "#03DAC5" : "#FFD600";
            } catch (Exception e) {
                msg = "✗ Clear failed: " + e.getMessage();
                color = "#F44336";
            }
            final String finalMsg = msg;
            final String finalColor = color;
            ui.post(() -> showActionResult(finalMsg, finalColor));
        }, "GaugeClearDtcs").start();
    }

    private void readVin() {
        if (!checkConnected()) return;
        showActionResult("Reading VIN…", "#FFD600");
        new Thread(() -> {
            try {
                String vin = manager.readVin();
                String result = vin == null || vin.isEmpty()
                        ? "VIN not available from this ECU"
                        : "VIN: " + vin;
                ui.post(() -> showActionResult(result, vin == null ? "#FFD600" : "#03DAC5"));
            } catch (Exception e) {
                String err = e.getMessage();
                ui.post(() -> showActionResult("✗ VIN read failed: " + err, "#F44336"));
            }
        }, "GaugeReadVin").start();
    }

    private void showActionResult(String text, String colorHex) {
        if (tvActionResult == null) return;
        tvActionResult.setText(text);
        tvActionResult.setTextColor(Color.parseColor(colorHex));
        tvActionResult.setVisibility(View.VISIBLE);
    }

    // ===================================================================
    // Polled value handling
    // ===================================================================

    /** Called from MainActivity.updateUI on every polled value. */
    public void onValue(String name, double value) {
        if (root == null) return;
        switch (name) {
            case "RPM":               if (gRpm != null) gRpm.setValue((float) value); break;
            case "Vehicle Speed":
                if (gSpeed != null) gSpeed.setValue((float) value);
                updateOdometerDisplay();
                break;
            case "Engine Load":       if (gLoad != null) gLoad.setValue((float) value); break;
            case "Throttle Position": if (gThrottle != null) gThrottle.setValue((float) value); break;
            case "Coolant Temp":
                if (gCoolant != null) gCoolant.setValue((float) value);
                updateTempLight(value);
                break;
            case "Oil Temp":          if (gOil != null) gOil.setValue((float) value); break;
            case "Intake Air Temp":   if (gIntake != null) gIntake.setValue((float) value); break;
            case "Fuel Level":        if (gFuel != null) gFuel.setValue((float) value); break;
            case "Battery Voltage":
                updateBatteryDisplay(value);
                updateBatteryLight(value);
                break;
            case "MIL Status":
                updateMilLights(value);
                break;
        }
    }

    private void updateBatteryDisplay(double value) {
        if (tvBattery == null) return;
        tvBattery.setText(String.format(Locale.US, "%.1f", value));
        if (value < 11.5) tvBattery.setTextColor(0xFFF44336);
        else if (value < 12.4) tvBattery.setTextColor(0xFFFFB300);
        else tvBattery.setTextColor(0xFFFFFFFF);
    }

    private void updateBatteryLight(double v) {
        if (warningLights == null) return;
        WarningLightsView.State s;
        String detail;
        if (v < 11.5) { s = WarningLightsView.State.FAULT; detail = String.format(Locale.US, "%.1f V — critically low", v); }
        else if (v < 12.4) { s = WarningLightsView.State.WARN; detail = String.format(Locale.US, "%.1f V — borderline", v); }
        else if (v > 14.7) { s = WarningLightsView.State.WARN; detail = String.format(Locale.US, "%.1f V — overcharging", v); }
        else { s = WarningLightsView.State.OK; detail = String.format(Locale.US, "%.1f V — normal", v); }
        warningLights.update(WarningLightsView.Light.BATTERY, s, String.format(Locale.US, "%.1fV", v), detail);
    }

    private void updateTempLight(double coolant) {
        if (warningLights == null) return;
        WarningLightsView.State s;
        String detail;
        if (coolant >= 110) { s = WarningLightsView.State.FAULT; detail = String.format(Locale.US, "%.0f °C — overheating", coolant); }
        else if (coolant >= 95) { s = WarningLightsView.State.WARN; detail = String.format(Locale.US, "%.0f °C — hot", coolant); }
        else if (coolant >= 70) { s = WarningLightsView.State.OK; detail = String.format(Locale.US, "%.0f °C — normal", coolant); }
        else { s = WarningLightsView.State.UNKNOWN; detail = String.format(Locale.US, "%.0f °C — warming up", coolant); }
        warningLights.update(WarningLightsView.Light.TEMP, s, String.format(Locale.US, "%.0f°", coolant), detail);
    }

    private void updateMilLights(double value) {
        if (warningLights == null) return;
        boolean milOn = MilStatusCommand.isMilOn(value);
        int count = MilStatusCommand.dtcCount(value);
        warningLights.update(WarningLightsView.Light.MIL,
                milOn ? WarningLightsView.State.FAULT : WarningLightsView.State.OK,
                milOn ? "ON" : "OFF",
                milOn ? "Check engine light is ILLUMINATED on the cluster."
                      : "Check engine light is OFF.");
        warningLights.update(WarningLightsView.Light.DTC_COUNT,
                count == 0 ? WarningLightsView.State.OK
                           : (count > 5 ? WarningLightsView.State.FAULT : WarningLightsView.State.WARN),
                String.valueOf(count),
                count == 0 ? "No DTCs stored." : count + " DTC(s) stored in DME. Tap READ DTCs.");
    }

    private void updateOdometerDisplay() {
        if (tvOdometer == null || ctx == null) return;
        tvOdometer.setText(String.format(Locale.US, "%,.0f", OdometerTracker.get(ctx).getTotalKm()));
    }

    public void updateStatus() {
        if (tvStatus == null) return;
        if (manager != null && manager.isConnected()) {
            tvStatus.setText("● Connected");
            tvStatus.setTextColor(Color.parseColor("#03DAC5"));
        } else {
            tvStatus.setText("● Disconnected");
            tvStatus.setTextColor(Color.parseColor("#FF6E6E"));
        }
    }

    private boolean checkConnected() {
        if (manager == null || !manager.isConnected()) {
            Toast.makeText(ctx, "Connect to the car first", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private JSONObject loadSensorInfo() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("knowledge/sensor_info.json"), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public void detach() {
        root = null;
        ctx = null;
        activity = null;
        manager = null;
        sensorInfo = null;
        gRpm = gSpeed = gLoad = gThrottle = null;
        gCoolant = gOil = gIntake = gFuel = null;
        tvBattery = tvOdometer = tvStatus = tvActionResult = null;
        warningLights = null;
        cardBattery = cardOdometer = null;
    }

    public boolean isAttached() { return root != null; }
}
