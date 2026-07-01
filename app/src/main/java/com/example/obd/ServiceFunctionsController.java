package com.example.obd;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Service Functions screen — exposes ISTA-style write operations and the CAN sniffer.
 *
 * Each card runs its routine on a background thread, posts the result back to the UI
 * thread, and shows a confirmation dialog before any write operation (CBS reset,
 * battery registration). The poll thread is paused for the duration of each call
 * inside ObdManagerFast; this controller just orchestrates UI + threading.
 *
 * CAN sniffer captures each frame line to a daily log file in the app's filesDir so
 * it can be pulled via `adb pull` for offline analysis.
 */
public class ServiceFunctionsController {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private View root;
    private Context ctx;
    private ObdManagerFast manager;

    private Thread snifferThread;
    private volatile boolean snifferRunning = false;
    private File snifferFile;

    public void attach(View view, ObdManagerFast manager) {
        this.root = view;
        this.ctx = view.getContext();
        this.manager = manager;

        wireModuleId();
        wireCbsReset();
        wireBatteryRegistration();
        wireSniffer();
    }

    public void detach() {
        stopSnifferIfRunning();
        root = null;
        ctx = null;
        manager = null;
    }

    // ===================================================================
    // 1. Module Identification
    // ===================================================================

    private void wireModuleId() {
        Button b = root.findViewById(R.id.btnReadIds);
        TextView out = root.findViewById(R.id.idResults);
        b.setOnClickListener(v -> {
            if (!checkConnected()) return;
            b.setEnabled(false);
            out.setText("Reading… (5-10s)");
            new Thread(() -> {
                StringBuilder sb = new StringBuilder();
                for (BmwModule mod : BmwModule.values()) {
                    try {
                        ObdManagerFast.ModuleIdentification id = manager.readModuleId(mod);
                        sb.append(mod.label).append("\n");
                        sb.append("  Part #:   ").append(id.partNumber == null ? "—" : id.partNumber).append("\n");
                        sb.append("  SW ver:   ").append(id.softwareVersion == null ? "—" : id.softwareVersion).append("\n");
                        sb.append("  HW ver:   ").append(id.hardwareNumber == null ? "—" : id.hardwareNumber).append("\n");
                        sb.append("  VIN echo: ").append(id.vinFromEcu == null ? "—" : id.vinFromEcu).append("\n\n");
                    } catch (IOException e) {
                        sb.append(mod.label).append("\n  ERROR: ").append(e.getMessage()).append("\n\n");
                    }
                }
                ui.post(() -> {
                    out.setText(sb.toString());
                    b.setEnabled(true);
                });
            }, "ModuleIdRead").start();
        });
    }

    // ===================================================================
    // 2. CBS Reset
    // ===================================================================

