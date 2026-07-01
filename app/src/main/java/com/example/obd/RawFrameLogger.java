package com.example.obd;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Captures raw byte traffic between the app and the ELM327 to a file.
 *
 * Disabled by default — toggle via {@link #setEnabled(boolean)} (persisted in
 * SharedPreferences). Output goes to {@code obd-raw.log} in the app's external
 * files dir, retrievable with {@code adb pull}.
 *
 * Format (xxd-style):
 *   2026-06-26 14:00:01.123  TX  41 54 5A 0D                  | ATZ.
 *   2026-06-26 14:00:01.245  RX  45 4C 4D 33 32 37 0D 0D 3E   | ELM327..>
 *
 * TX/bulk-RX calls log immediately. Byte-at-a-time RX (SPP read loop) is
 * coalesced and flushed on '\r', '\n', '>', a 256-byte cap, or {@link #flushRx()}.
 */
public final class RawFrameLogger {

    public static final String RAW_FILENAME = "obd-raw.log";
    private static final String TAG = "RawFrameLogger";
    private static final String PREFS = "obd_prefs";
    private static final String KEY_ENABLED = "raw_capture_enabled";
    private static final long MAX_FILE_BYTES = 5_000_000L;
    private static final int RX_FLUSH_AT = 256;

    private static final RawFrameLogger INSTANCE = new RawFrameLogger();
    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private volatile File rawFile;
    private volatile boolean enabled = false;
    private SharedPreferences prefs;

    private final byte[] rxBuf = new byte[RX_FLUSH_AT];
    private int rxLen = 0;

    private RawFrameLogger() {}

    public static RawFrameLogger get() { return INSTANCE; }

    public synchronized void init(Context ctx) {
        if (rawFile != null) return;
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        rawFile = new File(dir, RAW_FILENAME);
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        enabled = prefs.getBoolean(KEY_ENABLED, false);
        if (enabled) write("---- raw capture resumed (pid=" + android.os.Process.myPid() + ") ----");
    }

    public synchronized void setEnabled(boolean on) {
        if (this.enabled == on) return;
        this.enabled = on;
        if (prefs != null) prefs.edit().putBoolean(KEY_ENABLED, on).apply();
        if (on) {
            write("---- raw capture enabled ----");
        } else {
            flushRxLocked();
            write("---- raw capture disabled ----");
        }
    }

    public boolean isEnabled() { return enabled; }

    public File getRawFile() { return rawFile; }

    /** Log a transmitted chunk. One line per call. */
    public synchronized void tx(byte[] data, int off, int len) {
        if (!enabled || data == null || len <= 0) return;
        flushRxLocked();
        write("TX  " + formatHex(data, off, len));
    }

    /** Log a bulk-received chunk (BLE notification, SPP {@code read(byte[])}). */
    public synchronized void rx(byte[] data, int off, int len) {
        if (!enabled || data == null || len <= 0) return;
        for (int i = 0; i < len; i++) appendRxByteLocked(data[off + i] & 0xFF);
    }

    /** Log a single received byte (SPP byte-by-byte read loop). */
    public synchronized void rxByte(int b) {
        if (!enabled || b < 0) return;
        appendRxByteLocked(b & 0xFF);
    }

    /** Flush any buffered RX bytes — call on disconnect to avoid trailing loss. */
    public synchronized void flushRx() { flushRxLocked(); }

    private void appendRxByteLocked(int b) {
        rxBuf[rxLen++] = (byte) b;
        boolean terminator = (b == '\r' || b == '\n' || b == '>');
        if (terminator || rxLen >= RX_FLUSH_AT) flushRxLocked();
    }

    private void flushRxLocked() {
        if (rxLen == 0) return;
        write("RX  " + formatHex(rxBuf, 0, rxLen));
        rxLen = 0;
    }

    private static String formatHex(byte[] data, int off, int len) {
        StringBuilder hex = new StringBuilder(len * 3);
        StringBuilder ascii = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int b = data[off + i] & 0xFF;
            if (i > 0) hex.append(' ');
            hex.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
        }
        return String.format(Locale.US, "%-60s | %s", hex, ascii);
    }

    private void write(String line) {
        File f = rawFile;
        if (f == null) return;
        try {
            if (f.length() > MAX_FILE_BYTES) {
                File rot = new File(f.getParentFile(), RAW_FILENAME + ".1");
                if (rot.exists()) rot.delete();
                f.renameTo(rot);
            }
            try (BufferedWriter w = new BufferedWriter(new FileWriter(f, true))) {
                w.write(TS_FMT.format(new Date()));
                w.write("  ");
                w.write(line);
                w.newLine();
            }
        } catch (IOException ioe) {
            Log.w(TAG, "raw write failed: " + ioe.getMessage());
        }
    }
}
