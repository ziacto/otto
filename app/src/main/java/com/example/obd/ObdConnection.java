package com.example.obd;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class ObdConnection {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /**
     * Post-init health: HEALTHY means at least one ATSP probe got a positive 0100 reply;
     * FAILED means we connected to the adapter but the car never answered. Caller (UI)
     * uses this to show "Adapter connected but car not responding — check ignition"
     * instead of a misleading "Connected" pill.
     */
    public enum ConnectionHealth { UNKNOWN, HEALTHY, FAILED }

    private BluetoothSocket socket;       // null if BLE transport in use
    private BleObdTransport bleTransport; // null if classic SPP in use
    private SimulatedObdTransport simTransport; // set only in dry-run mode
    private BufferedInputStream in;
    private OutputStream out;
    private volatile ConnectionHealth health = ConnectionHealth.UNKNOWN;

    public ConnectionHealth getHealth() { return health; }

    public void connect(Context context, BluetoothDevice device) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Missing Bluetooth permission");
        }

        disconnect();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new IOException("Bluetooth not available");
        adapter.cancelDiscovery();

        int type = device.getType();
        ObdLogger.get().log(ObdLogger.Level.INFO,
                "Device type=" + describeType(type) + " name=" + safeName(device));

        // BLE-only adapters (vLinker BM+, OBDLink LX, generic BLE clones) cannot
        // accept RFCOMM. Skip straight to GATT.
        if (type == BluetoothDevice.DEVICE_TYPE_LE) {
            connectBle(context, device);
        } else if (type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            connectClassic(device);
        } else {
            // DUAL or UNKNOWN: prefer BLE — on dual-mode adapters (vLinker BM+) it has
            // lower per-command latency than RFCOMM and is more stable on Android 12+,
            // where the classic SPP stack has known dropouts. Fall back to classic if
            // the GATT path fails (e.g. user paired only as classic).
            try {
                connectBle(context, device);
            } catch (IOException bleErr) {
                ObdLogger.get().log(ObdLogger.Level.INFO,
                        "BLE failed (" + bleErr.getMessage() + "); falling back to classic SPP");
                connectClassic(device);
            }
        }

        initElm327();
        ObdLogger.get().log(ObdLogger.Level.INFO, "ELM327 init complete");
    }

    private void connectClassic(BluetoothDevice device) throws IOException {
        ObdLogger.get().log(ObdLogger.Level.INFO, "Opening RFCOMM SPP");
        // Verify device is actually bonded for classic — dual-mode adapters may be
        // BLE-paired but not classic-bonded. Connecting unbonded triggers the OS
        // pairing dialog which blocks our thread for ~12s.
        int bond = device.getBondState();
        ObdLogger.get().log(ObdLogger.Level.INFO,
                "Bond state: " + (bond == BluetoothDevice.BOND_BONDED ? "BONDED"
                        : (bond == BluetoothDevice.BOND_BONDING ? "BONDING" : "NONE")));
        BluetoothAdapter bondAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bondAdapter != null && bondAdapter.isDiscovering()) {
            bondAdapter.cancelDiscovery();
            pause(300);  // give the cancel time to propagate before SPP connect
        }
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
        } catch (IOException e) {
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "SPP UUID connect failed (" + e.getMessage() + "), trying ch1 reflection");
            try {
                Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                socket = (BluetoothSocket) m.invoke(device, 1);
                socket.connect();
            } catch (Exception ex) {
                throw new IOException("OBD connection failed: " + ex.getMessage());
            }
        }
        in = new BufferedInputStream(tapInput(socket.getInputStream()));
        out = tapOutput(socket.getOutputStream());
        ObdLogger.get().log(ObdLogger.Level.INFO, "RFCOMM socket open");
    }

    private void connectBle(Context context, BluetoothDevice device) throws IOException {
        bleTransport = new BleObdTransport();
        bleTransport.connect(context, device);
        in = bleTransport.getInput();
        out = bleTransport.getOutput();
    }

    /**
     * Dry-run connect — skips the entire Bluetooth stack and installs a
     * {@link SimulatedObdTransport} in place of an SPP/GATT socket. The rest of
     * the app (init, poll loop, parsers, UI) runs unchanged against fake
     * ELM327 responses. Useful for QA on a phone that isn't plugged into a car
     * and for the drawer's "Run Self-Test" action.
     */
    public void connectSimulator() throws IOException {
        disconnect();
        ObdLogger.get().log(ObdLogger.Level.INFO, "Simulator transport online");
        simTransport = new SimulatedObdTransport();
        simTransport.start();
        in = simTransport.getInput();
        out = simTransport.getOutput();
        initElm327();
        ObdLogger.get().log(ObdLogger.Level.INFO, "ELM327 init complete (simulator)");
    }

    /**
     * ELM327 initialization sequence – must run after every connect.
     *
     * BMW E65 730li (N52B30) engine ECU speaks ISO 15765-4 CAN 11-bit / 500 kbps
     * = ELM protocol 6. We try ATSP0 auto-detect first (works for most cars), and
     * if the ECU does not answer Mode 01 PID 00 we force ATSP6 explicitly. Some
     * cheap clones get stuck in "SEARCHING..." with ATSP0 and need the explicit
     * pick to commit. Adding 7 (CAN 29-bit / 500) as a last resort.
     */
    private void initElm327() throws IOException {
        // ATZ = hardware reset. The adapter takes 1-2s to come back. Loop until we
        // see "ELM" in the banner OR exhaust 4 attempts. We pause AFTER the first
        // failed attempt only, not after a successful one — saves up to ~2.4s on
        // healthy adapters.
        boolean atzOk = false;
        for (int i = 0; i < 4 && !atzOk; i++) {
            String resp = captureInit("ATZ");
            if (resp != null && (resp.toUpperCase().contains("ELM") || !resp.isEmpty())) {
                atzOk = true;
                break;
            }
            if (i < 3) pause(300);  // retry pause only between failed attempts
        }
        if (!atzOk) {
            ObdLogger.get().log(ObdLogger.Level.ERROR,
                    "ATZ never echoed — adapter may be dead or BLE notification pipe broken");
        }

        // Inter-command pauses keep cheap clones from dropping characters. 40ms is
        // tight but works for vLinker / OBDLink / quality clones; bump back to 80
        // if you see truncated init responses in the log on very cheap adapters.
        sendInit("ATE0"); pause(40);
        sendInit("ATL0"); pause(40);
        sendInit("ATH0"); pause(40);
        sendInit("ATCAF1"); pause(40);  // CAN auto-format on — ELM handles ISO-TP framing.
        sendInit("ATST 64"); pause(40); // request timeout = 100 × 4 ms = 400 ms
        // Adaptive timing 2: ELM auto-adjusts response timeouts – critical on slow clones.
        sendInit("ATAT2"); pause(40);

        if (probeProtocol("0")) {
            health = ConnectionHealth.HEALTHY;
        } else {
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "ATSP0 auto-detect did not respond to 0100; forcing ATSP6 (CAN 11/500) for BMW E65");
            if (probeProtocol("6")) {
                health = ConnectionHealth.HEALTHY;
            } else {
                ObdLogger.get().log(ObdLogger.Level.INFO,
                        "ATSP6 also failed; trying ATSP7 (CAN 29/500)");
                if (probeProtocol("7")) {
                    health = ConnectionHealth.HEALTHY;
                } else {
                    health = ConnectionHealth.FAILED;
                    ObdLogger.get().log(ObdLogger.Level.ERROR,
                            "Protocol detection failed on ATSP0/6/7 — polling will likely return NO DATA");
                }
            }
        }
        // Log the protocol the ELM finally settled on (e.g. "ISO 15765-4 (CAN 11/500)")
        captureInit("ATDPN");
    }

    /**
     * Set protocol via ATSP&lt;p&gt; and probe Mode 01 PID 00. true if the ECU answered.
     * Only pauses 1500ms if the probe failed — on a healthy adapter that answers
     * immediately, we skip the wait entirely. Saves up to 3s vs. the old behaviour.
     */
    private boolean probeProtocol(String p) throws IOException {
        captureInit("ATSP" + p);
        String resp = captureInit("0100");
        boolean ok = isPositiveMode01(resp);
        if (!ok) pause(500); // shorter settle before next probe; only on failure
        ObdLogger.get().log(ObdLogger.Level.INFO,
                "Probe ATSP" + p + " -> " + (ok ? "OK" : "no-data"));
        return ok;
    }

    private static boolean isPositiveMode01(String raw) {
        if (raw == null) return false;
        String s = raw.toUpperCase().replaceAll("\\s+", "");
        // If the ECU answered Mode 01 PID 00 (4100...), accept it regardless of
        // a leading "SEARCHING..." status string emitted by the ELM during auto-
        // detect — that's exactly the response shape on the BMW E65 first probe.
        if (s.contains("4100")) return true;
        return false;
    }

    private void sendInit(String cmd) throws IOException {
        captureInit(cmd); // single path; response captured for the log even when ignored
    }

    /** Send one init command and return the raw response (up to but not including '>'). */
    private String captureInit(String cmd) throws IOException {
        // Local refs so a concurrent disconnect() that nulls the fields mid-loop
        // surfaces as IOException ("stream closed") instead of NPE inside the
        // 5s read deadline.
        BufferedInputStream localIn = in;
        OutputStream localOut = out;
        if (localIn == null || localOut == null) {
            throw new IOException("Streams not initialized");
        }
        ObdLogger.get().log(ObdLogger.Level.TX, "init> " + cmd);
        while (localIn.available() > 0) localIn.read();
        localOut.write((cmd + "\r").getBytes());
        localOut.flush();
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (localIn.available() > 0) {
                int c = localIn.read();
                if (c == -1 || c == '>') break;
                sb.append((char) c);
            } else {
                pause(20);
            }
        }
        String resp = sb.toString().trim();
        ObdLogger.get().log(ObdLogger.Level.RX, "init< " + resp);
        return resp;
    }

    private void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public boolean isConnected() {
        if (socket != null) return socket.isConnected();
        if (bleTransport != null) return bleTransport.isConnected();
        if (simTransport != null) return simTransport.isConnected();
        return false;
    }

    /**
     * Milliseconds since the BLE adapter last delivered notification bytes.
     * Returns -1 for classic SPP (no notification concept) and for BLE that
     * has not yet received its first frame.
     */
    public long bleMsSinceLastRx() {
        return bleTransport == null ? -1 : bleTransport.msSinceLastNotify();
    }

    /**
     * Reset the BLE notification watchdog to "just now".
     * No-op for SPP connections. Call before resuming polling after any pause
     * (one-shot command, module scan) to avoid false stale-notify trips.
     */
    public void resetBleWatchdog() {
        if (bleTransport != null) bleTransport.resetNotifyWatchdog();
    }

    public void disconnect() {
        RawFrameLogger.get().flushRx();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        if (bleTransport != null) bleTransport.disconnect();
        if (simTransport != null) simTransport.disconnect();
        socket = null;
        bleTransport = null;
        simTransport = null;
        in = null;
        out = null;
    }

    /** Pass-through InputStream that taps every byte into {@link RawFrameLogger}. */
    private static InputStream tapInput(final InputStream src) {
        return new InputStream() {
            @Override public int read() throws IOException {
                int b = src.read();
                if (b >= 0) RawFrameLogger.get().rxByte(b);
                return b;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = src.read(b, off, len);
                if (n > 0) RawFrameLogger.get().rx(b, off, n);
                return n;
            }
            @Override public int available() throws IOException { return src.available(); }
            @Override public void close() throws IOException { src.close(); }
        };
    }

    /** Pass-through OutputStream that taps every write into {@link RawFrameLogger}. */
    private static OutputStream tapOutput(final OutputStream dst) {
        return new OutputStream() {
            @Override public void write(int b) throws IOException {
                dst.write(b);
                byte[] one = { (byte) b };
                RawFrameLogger.get().tx(one, 0, 1);
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                dst.write(b, off, len);
                RawFrameLogger.get().tx(b, off, len);
            }
            @Override public void flush() throws IOException { dst.flush(); }
            @Override public void close() throws IOException { dst.close(); }
        };
    }

    public BufferedInputStream getInput() { return in; }
    public OutputStream getOutput() { return out; }

    private static String describeType(int t) {
        switch (t) {
            case BluetoothDevice.DEVICE_TYPE_CLASSIC: return "CLASSIC";
            case BluetoothDevice.DEVICE_TYPE_LE:      return "LE";
            case BluetoothDevice.DEVICE_TYPE_DUAL:    return "DUAL";
            default:                                  return "UNKNOWN";
        }
    }

    private static String safeName(BluetoothDevice d) {
        try { return d.getName() == null ? "(null)" : d.getName(); }
        catch (SecurityException e) { return "(no-perm)"; }
    }
}
