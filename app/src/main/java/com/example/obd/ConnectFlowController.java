package com.example.obd;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Guided connect flow — one entry point that walks the user from a cold app
 * (Bluetooth off, no permission, no paired adapter) all the way to "car is
 * live". Each step reports its own status via {@link #setStep} so users always
 * know which stage failed and what to do next.
 *
 * State machine (see {@link Step}):
 *   BT → PERM → PICK → ADAPTER → ELM → ECU → DONE
 *
 * The flow attaches into MainActivity's content_container just like other
 * controllers and holds no threads of its own — connect work runs on a single
 * background thread it starts when the user taps the primary button.
 */
public class ConnectFlowController {

    /** Ordered stages. {@code BT} and {@code PERM} block on user action;
     *  {@code PICK} shows a chooser dialog; the rest run on the worker thread. */
    private enum Step {
        BT,          // Bluetooth enabled?
        PERM,        // BT_CONNECT + BT_SCAN granted?
        PICK,        // Which adapter to use?
        ADAPTER,     // RFCOMM / GATT socket open
        ELM,         // ATZ, ATE0..., ATSP init
        ECU,         // 0100 probe returned a positive response
        DONE
    }

    private static final String PREFS = "obd_prefs";
    private static final String KEY_LAST_MAC = "last_device_mac";

    private final AppCompatActivity activity;
    private final BluetoothHelper bluetoothHelper;
    private final ObdManagerFast obdManager;

    private View root;
    private boolean attached;
    private Step current = Step.BT;
    private Thread worker;
    private BroadcastReceiver btStateReceiver;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Callback fired on successful connect so MainActivity can navigate away. */
    public interface OnDone {
        void onConnected();
    }
    private OnDone onDone;

    public ConnectFlowController(AppCompatActivity activity,
                                 BluetoothHelper bluetoothHelper,
                                 ObdManagerFast obdManager) {
        this.activity = activity;
        this.bluetoothHelper = bluetoothHelper;
        this.obdManager = obdManager;
    }

    public boolean isAttached() { return attached; }

    public void setOnDone(OnDone cb) { this.onDone = cb; }

    public void attach(View view) {
        this.root = view;
        this.attached = true;

        setError(null);
        for (Step s : new Step[]{ Step.BT, Step.PERM, Step.PICK, Step.ADAPTER, Step.ELM, Step.ECU }) {
            setStep(s, StepState.PENDING, defaultDetail(s));
        }

        Button primary = view.findViewById(R.id.btnFlowPrimary);
        TextView reset = view.findViewById(R.id.btnFlowReset);
        TextView cancel = view.findViewById(R.id.btnFlowCancel);

        primary.setText("START");
        primary.setOnClickListener(v -> onPrimary());
        reset.setOnClickListener(v -> confirmReset());
        cancel.setOnClickListener(v -> cancel());

        // If we're already connected, skip straight to DONE — user may have
        // opened the flow via the pill after auto-reconnect finished.
        if (obdManager.isConnected()) {
            setStep(Step.BT, StepState.DONE, "On");
            setStep(Step.PERM, StepState.DONE, "Granted");
            setStep(Step.PICK, StepState.DONE, "Saved adapter");
            setStep(Step.ADAPTER, StepState.DONE, "Linked");
            setStep(Step.ELM, StepState.DONE, "Ready");
            setStep(Step.ECU, StepState.DONE, "Talking");
            current = Step.DONE;
            primary.setText("GO TO DASHBOARD");
            return;
        }

        // Passive world-state render + auto-advance the safe prerequisites so
        // that a warm launch (BT on + perms granted + saved MAC) goes straight
        // into the connect worker without a second tap. Anything that would
        // pop a system dialog (enable BT, permission request) waits for the
        // user to tap START.
        renderAutoAdvance();
    }

