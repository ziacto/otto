package com.example.obd;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GATT-based transport for BLE-only ELM327 clones (vLinker BM+, OBDLink LX, etc).
 *
 * Exposes the same BufferedInputStream / OutputStream pair as the classic SPP
 * {@link ObdConnection} so the rest of the app (init sequence, poll loop,
 * one-shot commands) does not need to know which transport is in use.
 *
 * BLE notifications from the adapter are enqueued into a blocking queue and
 * drained by a queue-backed InputStream. A PipedInputStream is deliberately
 * NOT used here: it binds itself to the last reader thread, and the manager
 * kills/recreates the poll thread on every one-shot — a notification arriving
 * in that window used to hit a dead reader and get silently dropped ("Pipe
 * broken"), corrupting the next read. Outbound bytes are chunked to the
 * negotiated MTU and written to the adapter's write characteristic.
 */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation") // pre-API-33 GATT setValue/writeCharacteristic — kept for minSdk 24
public class BleObdTransport {

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Known ELM327-over-BLE service UUIDs. Order = preference.
    // FFF0/FFF1/FFF2 covers vLinker BM+ and most generic clones.
    // FFE0/FFE1 covers HC-08-style adapters.
    // FFE5 covers HM-10 compatibility mode (some vLinker firmwares fall back to this).
    private static final UUID[] CANDIDATE_SERVICES = {
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb"),
    };

    private static final int BLE_CHUNK_SIZE = 20; // safe default before MTU negotiation

    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic writeChar;
    private volatile BluetoothGattCharacteristic notifyChar;

    // Thread-agnostic rx path: GATT callback thread offers chunks (never blocks),
    // any reader thread drains them. queuedBytes tracks unread bytes across the
    // queue plus the partially-consumed head chunk so available() stays exact.
    private final LinkedBlockingQueue<byte[]> rxQueue = new LinkedBlockingQueue<>(512);
    private final AtomicInteger queuedBytes = new AtomicInteger(0);
    private final AtomicBoolean discoveryStarted = new AtomicBoolean(false);
    private volatile int chunkSize = BLE_CHUNK_SIZE;
    private BufferedInputStream bufIn;
    private OutputStream txOut;

    private final Object writeLock = new Object();
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final CountDownLatch servicesLatch = new CountDownLatch(1);
    private volatile CountDownLatch writeLatch;
    private volatile CountDownLatch cccdLatch;

    private volatile boolean connected = false;
    private volatile boolean closed = false;
    private volatile IOException firstError;
    private volatile long lastNotifyAtMs = 0L;
    private volatile CountDownLatch mtuLatch;

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "BLE state=" + newState + " status=" + status);
            // A disconnect must clear `connected` regardless of status — link
            // loss arrives as status 8/133 with STATE_DISCONNECTED, and the old
            // early-return on bad status left isConnected() reporting true
            // forever after the link died.
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                firstError = new IOException("GATT status=" + status);
                connectedLatch.countDown();
                servicesLatch.countDown();
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                // SEQUENCED GATT OPS (Plugin.BLE / Nordic-BLE pattern):
                // 1. Request MTU first
                // 2. Wait for onMtuChanged
                // 3. Then call discoverServices
                // Running them concurrently is the #1 cause of GATT 133 failures.
                mtuLatch = new CountDownLatch(1);
                if (!g.requestMtu(247)) {
                    // MTU request rejected — skip the wait and go straight to discovery
                    mtuLatch.countDown();
                }
                connectedLatch.countDown();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "BLE MTU=" + mtu + " status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) {
                // Use the negotiated MTU for outbound chunking (ATT header = 3
                // bytes). We used to request 247 and then still chunk at 20.
                chunkSize = mtu - 3;
            }
            // Now safe to discover services. CAS-guarded because connect() has
            // a fallback that starts discovery if this callback never fires
            // (some stacks skip onMtuChanged when the MTU was already
            // negotiated) — only one of the two paths may call it.
            if (discoveryStarted.compareAndSet(false, true)) {
                if (!g.discoverServices()) {
                    firstError = new IOException("discoverServices() rejected");
                    servicesLatch.countDown();
                }
            }
            CountDownLatch l = mtuLatch;
            if (l != null) l.countDown();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                firstError = new IOException("service discovery status=" + status);
                servicesLatch.countDown();
                return;
            }
            for (UUID svcUuid : CANDIDATE_SERVICES) {
                BluetoothGattService svc = g.getService(svcUuid);
                if (svc != null && pickCharacteristics(svc)) {
                    ObdLogger.get().log(ObdLogger.Level.INFO,
                            "BLE service picked: " + svcUuid);
                    break;
                }
            }
            if (writeChar == null || notifyChar == null) {
                for (BluetoothGattService svc : g.getServices()) {
                    if (pickCharacteristics(svc)) {
                        ObdLogger.get().log(ObdLogger.Level.INFO,
                                "BLE service picked (fallback): " + svc.getUuid());
                        break;
                    }
                }
            }
            if (writeChar == null || notifyChar == null) {
                firstError = new IOException("no usable BLE write+notify chars");
                servicesLatch.countDown();
                return;
            }
            if (!enableNotifications()) {
                firstError = new IOException("notification enable failed");
            }
            servicesLatch.countDown();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic c) {
            if (closed) return;
            byte[] data = c.getValue();
            if (data == null || data.length == 0) return;
            lastNotifyAtMs = System.currentTimeMillis();
            RawFrameLogger.get().rx(data, 0, data.length);
            // Clone: the stack may reuse the characteristic's backing array.
            // offer() never blocks the Binder GATT callback thread; at OBD data
            // rates the 512-chunk queue only fills if nothing has been reading
            // for a long time, in which case dropping the oldest is correct.
            byte[] copy = data.clone();
            while (!rxQueue.offer(copy)) {
                byte[] dropped = rxQueue.poll();
                if (dropped != null) {
                    queuedBytes.addAndGet(-dropped.length);
                    ObdLogger.get().log(ObdLogger.Level.ERROR,
                            "BLE rx queue full — dropped " + dropped.length + " stale bytes");
                }
            }
            queuedBytes.addAndGet(copy.length);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g,
                                          BluetoothGattCharacteristic c,
                                          int status) {
            CountDownLatch l = writeLatch;
            if (l != null) l.countDown();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g,
                                      BluetoothGattDescriptor d,
                                      int status) {
            // CCCD write completed — let connect() proceed to send commands.
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "BLE CCCD write status=" + status);
            CountDownLatch l = cccdLatch;
            if (l != null) l.countDown();
        }
    };

    private boolean pickCharacteristics(BluetoothGattService svc) {
        BluetoothGattCharacteristic w = null, n = null;
        for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
            int p = c.getProperties();
            if (w == null && (p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                    | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                w = c;
            }
            if (n == null && (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                n = c;
            }
        }
        if (w != null && n != null) {
            writeChar = w;
            notifyChar = n;
            return true;
        }
        return false;
    }

    private boolean enableNotifications() {
        if (!gatt.setCharacteristicNotification(notifyChar, true)) return false;
        BluetoothGattDescriptor d = notifyChar.getDescriptor(CCCD);
        if (d == null) return true; // some chars notify without CCCD; let it ride
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        // Set up a latch so we can wait for the descriptor write to complete on the chip
        // before we start sending AT commands. Skipping this causes lost notifications on
        // vLinker BM+ / OBDLink LX with some Android stacks.
        cccdLatch = new CountDownLatch(1);
        boolean ok = gatt.writeDescriptor(d);
        if (!ok) cccdLatch.countDown();
        return ok;
    }

    public void connect(Context context, BluetoothDevice device) throws IOException {
        ObdLogger.get().log(ObdLogger.Level.INFO,
                "BLE connectGatt " + device.getAddress());
        gatt = device.connectGatt(context, false, callback);
        if (gatt == null) throw new IOException("connectGatt returned null");

        try {
            if (!connectedLatch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("BLE connect timeout");
            }
            if (firstError != null) throw firstError;

            // Some stacks never deliver onMtuChanged when the MTU was already
            // negotiated (and requestMtu() itself can be rejected). Discovery
            // used to be chained exclusively off that callback, so connect()
            // would hang to the 10s timeout. Wait briefly, then start
            // discovery ourselves — the CAS ensures only one path does.
            CountDownLatch ml = mtuLatch;
            if (ml != null && !ml.await(2, TimeUnit.SECONDS)) {
                ObdLogger.get().log(ObdLogger.Level.INFO,
                        "BLE MTU callback missing after 2s — starting discovery directly");
            }
            if (discoveryStarted.compareAndSet(false, true)) {
                BluetoothGatt g = gatt;
                if (g == null || !g.discoverServices()) {
                    throw new IOException("discoverServices() rejected");
                }
            }

            if (!servicesLatch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("BLE service discovery timeout");
            }
            if (firstError != null) throw firstError;

            // Wait for the CCCD descriptor write to complete on the chip before we
            // declare the transport ready. Otherwise the first AT command may go
            // out before notifications are enabled = silent failure with vLinker BM+.
            CountDownLatch lc = cccdLatch;
            if (lc != null) {
                if (!lc.await(3, TimeUnit.SECONDS)) {
                    ObdLogger.get().log(ObdLogger.Level.INFO,
                            "BLE CCCD write did not ack within 3s — proceeding anyway");
                }
            }
            // Additional 500ms settle window — gives vLinker firmware time to enable
            // its notification pipe internally after CCCD write completes.
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("BLE connect interrupted");
        }

        bufIn = new BufferedInputStream(new RxQueueInputStream());

        txOut = new OutputStream() {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                int cs = chunkSize; // negotiated MTU-3, not the 20-byte default
                for (int i = 0; i < len; i += cs) {
                    int n = Math.min(cs, len - i);
                    byte[] chunk = new byte[n];
                    System.arraycopy(b, off + i, chunk, 0, n);
                    sendChunk(chunk);
                }
            }
            @Override public void write(int b) throws IOException {
                sendChunk(new byte[]{ (byte) b });
            }
            @Override public void flush() { /* chunks sent eagerly */ }
        };

        ObdLogger.get().log(ObdLogger.Level.INFO, "BLE transport ready");
    }

    private void sendChunk(byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (closed) throw new IOException("BLE transport closed");
            BluetoothGattCharacteristic wc = writeChar;
            BluetoothGatt g = gatt;
            if (wc == null || g == null) throw new IOException("BLE not ready");

            // Prefer WRITE_NO_RESPONSE when supported (vLinker default and faster).
            // If only DEFAULT_WRITE is supported, fall back. If both are supported,
            // use WRITE_NO_RESPONSE to avoid per-chunk roundtrip latency.
            int props = wc.getProperties();
            int wt;
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                wt = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            } else if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                wt = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            } else {
                throw new IOException("Write char has no writable property bits");
            }
            wc.setValue(payload);
            wc.setWriteType(wt);
            RawFrameLogger.get().tx(payload, 0, payload.length);

            CountDownLatch l = null;
            if (wt == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                l = new CountDownLatch(1);
                writeLatch = l;
            }
            if (!g.writeCharacteristic(wc)) {
                // First retry — sometimes the previous op hasn't completed yet on the chip
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("BLE write interrupted");
                }
                if (!g.writeCharacteristic(wc)) {
                    throw new IOException("BLE write rejected (twice)");
                }
            }
            if (l != null) {
                try {
                    if (!l.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("BLE write ack timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("BLE write interrupted");
                }
            }
        }
    }

    public BufferedInputStream getInput() { return bufIn; }
    public OutputStream getOutput() { return txOut; }
    public boolean isConnected() { return connected; }

    public void disconnect() {
        closed = true;
        connected = false;
        RawFrameLogger.get().flushRx();
        // unblock anyone waiting on a write ack or CCCD/MTU
        CountDownLatch l = writeLatch;
        if (l != null) l.countDown();
        CountDownLatch c = cccdLatch;
        if (c != null) c.countDown();
        CountDownLatch m = mtuLatch;
        if (m != null) m.countDown();
        synchronized (writeLock) {
            BluetoothGatt g = gatt;
            // Clear Android's GATT service cache so the next connect sees fresh services.
            // Without this, a stale cache can hide newly-added services after a firmware
            // reset on the adapter. refreshDeviceCache is a hidden method — invoke via reflection.
            try {
                if (g != null) {
                    java.lang.reflect.Method m2 = g.getClass().getMethod("refresh");
                    if (m2 != null) m2.invoke(g);
                }
            } catch (Exception ignored) {}
            try { if (g != null) { g.disconnect(); g.close(); } } catch (Exception ignored) {}
            gatt = null;
            writeChar = null;
            notifyChar = null;
        }
        // Wake any reader blocked in the rx queue poll — it re-checks `closed`
        // and returns EOF. The queue itself is left to be garbage-collected
        // with the transport.
        rxQueue.offer(new byte[0]);
    }

    /**
     * InputStream over the notification queue. Unlike PipedInputStream it has
     * no reader-thread affinity, so the poll thread can be killed and recreated
     * freely without notifications landing in a dead pipe. available() is exact
     * (queuedBytes covers the queue plus the partially-read head chunk), which
     * the available()-then-read() pattern in every command pump relies on.
     */
    private final class RxQueueInputStream extends InputStream {
        private byte[] cur;
        private int pos;

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xFF);
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (cur == null || pos >= cur.length) {
                if (closed && rxQueue.isEmpty()) return -1;
                try {
                    byte[] next = rxQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (next != null && next.length > 0) {
                        cur = next;
                        pos = 0;
                    } else if (closed) {
                        return -1;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted");
                }
            }
            int n = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, n);
            pos += n;
            queuedBytes.addAndGet(-n);
            return n;
        }

        @Override public int available() {
            return Math.max(0, queuedBytes.get());
        }
    }

    /** Milliseconds since the adapter last delivered notification bytes, or -1 if never. */
    public long msSinceLastNotify() {
        long t = lastNotifyAtMs;
        return t == 0 ? -1 : (System.currentTimeMillis() - t);
    }

    /**
     * Reset the notification watchdog to "just now". Call this whenever the poll
     * thread resumes after a pause (one-shot command, module scan, group switch) so
     * the 5-second stale-notify threshold is measured from resume, not from the
     * last notification received during the paused operation.
     */
    public void resetNotifyWatchdog() {
        if (!closed) lastNotifyAtMs = System.currentTimeMillis();
    }
}
