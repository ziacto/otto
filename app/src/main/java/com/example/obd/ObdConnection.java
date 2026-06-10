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
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class ObdConnection {
    private BluetoothSocket socket;
    private BufferedInputStream in;
    private OutputStream out;

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

        try {
            socket = device.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
            socket.connect();
        } catch (IOException e) {
            try {
                Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                socket = (BluetoothSocket) m.invoke(device, 1);
                socket.connect();
            } catch (Exception ex) {
                throw new IOException("OBD connection failed: " + ex.getMessage());
            }
        }

        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        initElm327();
    }

    /** ELM327 initialization sequence – must run after every connect. */
    private void initElm327() throws IOException {
        sendInit("ATZ");    // Reset
        pause(1500);        // Reset takes ~1–1.5s
        sendInit("ATE0");   // Echo off
        sendInit("ATL0");   // Linefeeds off
        sendInit("ATH0");   // Headers off
        sendInit("ATSP0");  // Auto-detect protocol
        // Adaptive Timing Mode 2: ELM automatically adjusts response timeouts.
        // Critical for slow/cheap clones – without this, slow ECUs return "NO DATA".
        sendInit("ATAT2");
        // Dummy query: triggers protocol detection now, not on the first real poll.
        // Response is discarded – "SEARCHING..." / "CONNECT" / data are all ignored.
        sendInit("0100");
        pause(2000);        // Protocol detection can take 2–3s on slow adapters
    }

    private void sendInit(String cmd) throws IOException {
        while (in.available() > 0) in.read(); // Flush buffer
        out.write((cmd + "\r").getBytes());
        out.flush();
        long deadline = System.currentTimeMillis() + 5000; // 5s – sufficient even for slow adapters
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                if (in.read() == '>') break; // Read byte immediately without sleeping
            } else {
                pause(20); // Only sleep if buffer is truly empty
            }
        }
    }

    private void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    public void disconnect() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
        in = null;
        out = null;
    }

    public BufferedInputStream getInput() { return in; }
    public OutputStream getOutput() { return out; }
}
