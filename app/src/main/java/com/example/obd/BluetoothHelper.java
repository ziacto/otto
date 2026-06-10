package com.example.obd;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

public class BluetoothHelper {

    private static final int REQ_PERMISSIONS = 1001;
    private final Context context;
    private final ObdManagerFast obdManager;

    public BluetoothHelper(Context context, ObdManagerFast obdManager) {
        this.context = context;
        this.obdManager = obdManager;
    }

    public void requestNeededPermissions() {
        ArrayList<String> perms = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        ArrayList<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (ActivityCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }

        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                    (android.app.Activity) context,
                    toRequest.toArray(new String[0]),
                    REQ_PERMISSIONS
            );
        }
    }

    @SuppressLint("MissingPermission")
    public void showPairedDevicesAndConnect() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(context, "No Bluetooth available", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!adapter.isEnabled()) {
            Toast.makeText(context, "Please activate Bluetooth", Toast.LENGTH_LONG).show();
            return;
        }

        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            Toast.makeText(context, "No paired device found. Please pair your OBD adapter.", Toast.LENGTH_LONG).show();
            return;
        }

        final ArrayList<BluetoothDevice> list = new ArrayList<>(bonded);
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).getName() + "\n" + list.get(i).getAddress();
        }

        new AlertDialog.Builder(context)
                .setTitle("Choose OBD-Adapter")
                .setItems(names, (dialog, which) -> {
                    BluetoothDevice device = list.get(which);
                    new Thread(() -> {
                        try {
                            ((android.app.Activity) context).runOnUiThread(() ->
                                    Toast.makeText(context, "Connecting...", Toast.LENGTH_SHORT).show());
                            obdManager.connect(device);
                            ((android.app.Activity) context).runOnUiThread(() ->
                                    Toast.makeText(context, "Connected", Toast.LENGTH_SHORT).show());
                        } catch (SecurityException se) {
                            ((android.app.Activity) context).runOnUiThread(() ->
                                    Toast.makeText(context, "Bluetooth permission is missing", Toast.LENGTH_LONG).show());
                        } catch (IOException e) {
                            ((android.app.Activity) context).runOnUiThread(() ->
                                    Toast.makeText(context, "Connection failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}