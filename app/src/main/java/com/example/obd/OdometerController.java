package com.example.obd;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

/**
 * Odometer screen — three independent distance sources, each with its own card.
 *
 * 1. App-tracked: from {@link OdometerTracker}, integrated from speed samples.
 * 2. Mode 01 PID 0x31: live OBD read, distance since last DTC clear.
 * 3. BMW UDS DME read: experimental, may not respond on all DME variants.
 */
public class OdometerController {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private View root;
    private Context ctx;
    private ObdManagerFast manager;

    public void attach(View view, ObdManagerFast manager) {
        this.root = view;
        this.ctx = view.getContext();
        this.manager = manager;

        TextView appTracked = view.findViewById(R.id.odoAppTracked);
        appTracked.setText(formatKm(OdometerTracker.get(ctx).getTotalKm()));

        Button btnReset = view.findViewById(R.id.btnOdoReset);
        btnReset.setOnClickListener(v -> confirmReset(appTracked));

        Button btnSeed = view.findViewById(R.id.btnOdoSeed);
        btnSeed.setOnClickListener(v -> promptSeed(appTracked));

        Button btnRefreshSc = view.findViewById(R.id.btnOdoRefreshSc);
        btnRefreshSc.setOnClickListener(v -> readSinceCleared());

        Button btnRefreshUds = view.findViewById(R.id.btnOdoRefreshUds);
        btnRefreshUds.setOnClickListener(v -> readBmwUdsOdometer());
    }

    public void detach() {
        root = null;
        ctx = null;
        manager = null;
    }

    /** Refresh the app-tracked display from prefs — call when this screen becomes visible. */
    public void refreshAppTracked() {
        if (root == null) return;
        TextView tv = root.findViewById(R.id.odoAppTracked);
        if (tv != null) tv.setText(formatKm(OdometerTracker.get(ctx).getTotalKm()));
    }

    private void confirmReset(TextView display) {
        new AlertDialog.Builder(ctx)
                .setTitle("Reset app-tracked distance?")
                .setMessage("Zeroes the kilometres the app has integrated since you first connected. Cannot be undone.")
                .setPositiveButton("Reset", (d, w) -> {
                    OdometerTracker.get(ctx).reset();
                    display.setText(formatKm(0));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptSeed(TextView display) {
        final EditText input = new EditText(ctx);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("e.g. 185000");
        new AlertDialog.Builder(ctx)
                .setTitle("Seed from cluster")
                .setMessage("Type the kilometres shown on your cluster odometer right now. The app-tracked counter will start from that value and add up from here.")
                .setView(input)
                .setPositiveButton("Set", (d, w) -> {
                    try {
                        double km = Double.parseDouble(input.getText().toString().trim());
                        OdometerTracker.get(ctx).setTotalKm(km);
                        display.setText(formatKm(km));
                    } catch (NumberFormatException e) {
                        Toast.makeText(ctx, "Invalid number", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void readSinceCleared() {
        if (!checkConnected()) return;
        TextView out = root.findViewById(R.id.odoSinceCleared);
        out.setText("Reading…");
        new Thread(() -> {
            String result;
            try {
                DistanceSinceClearedCommand cmd = new DistanceSinceClearedCommand();
                // Borrow the connection — pause polling, run one-shot read
                double km = manager.runOneShot(cmd);
                result = formatKm(km);
            } catch (Exception e) {
                result = "Not supported / read failed";
            }
            String finalResult = result;
            ui.post(() -> out.setText(finalResult));
        }, "OdoReadSc").start();
    }

    private void readBmwUdsOdometer() {
        if (!checkConnected()) return;
        TextView out = root.findViewById(R.id.odoUds);
        out.setText("Trying DID 0xA001…");
        new Thread(() -> {
            String result;
            try {
                Double km = manager.readUdsRawNumeric(BmwModule.DME, 0xA001, 4);
                if (km == null) result = "No response from DME (DID 0xA001 not supported on this variant)";
                else result = String.format(Locale.US, "%,.0f km", km);
            } catch (Exception e) {
                result = "Error: " + e.getMessage();
            }
            String finalResult = result;
            ui.post(() -> out.setText(finalResult));
        }, "OdoReadUds").start();
    }

    private boolean checkConnected() {
        if (manager == null || !manager.isConnected()) {
            Toast.makeText(ctx, "Connect to the car first", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private static String formatKm(double km) {
        return String.format(Locale.US, "%,.0f km", km);
    }
}
