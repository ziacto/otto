package com.example.obd;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Set;

public class BluetoothHelper {

    private static final int REQ_PERMISSIONS = 1001;
    private static final String PREFS = "obd_prefs";
    private static final String KEY_LAST_MAC = "last_device_mac";

    private final Context context;
    private final ObdManagerFast obdManager;

    public BluetoothHelper(Context context, ObdManagerFast obdManager) {
        this.context = context;
        this.obdManager = obdManager;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Toast from any thread without casting context to Activity — the blind
     * (Activity) cast turned a successful connect into a swallowed
     * ClassCastException (logged as "connect failed") when the helper was
     * constructed with a non-Activity context.
     */
    private void postToast(String msg, int length) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Toast.makeText(context.getApplicationContext(), msg, length).show());
    }

    private void rememberDevice(BluetoothDevice device) {
        if (device != null) {
            prefs().edit().putString(KEY_LAST_MAC, device.getAddress()).apply();
        }
    }

    /** True if a connection attempt was started. False if no saved device, no permission, or BT off. */
    @SuppressLint("MissingPermission")
    public boolean tryAutoConnect() {
        if (obdManager.isConnected()) return false;

        String mac = prefs().getString(KEY_LAST_MAC, null);
        if (mac == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return false;

        BluetoothDevice match = null;
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            if (mac.equals(d.getAddress())) { match = d; break; }
        }
        if (match == null) return false;

        final BluetoothDevice target = match;
        ObdLogger.get().log(ObdLogger.Level.INFO, "Auto-connecting to " + mac);
        new Thread(() -> {
            try {
                obdManager.connect(target);
                ObdLogger.get().log(ObdLogger.Level.INFO, "Auto-connect OK");
                postToast("Auto-connected", Toast.LENGTH_SHORT);
            } catch (Exception e) {
                ObdLogger.get().log(ObdLogger.Level.ERROR, "Auto-connect failed: " + e.getMessage());
            }
        }, "ObdAutoConnect").start();
        return true;
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

    /**
     * "Clear Bluetooth cache" flow — after a stuck adapter or a stale RFCOMM link,
     * the vLinker sometimes stays in a half-open state where every new connect
     * fails with "read failed, socket might closed or timeout, read ret: -1".
     * Removing the bond forces the Android BT stack to drop all cached SDP
     * records and re-pair from scratch, which is the reliable way to recover
     * without a phone reboot.
     *
     * Steps:
     *   1. Close any live OBD connection.
     *   2. Reset the ELM link watchdog counters.
     *   3. removeBond() on the saved device via reflection (public API only
     *      exists on API 33+; reflection is stable and works on all supported
     *      Android versions).
     *   4. Forget the saved MAC in prefs so tryAutoConnect stops firing.
     *   5. Open system Bluetooth settings so the user can re-pair.
     */
    @SuppressLint("MissingPermission")
    public void clearBluetoothCache() {
        // 1. Tear down any live OBD session first.
        try {
            if (obdManager != null && obdManager.isConnected()) {
                obdManager.disconnect();
            }
        } catch (Exception ignored) {}

        String mac = prefs().getString(KEY_LAST_MAC, null);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        boolean unbonded = false;
        if (mac != null && adapter != null) {
            try {
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    if (mac.equals(d.getAddress())) {
                        try {
                            Method m = d.getClass().getMethod("removeBond");
                            Object r = m.invoke(d);
                            unbonded = Boolean.TRUE.equals(r);
                            ObdLogger.get().log(ObdLogger.Level.INFO,
                                    "removeBond(" + mac + ") returned " + r);
                        } catch (Exception e) {
                            ObdLogger.get().log(ObdLogger.Level.ERROR,
                                    "removeBond failed: " + e.getMessage());
                        }
                        break;
                    }
                }
            } catch (SecurityException se) {
                ObdLogger.get().log(ObdLogger.Level.ERROR,
                        "getBondedDevices denied: " + se.getMessage());
            }
        }

        // 4. Forget saved device so auto-reconnect stops until the user picks again.
        prefs().edit().remove(KEY_LAST_MAC).apply();
        ObdLogger.get().log(ObdLogger.Level.INFO, "Cleared BT cache (unbonded=" + unbonded + ")");

        String toastMsg = unbonded
                ? "Bluetooth cache cleared. Re-pair your OBD adapter in Bluetooth settings, then reopen the app."
                : "Saved OBD adapter forgotten. Open Bluetooth settings, unpair the OBD device, then pair again.";
        Toast.makeText(context, toastMsg, Toast.LENGTH_LONG).show();

        // 5. Bounce the user to system BT settings — one tap saves them the search.
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "Could not open BT settings: " + e.getMessage());
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
                            postToast("Connecting...", Toast.LENGTH_SHORT);
                            ObdLogger.get().log(ObdLogger.Level.INFO, "Connecting to " + device.getAddress());
                            obdManager.connect(device);
                            rememberDevice(device);
                            ObdLogger.get().log(ObdLogger.Level.INFO, "Connected to " + device.getAddress());
                            postToast("Connected", Toast.LENGTH_SHORT);
                        } catch (SecurityException se) {
                            ObdLogger.get().log(ObdLogger.Level.ERROR,
                                    "BT permission missing: " + se);
                            postToast("Bluetooth permission is missing", Toast.LENGTH_LONG);
                        } catch (IOException e) {
                            ObdLogger.get().log(ObdLogger.Level.ERROR,
                                    "Connect failed to " + device.getAddress() + ": " + e);
                            postToast("Connection failed: " + e.getMessage(), Toast.LENGTH_LONG);
                        }
                    }, "ObdManualConnect").start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}