    private void wireCbsReset() {
        Spinner spinner = root.findViewById(R.id.cbsItemSpinner);
        Button b = root.findViewById(R.id.btnResetCbs);
        TextView out = root.findViewById(R.id.cbsResult);

        ObdManagerFast.CbsItem[] items = ObdManagerFast.CbsItem.values();
        String[] labels = new String[items.length];
        for (int i = 0; i < items.length; i++) labels[i] = items[i].label;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        b.setOnClickListener(v -> {
            if (!checkConnected()) return;
            int pos = spinner.getSelectedItemPosition();
            ObdManagerFast.CbsItem item = items[pos];
            new AlertDialog.Builder(ctx)
                    .setTitle("Confirm CBS reset")
                    .setMessage("Reset \"" + item.label + "\" service counter on the DME?\n\n"
                            + "Only do this AFTER the work has been done. Ignition ON, engine OFF.")
                    .setPositiveButton("Reset", (d, w) -> doCbsReset(item, b, out))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void doCbsReset(ObdManagerFast.CbsItem item, Button b, TextView out) {
        b.setEnabled(false);
        out.setText("Resetting…");
        new Thread(() -> {
            String msg;
            try {
                boolean ok = manager.resetCbsCounter(item);
                msg = ok ? ("✓ " + item.label + " counter reset OK")
                         : ("✗ DME did not acknowledge — counter may not be reset");
            } catch (IOException e) {
                msg = "✗ Error: " + e.getMessage();
            }
            String finalMsg = msg;
            ui.post(() -> { out.setText(finalMsg); b.setEnabled(true); });
        }, "CbsReset").start();
    }

    // ===================================================================
    // 3. Battery Registration
    // ===================================================================

    private void wireBatteryRegistration() {
        EditText ah = root.findViewById(R.id.batteryAh);
        Spinner typeSpin = root.findViewById(R.id.batteryType);
        Button b = root.findViewById(R.id.btnRegisterBattery);
        TextView out = root.findViewById(R.id.batteryResult);

        ObdManagerFast.BatteryType[] types = ObdManagerFast.BatteryType.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) labels[i] = types[i].label;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpin.setAdapter(adapter);

        b.setOnClickListener(v -> {
            if (!checkConnected()) return;
            int cap;
            try {
                cap = Integer.parseInt(ah.getText().toString().trim());
            } catch (NumberFormatException e) {
                Toast.makeText(ctx, "Enter a capacity number", Toast.LENGTH_SHORT).show();
                return;
            }
            if (cap < 40 || cap > 110) {
                Toast.makeText(ctx, "Capacity must be 40-110 Ah", Toast.LENGTH_SHORT).show();
                return;
            }
            ObdManagerFast.BatteryType type = types[typeSpin.getSelectedItemPosition()];
            new AlertDialog.Builder(ctx)
                    .setTitle("Register new battery")
                    .setMessage("Tell the DME/IBS that the battery is now:\n\n"
                            + "  • " + cap + " Ah\n"
                            + "  • " + type.label + "\n\n"
                            + "WARNING: wrong values cause alternator over/under-charging. "
                            + "Verify the new battery sticker first.")
                    .setPositiveButton("Register", (d, w) -> doBatteryRegister(cap, type, b, out))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void doBatteryRegister(int cap, ObdManagerFast.BatteryType type, Button b, TextView out) {
        b.setEnabled(false);
        out.setText("Writing to DME…");
        new Thread(() -> {
            String msg;
            try {
                boolean ok = manager.registerBattery(cap, type);
                msg = ok ? ("✓ Battery " + cap + "Ah " + type.label + " registered")
                         : ("✗ DME did not acknowledge — registration may have failed");
            } catch (IOException e) {
                msg = "✗ Error: " + e.getMessage();
            }
            String finalMsg = msg;
            ui.post(() -> { out.setText(finalMsg); b.setEnabled(true); });
        }, "BatReg").start();
    }

    // ===================================================================
    // 4. CAN Sniffer
    // ===================================================================

    private void wireSniffer() {
        Button start = root.findViewById(R.id.btnStartSniffer);
        Button stop = root.findViewById(R.id.btnStopSniffer);
        TextView status = root.findViewById(R.id.snifferStatus);

        start.setOnClickListener(v -> {
            if (!checkConnected()) return;
            startSniffer(start, stop, status);
        });
        stop.setOnClickListener(v -> stopSniffer(start, stop, status));
    }

    private void startSniffer(Button start, Button stop, TextView status) {
        try {
            // Create log file in app's external files dir so it can be pulled via adb
            File dir = new File(ctx.getExternalFilesDir(null), "diag");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            snifferFile = new File(dir, "can_capture_" + ts + ".txt");
        } catch (Exception e) {
            Toast.makeText(ctx, "Cannot create log file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        snifferRunning = true;
        start.setEnabled(false);
        stop.setEnabled(true);
        status.setText("Starting…");

        snifferThread = new Thread(() -> {
            int frames = 0;
            try {
                manager.startSniffer();
                try (BufferedWriter w = new BufferedWriter(new FileWriter(snifferFile))) {
                    w.write("# E65 D-CAN capture " + new Date() + "\n");
                    w.write("# Format: <timestamp_ms> <CAN_ID> <data bytes>\n");
                    long start0 = System.currentTimeMillis();
                    while (snifferRunning) {
                        String line = manager.readSnifferLine(500);
                        if (line != null && !line.isEmpty() && !"BUFFER FULL".equals(line)) {
                            long t = System.currentTimeMillis() - start0;
                            w.write(t + " " + line + "\n");
                            frames++;
                            if (frames % 25 == 0) {
                                int f = frames;
                                ui.post(() -> status.setText("Captured " + f + " frames → " + snifferFile.getName()));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                final String msg = e.getMessage();
                ui.post(() -> status.setText("✗ Sniffer error: " + msg));
            } finally {
                int f = frames;
                ui.post(() -> {
                    status.setText("Stopped. Captured " + f + " frames → " + snifferFile.getName());
                    start.setEnabled(true);
                    stop.setEnabled(false);
                });
            }
        }, "CanSniffer");
        snifferThread.start();
    }

    private void stopSniffer(Button start, Button stop, TextView status) {
        snifferRunning = false;
        new Thread(() -> {
            try { manager.stopSniffer(); }
            catch (IOException e) {
                final String msg = e.getMessage();
                ui.post(() -> status.setText("Stop error: " + msg));
            }
        }, "CanSnifferStop").start();
    }

    private void stopSnifferIfRunning() {
        if (snifferRunning) {
            snifferRunning = false;
            try {
                if (manager != null) manager.stopSniffer();
            } catch (IOException ignored) {}
        }
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private boolean checkConnected() {
        if (manager == null || !manager.isConnected()) {
            Toast.makeText(ctx, "Connect to the car first", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }
}