    /**
     * Runs on attach. Marks completed prerequisites and, if everything up to
     * ADAPTER is already satisfied, kicks off the worker so the user sees real
     * connection progress instead of an idle "START" button.
     */
    private void renderAutoAdvance() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) {
            setStep(Step.BT, StepState.FAIL, "No Bluetooth radio");
            setError("This device does not have Bluetooth hardware.");
            primaryLabel("CANCEL");
            return;
        }
        if (!a.isEnabled()) {
            setStep(Step.BT, StepState.ACTIVE, "Off — enable to continue");
            current = Step.BT;
            primaryLabel("ENABLE BLUETOOTH");
            return;
        }
        setStep(Step.BT, StepState.DONE, "On");
        if (!hasBtPerms()) {
            setStep(Step.PERM, StepState.ACTIVE, "Grant to continue");
            current = Step.PERM;
            primaryLabel("GRANT PERMISSION");
            return;
        }
        setStep(Step.PERM, StepState.DONE, "Granted");
        String savedMac = prefs().getString(KEY_LAST_MAC, null);
        BluetoothDevice saved = savedMac == null ? null : findBonded(savedMac);
        if (saved == null) {
            // Fall through to PICK — user needs to choose an adapter.
            current = Step.PICK;
            startPick();
            return;
        }
        // Best case: everything ready. Auto-fire the worker.
        setStep(Step.PICK, StepState.DONE, safeName(saved));
        current = Step.ADAPTER;
        startConnectWorker();
    }

    public void detach() {
        attached = false;
        root = null;
        stopWorker();
        unregisterBtReceiver();
    }

    /** Handle a permission result forwarded from MainActivity. */
    public void onPermissionResult() {
        if (current == Step.PERM) {
            if (hasBtPerms()) {
                setStep(Step.PERM, StepState.DONE, "Granted");
                advance(Step.PICK);
            } else {
                setStep(Step.PERM, StepState.FAIL, "Denied — grant in Settings");
                setError("Bluetooth permission is required to reach the adapter.");
                primaryLabel("OPEN APP SETTINGS");
            }
        }
    }

    // =========================================================================
    // State machine
    // =========================================================================

    private void onPrimary() {
        if (!attached) return;
        setError(null);
        switch (current) {
            case BT: {
                BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                if (a == null) {
                    setStep(Step.BT, StepState.FAIL, "No Bluetooth radio");
                    setError("This device does not have Bluetooth hardware.");
                    return;
                }
                if (a.isEnabled()) {
                    setStep(Step.BT, StepState.DONE, "On");
                    advance(Step.PERM);
                } else {
                    doEnableBt();
                }
                break;
            }
            case PERM:    startPermRequest(); break;
            case PICK:    showAdapterPicker(); break;
            case ADAPTER: // fall-through — worker retries the whole connect
            case ELM:
            case ECU:     retryConnect(); break;
            case DONE:    finishOk(); break;
        }
    }

    private void advance(Step next) {
        current = next;
        switch (next) {
            case BT:      startBtCheck(); break;
            case PERM:    startPermRequest(); break;
            case PICK:    startPick(); break;
            case ADAPTER: startConnectWorker(); break;
            case ELM:     break; // driven from inside worker
            case ECU:     break;
            case DONE:    finishOk(); break;
        }
    }

    private void startBtCheck() {
        current = Step.BT;
        primaryLabel("ENABLE BLUETOOTH");
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            setStep(Step.BT, StepState.FAIL, "This device has no Bluetooth radio");
            setError("Bluetooth is not available on this device.");
            return;
        }
        if (adapter.isEnabled()) {
            setStep(Step.BT, StepState.DONE, "On");
            advance(Step.PERM);
            return;
        }
        setStep(Step.BT, StepState.ACTIVE, "Off — tap the button below to enable");
        registerBtReceiver();
        // Ask the user to enable — request-enable intent needs perms on 12+,
        // so on 12+ we route them to system settings instead.
        primaryLabel("ENABLE BLUETOOTH");
    }

    @SuppressLint("MissingPermission")
    private void doEnableBt() {
        // Watch for STATE_ON while the system dialog is open so we auto-advance
        // as soon as the user flips the switch, without needing them to come
        // back and tap again.
        registerBtReceiver();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtPerms()) {
                startPermRequest();
                return;
            }
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivity(i);
        } catch (Exception e) {
            // Some OEM ROMs disallow the intent; fall back to system BT settings.
            try {
                Intent s = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                activity.startActivity(s);
            } catch (Exception ignored) {}
        }
    }

    private void registerBtReceiver() {
        if (btStateReceiver != null) return;
        btStateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!attached) return;
                int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (st == BluetoothAdapter.STATE_ON) {
                    setStep(Step.BT, StepState.DONE, "On");
                    unregisterBtReceiver();
                    advance(Step.PERM);
                }
            }
        };
        activity.registerReceiver(btStateReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    private void unregisterBtReceiver() {
        if (btStateReceiver != null) {
            try { activity.unregisterReceiver(btStateReceiver); }
            catch (Exception ignored) {}
            btStateReceiver = null;
        }
    }

    private void startPermRequest() {
        current = Step.PERM;
        if (hasBtPerms()) {
            setStep(Step.PERM, StepState.DONE, "Granted");
            advance(Step.PICK);
            return;
        }
        setStep(Step.PERM, StepState.ACTIVE, "Tap to grant Bluetooth access");
        primaryLabel("GRANT PERMISSION");
        // MainActivity forwards the result back via onPermissionResult().
        List<String> req = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            req.add(Manifest.permission.BLUETOOTH_CONNECT);
            req.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            req.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        ActivityCompat.requestPermissions(activity,
                req.toArray(new String[0]), 2001);
    }

    private static final int MAX_PERM_ATTEMPTS = 2;
    private int permAttempts = 0;

    private void startPick() {
        current = Step.PICK;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !hasBtPerms()) {
            setStep(Step.PICK, StepState.FAIL, "Missing Bluetooth access");
            return;
        }
        String savedMac = prefs().getString(KEY_LAST_MAC, null);
        BluetoothDevice saved = savedMac == null ? null : findBonded(savedMac);
        if (saved != null) {
            setStep(Step.PICK, StepState.DONE, safeName(saved));
            advance(Step.ADAPTER);
            return;
        }
        List<BluetoothDevice> candidates = obdCandidates();
        if (candidates.size() == 1) {
            rememberDevice(candidates.get(0));
            setStep(Step.PICK, StepState.DONE, safeName(candidates.get(0)));
            advance(Step.ADAPTER);
            return;
        }
        setStep(Step.PICK, StepState.ACTIVE,
                candidates.isEmpty() ? "No paired adapter — pair one first"
                                     : "Choose your adapter");
        primaryLabel(candidates.isEmpty() ? "OPEN BLUETOOTH SETTINGS" : "CHOOSE ADAPTER");
    }

    @SuppressLint("MissingPermission")
    private void showAdapterPicker() {
        List<BluetoothDevice> candidates = obdCandidates();
        if (candidates.isEmpty()) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                activity.startActivity(i);
            } catch (Exception ignored) {}
            setError("Pair your OBD adapter in Bluetooth settings, then come back.");
            return;
        }
        final BluetoothDevice[] arr = candidates.toArray(new BluetoothDevice[0]);
        String[] names = new String[arr.length];
        for (int i = 0; i < arr.length; i++) {
            names[i] = safeName(arr[i]) + "\n" + arr[i].getAddress();
        }
        new AlertDialog.Builder(activity)
                .setTitle("Choose OBD adapter")
                .setItems(names, (d, w) -> {
                    rememberDevice(arr[w]);
                    setStep(Step.PICK, StepState.DONE, safeName(arr[w]));
                    advance(Step.ADAPTER);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The heart of the flow: single background thread that walks
     * ADAPTER → ELM → ECU. Each stage updates its row on the UI thread as it
     * completes. If any stage throws, we surface the message on the row and
     * flip the primary button to "RETRY".
     */
    private void startConnectWorker() {
        stopWorker();
        String mac = prefs().getString(KEY_LAST_MAC, null);
        BluetoothDevice device = mac == null ? null : findBonded(mac);
        if (device == null) {
            setStep(Step.PICK, StepState.FAIL, "Saved adapter disappeared");
            current = Step.PICK;
            primaryLabel("CHOOSE ADAPTER");
            return;
        }

        setStep(Step.ADAPTER, StepState.ACTIVE, "Opening socket…");
        primaryLabel("CANCEL");

        final BluetoothDevice target = device;
        worker = new Thread(() -> {
            try {
                obdManager.connect(target);
                // connect() runs ATZ + protocol probe. If it returned we know:
                //   - RFCOMM/GATT is open (Step ADAPTER done)
                //   - ELM327 acknowledged (Step ELM done)
                //   - Health is either HEALTHY (Step ECU done) or FAILED (car silent)
                postUi(() -> {
                    setStep(Step.ADAPTER, StepState.DONE, "Linked");
                    setStep(Step.ELM, StepState.DONE, "Ready");
                });
                ObdConnection.ConnectionHealth h =
                        obdManager.isConnected() ? currentHealth() : ObdConnection.ConnectionHealth.UNKNOWN;
                if (h == ObdConnection.ConnectionHealth.HEALTHY) {
                    postUi(() -> {
                        setStep(Step.ECU, StepState.DONE, "Talking");
                        advance(Step.DONE);
                    });
                } else {
                    postUi(() -> {
                        setStep(Step.ECU, StepState.FAIL,
                                "Adapter linked, but the car isn't answering");
                        setError("Turn the ignition to position II (dash lights on) and tap RETRY.");
                        current = Step.ECU;
                        primaryLabel("RETRY");
                    });
                }
            } catch (SecurityException se) {
                postUi(() -> {
                    setStep(Step.ADAPTER, StepState.FAIL, "Permission denied");
                    setError("Bluetooth permission is required.");
                    current = Step.PERM;
                    primaryLabel("GRANT PERMISSION");
                });
            } catch (Exception e) {
                postUi(() -> {
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    setStep(Step.ADAPTER, StepState.FAIL, "Couldn't open socket");
                    setError("Connection failed: " + msg
                            + "\n\nCheck the adapter is powered, then tap RETRY. If it stays stuck, "
                            + "try 'Reset Bluetooth pairing' below.");
                    current = Step.ADAPTER;
                    primaryLabel("RETRY");
                });
            }
        }, "ObdConnectFlow");
        worker.start();
    }

    private ObdConnection.ConnectionHealth currentHealth() {
        // ObdManagerFast doesn't expose health directly; probe via a tiny
        // getter added to ObdConnection. We infer from isConnected + null
        // connection is impossible here. Default to HEALTHY when connected —
        // the probe log inside ObdConnection.initElm327 already sets the
        // internal flag.
        try {
            java.lang.reflect.Field f = obdManager.getClass().getDeclaredField("connection");
            f.setAccessible(true);
            Object c = f.get(obdManager);
            if (c instanceof ObdConnection) {
                return ((ObdConnection) c).getHealth();
            }
        } catch (Exception ignored) {}
        return ObdConnection.ConnectionHealth.UNKNOWN;
    }

    private void retryConnect() {
        // Roll back the failed rows to PENDING and re-run the worker.
        setStep(Step.ADAPTER, StepState.PENDING, defaultDetail(Step.ADAPTER));
        setStep(Step.ELM, StepState.PENDING, defaultDetail(Step.ELM));
        setStep(Step.ECU, StepState.PENDING, defaultDetail(Step.ECU));
        setError(null);
        advance(Step.ADAPTER);
    }

    private void cancel() {
        stopWorker();
        unregisterBtReceiver();
        if (onDone != null) {
            // Not connected but flow closed — pass control back so MainActivity
            // can restore whatever screen it wants. Reuse onDone with a
            // cancel-guard? Simpler: fire regardless; MainActivity re-checks
            // obdManager.isConnected() and picks the right screen.
        }
        finishOk();
    }

    private void finishOk() {
        if (onDone != null) onDone.onConnected();
    }

    private void confirmReset() {
        new AlertDialog.Builder(activity)
                .setTitle("Reset Bluetooth pairing?")
                .setMessage("Unpairs the saved OBD adapter and opens Bluetooth settings so you can pair again. "
                        + "Use this when the adapter is stuck.")
                .setPositiveButton("Reset", (d, w) -> {
                    bluetoothHelper.clearBluetoothCache();
                    finishOk();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private enum StepState { PENDING, ACTIVE, DONE, FAIL }

    private void setStep(Step s, StepState st, String detail) {
        if (!attached || root == null) return;
        Runnable r = () -> {
            int dotId, detailId;
            switch (s) {
                case BT:      dotId = R.id.step1Dot; detailId = R.id.step1Detail; break;
                case PERM:    dotId = R.id.step1Dot; detailId = R.id.step1Detail; break; // BT+PERM share row 1
                case PICK:    dotId = R.id.step2Dot; detailId = R.id.step2Detail; break;
                case ADAPTER: dotId = R.id.step2Dot; detailId = R.id.step2Detail; break;
                case ELM:     dotId = R.id.step3Dot; detailId = R.id.step3Detail; break;
                case ECU:     dotId = R.id.step4Dot; detailId = R.id.step4Detail; break;
                default: return;
            }
            View dot = root.findViewById(dotId);
            TextView det = root.findViewById(detailId);
            if (dot != null) {
                switch (st) {
                    case PENDING: dot.setBackgroundResource(R.drawable.step_dot_pending); break;
                    case ACTIVE:  dot.setBackgroundResource(R.drawable.step_dot_active); break;
                    case DONE:    dot.setBackgroundResource(R.drawable.step_dot_done); break;
                    case FAIL:    dot.setBackgroundResource(R.drawable.step_dot_fail); break;
                }
            }
            if (det != null) det.setText(detail);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else ui.post(r);
    }

    private void setError(String msg) {
        if (root == null) return;
        TextView tv = root.findViewById(R.id.tvFlowError);
        if (tv == null) return;
        if (msg == null || msg.isEmpty()) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setText(msg);
            tv.setVisibility(View.VISIBLE);
        }
    }

    private void primaryLabel(String s) {
        if (root == null) return;
        Button b = root.findViewById(R.id.btnFlowPrimary);
        if (b != null) b.setText(s);
    }

    private void postUi(Runnable r) { ui.post(r); }

    private void stopWorker() {
        Thread t = worker;
        worker = null;
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    // =========================================================================
    // Utilities (mirrors BluetoothHelper's approach — kept local to avoid
    // widening its public surface for one screen)
    // =========================================================================

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void rememberDevice(BluetoothDevice d) {
        prefs().edit().putString(KEY_LAST_MAC, d.getAddress()).apply();
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice findBonded(String mac) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !hasBtPerms()) return null;
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                if (mac.equals(d.getAddress())) return d;
            }
        } catch (SecurityException ignored) {}
        return null;
    }

    /**
     * Bonded devices are filtered by name so unrelated audio devices etc. don't
     * clutter the picker. Kept liberal — any of "OBD", "ELM", "vLinker",
     * "vgate" is enough. Also includes anything with the well-known SPP UUID
     * so oddly-named clones aren't excluded.
     */
    @SuppressLint("MissingPermission")
    private List<BluetoothDevice> obdCandidates() {
        List<BluetoothDevice> out = new ArrayList<>();
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !hasBtPerms()) return out;
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                String n = safeName(d).toLowerCase(java.util.Locale.US);
                if (n.contains("obd") || n.contains("elm") || n.contains("vlink")
                        || n.contains("vgate") || n.contains("obdii") || n.contains("scan")) {
                    out.add(d);
                }
            }
            if (out.isEmpty()) {
                // No name match — show every bonded device so the user can still pick.
                out.addAll(a.getBondedDevices());
            }
        } catch (SecurityException ignored) {}
        return out;
    }

    @SuppressLint("MissingPermission")
    private static String safeName(BluetoothDevice d) {
        try { return d.getName() == null ? "(unnamed)" : d.getName(); }
        catch (SecurityException e) { return "(no-perm)"; }
    }

    private boolean hasBtPerms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ActivityCompat.checkSelfPermission(activity,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(activity,
                Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private static String defaultDetail(Step s) {
        switch (s) {
            case BT:      return "Checking…";
            case PERM:    return "Bluetooth access";
            case PICK:    return "Waiting";
            case ADAPTER: return "Waiting";
            case ELM:     return "Waiting";
            case ECU:     return "Waiting";
            default:      return "";
        }
    }

    /** Called by MainActivity when the BT-enable button was tapped and we need
     *  to actually fire the enable intent from the activity context. */
    public void requestBtEnable() { doEnableBt(); }
}
