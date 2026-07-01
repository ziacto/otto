package com.example.obd;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class ObdManagerFast {
    private final Context context;
    private final SpeedPollerListener listener;
    private final Supplier<Integer> intervalSupplier;
    private final Supplier<List<ObdCommand>> commandSupplier;

    private volatile ObdConnection connection;
    private volatile boolean running = false;
    private Thread pollThread;

    public ObdManagerFast(Context context,
                          SpeedPollerListener listener,
                          Supplier<Integer> intervalSupplier,
                          Supplier<List<ObdCommand>> commandSupplier) {
        this.context = context;
        this.listener = listener;
        this.intervalSupplier = intervalSupplier;
        this.commandSupplier = commandSupplier;
    }

    public void connect(BluetoothDevice device) throws IOException {
        disconnect();
        resetCommandSupportState();
        connection = new ObdConnection();
        connection.connect(context, device);
        startPolling();
    }

    /**
     * Dry-run connect — no Bluetooth, no car. Same code path as {@link #connect}
     * afterwards so the poll loop, parsers and UI all run against synthetic
     * ELM327 responses. Wired to the "Simulator Mode" drawer item and reused
     * by the Self-Test.
     */
    public void connectSimulator() throws IOException {
        disconnect();
        resetCommandSupportState();
        connection = new ObdConnection();
        connection.connectSimulator();
        startPolling();
    }

    /**
     * Clear any "known unsupported" flags from the previous session — a new
     * connect might be to a different vehicle where the PID set differs.
     * ObdCommand instances live in the PollGroup enum and are shared across
     * reconnects, so we have to actively reset them.
     */
    private void resetCommandSupportState() {
        try {
            for (com.example.obd.MainActivity.PollGroup g : com.example.obd.MainActivity.PollGroup.values()) {
                for (ObdCommand c : g.getSensors()) c.resetSupportState();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Switch poll group: stop thread and wait for it to finish,
     * then start a new one. Connection stays open.
     *
     * Heavy hammer — joins the poll thread for up to 2s on the calling thread.
     * Don't call from the UI thread on dashboard switches; use {@link #swapPollGroup}.
     */
    public void restartPolling() {
        stopAndJoinPollThread();
        if (connection != null && connection.isConnected()) {
            startPolling();
        }
    }

    /**
     * Fast, non-blocking poll-group swap. The supplier already re-reads
     * currentGroup on every loop iteration, so all we need to do is wake the
     * sleep so the new commands take effect immediately. No join, no UI freeze.
     */
    public void swapPollGroup() {
        Thread t = pollThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    public synchronized void disconnect() {
        // Order matters:
        //   1. running=false so the poll thread exits via its early-return path
        //      instead of falling into the post-loop self-disconnect (which would
        //      block on this synchronized method and create a 2s join deadlock).
        //   2. Close the transport so any blocked read wakes with IOException.
        //   3. Join the poll thread.
        running = false;
        ObdConnection c = connection;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) {}
        }
        stopAndJoinPollThread();
        connection = null;
    }

    public boolean isConnected() {
        return connection != null && connection.isConnected();
    }

    /**
     * Run a one-shot OBD command (e.g. Mode 03/04/07) while the poll thread is paused.
     * Returns the raw ELM327 response text up to the '>' prompt.
     */
    private synchronized String sendOneShot(String cmd) throws IOException {
        if (connection == null || !connection.isConnected()) {
            throw new IOException("Not connected");
        }
        ObdLogger.get().log(ObdLogger.Level.TX, cmd);
        stopAndJoinPollThread();
        try {
            BufferedInputStream in = connection.getInput();
            OutputStream out = connection.getOutput();
            // Flush any leftover bytes
            while (in.available() > 0) in.read();
            out.write((cmd + "\r").getBytes());
            out.flush();

            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + 10_000; // 10s — DTC/VIN reads can be slow
            while (System.currentTimeMillis() < deadline) {
                if (in.available() > 0) {
                    int c = in.read();
                    if (c == -1 || c == '>') break;
                    sb.append((char) c);
                } else {
                    try { Thread.sleep(20); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted");
                    }
                }
            }
            String raw = sb.toString().trim();
            ObdLogger.get().log(ObdLogger.Level.RX, raw);
            return raw;
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
        }
    }

    /** Read DTCs via Mode 03 (stored), 07 (pending), or 0A (permanent). Blocking — call off main thread. */
    public List<String> readDtcs(int mode) throws IOException {
        if (mode != 3 && mode != 7 && mode != 0x0A) {
            throw new IllegalArgumentException("Mode must be 3, 7, or 0A");
        }
        String cmd = (mode == 0x0A) ? "0A" : String.format("%02d", mode);
        String raw = sendOneShot(cmd);
        if (raw.toUpperCase().contains("NO DATA")) return Collections.emptyList();
        return DtcUtil.parseDtcResponse(raw, mode);
    }

    /** Clear stored DTCs via Mode 04. Blocking — call off main thread. */
    public boolean clearDtcs() throws IOException {
        String raw = sendOneShot("04");
        return DtcUtil.isClearAcknowledged(raw);
    }

    /** Read VIN via Mode 09 PID 02. Blocking — call off main thread. Returns null if unavailable. */
    public String readVin() throws IOException {
        String raw = sendOneShot("0902");
        return VinUtil.parseVin(raw);
    }

    /**
     * Read DTCs from a BMW non-DME module on D-CAN via ATSH/ATCRA header switching.
     * Issues UDS service 0x19 02 FF (report DTCs by status mask) and parses the
     * response. Restores the ATSP / default header afterward.
     *
     * Returns an empty list if the module didn't respond or returned no codes.
     * Throws IOException only on connection-level failure.
     */
    public synchronized List<String> readModuleDtcs(BmwModule module) throws IOException {
        if (connection == null || !connection.isConnected()) {
            throw new IOException("Not connected");
        }
        stopAndJoinPollThread();
        try {
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "Module read: " + module.name() + " (resp " + module.responseId + ")");
            // Set up D-CAN routing for this module
            sendAt("ATCAF1");                    // CAN auto-format on (we want ISO-TP framing)
            sendAt("ATSH 6F1");                  // tester header
            sendAt("ATCRA " + module.responseId);// listen only for this module's response
            sendAt("ATFCSH 6F1");                // flow control source = tester
            sendAt("ATFCSD 30 00 00");           // flow control: continuous, no delay
            sendAt("ATFCSM 1");                  // flow control mode: use the above

            // Build target byte from module header hint (second byte). DME=12, DSC=29, EGS=18.
            String tgt = module.headerHint.split(" ")[1];
            // UDS read DTCs by status mask, mask 0xFF
            String cmd = tgt + " 19 02 FF";
            String raw = sendRawNoTimeout(cmd);
            ObdLogger.get().log(ObdLogger.Level.RX, "module< " + raw);

            // Reset filters so subsequent generic polls work
            sendAt("ATCRA");
            sendAt("ATAR");

            if (raw == null || raw.toUpperCase().contains("NO DATA")
                    || raw.toUpperCase().contains("CAN ERROR")
                    || raw.toUpperCase().contains("BUS")) {
                return Collections.emptyList();
            }
            return BmwDtcParser.parseUdsDtcResponse(raw);
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
        }
    }

    /** Best-effort AT command — logs TX, drains response up to '>'. */
    private void sendAt(String at) throws IOException {
        java.io.BufferedInputStream in = connection.getInput();
        java.io.OutputStream out = connection.getOutput();
        while (in.available() > 0) in.read();
        ObdLogger.get().log(ObdLogger.Level.TX, "AT> " + at);
        out.write((at + "\r").getBytes());
        out.flush();
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int c = in.read();
                if (c == -1 || c == '>') break;
            } else {
                try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
            }
        }
    }

    /** Raw send with longer timeout — module reads can take 5-10s on cold modules. */
    private String sendRawNoTimeout(String cmd) throws IOException {
        java.io.BufferedInputStream in = connection.getInput();
        java.io.OutputStream out = connection.getOutput();
        while (in.available() > 0) in.read();
        ObdLogger.get().log(ObdLogger.Level.TX, "TX> " + cmd);
        out.write((cmd + "\r").getBytes());
        out.flush();
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int c = in.read();
                if (c == -1 || c == '>') break;
                sb.append((char) c);
            } else {
                try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
            }
        }
        return sb.toString().trim();
    }

    /** Read Mode 09 ASCII strings (PID 04 = CALID, PID 0A = ECU name). Null if unavailable. */
    public String readMode09Ascii(int pid) throws IOException {
        String raw = sendOneShot(String.format("09%02X", pid));
        return ObdUtil.parseMode09Ascii(raw, pid);
    }

    /**
     * Read a BMW UDS Mode 22 (ReadDataByIdentifier) PID. Performs ATSH/ATCRA header
     * switching to talk to the per-module address (DME/EGS/DSC), assembles ISO-TP
     * multi-frame replies via ATCAF1, decodes via the {@link BmwMode22Pid.Did} decoder.
     *
     * Returns null if the module did not answer, the response was malformed, or the
     * decoder rejected the data. Call off the UI thread — pauses the poll loop.
     */
    public synchronized Double readMode22Pid(BmwMode22Pid.Did pid) throws IOException {
        if (connection == null || !connection.isConnected()) {
            throw new IOException("Not connected");
        }
        stopAndJoinPollThread();
        try {
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "Mode22 " + pid.name() + " (" + pid.didHex() + ") on " + pid.module.name());

            sendAt("ATCAF1");
            sendAt("ATSH 6F1");
            sendAt("ATCRA " + pid.module.responseId);
            sendAt("ATFCSH 6F1");
            sendAt("ATFCSD 30 00 00");
            sendAt("ATFCSM 1");

            // BMW request layout: <target> 22 <did-hi> <did-lo>
            String tgt = pid.module.headerHint.split(" ")[1];
            String cmd = String.format("%s 22 %02X %02X", tgt, (pid.did >> 8) & 0xFF, pid.did & 0xFF);
            String raw = sendRawNoTimeout(cmd);
            ObdLogger.get().log(ObdLogger.Level.RX, "m22< " + raw);

            sendAt("ATCRA");
            sendAt("ATAR");

            byte[] data = BmwMode22Pid.parseMode22Data(raw, pid.did);
            if (data.length == 0) return null;
            return pid.decoder.decode(data);
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
        }
    }

    /** Read a batch of Mode 22 PIDs that share a target module — cheaper than per-call init. */
    public synchronized java.util.LinkedHashMap<BmwMode22Pid.Did, Double> readMode22Batch(
            java.util.List<BmwMode22Pid.Did> pids) throws IOException {
        java.util.LinkedHashMap<BmwMode22Pid.Did, Double> out = new java.util.LinkedHashMap<>();
        if (pids == null || pids.isEmpty()) return out;
        if (connection == null || !connection.isConnected()) {
            throw new IOException("Not connected");
        }
        stopAndJoinPollThread();
        try {
            BmwModule prev = null;
            for (BmwMode22Pid.Did pid : pids) {
                if (pid.module != prev) {
                    sendAt("ATCAF1");
                    sendAt("ATSH 6F1");
                    sendAt("ATCRA " + pid.module.responseId);
                    sendAt("ATFCSH 6F1");
                    sendAt("ATFCSD 30 00 00");
                    sendAt("ATFCSM 1");
                    prev = pid.module;
                }
                String tgt = pid.module.headerHint.split(" ")[1];
                String cmd = String.format("%s 22 %02X %02X", tgt,
                        (pid.did >> 8) & 0xFF, pid.did & 0xFF);
                String raw = sendRawNoTimeout(cmd);
                byte[] data = BmwMode22Pid.parseMode22Data(raw, pid.did);
                out.put(pid, data.length == 0 ? null : pid.decoder.decode(data));
            }
            sendAt("ATCRA");
            sendAt("ATAR");
            return out;
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
        }
    }

    /** Read Mode 01 PID 01 readiness monitors. Returns null if ECU did not respond. */
    public ObdUtil.Readiness readReadiness() throws IOException {
        String raw = sendOneShot("0101");
        return ObdUtil.parseReadiness(raw);
    }

    /**
     * Read Mode 02 freeze-frame for the canonical sensor set (RPM, speed, load,
     * coolant, IAT, MAF, throttle, triggering DTC). Map preserves insertion order.
     * Empty map if ECU reports NO DATA for everything (no freeze frame stored).
     */
    public java.util.LinkedHashMap<String, String> readFreezeFrame() throws IOException {
        // (label, pid) pairs — ordered by relevance for diagnosing intermittent faults
        int[][] pids = {
                {0x02, 0}, // triggering DTC
                {0x0C, 0}, // RPM
                {0x0D, 0}, // speed
                {0x04, 0}, // engine load
                {0x05, 0}, // coolant
                {0x0F, 0}, // intake air temp
                {0x11, 0}, // throttle
                {0x10, 0}, // MAF
        };
        String[] labels = {
                "Triggering DTC", "RPM", "Vehicle Speed", "Engine Load",
                "Coolant Temp", "Intake Air Temp", "Throttle", "MAF"
        };
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pids.length; i++) {
            int pid = pids[i][0];
            String raw = sendOneShot(String.format("02%02X", pid));
            String val = ObdUtil.parseFreezeFramePid(raw, pid);
            out.put(labels[i], val == null ? "—" : val);
        }
        return out;
    }

    // ===================================================================
    // SERVICE FUNCTIONS — UDS routines for ID reads, CBS reset, battery
    // registration, and raw CAN sniffing. All pause the poll thread while
    // running and restart it on completion.
    // ===================================================================

    /** Result of probing a single UDS DID — keeps raw hex for the user to inspect. */
    public static class VehicleDataReading {
        public final int did;
        public final String label;
        public final String rawHex;       // hex bytes after "62 HI LO" (empty if no response)
        public final String asciiAttempt; // best-effort ASCII interpretation
        public final boolean responded;
        public VehicleDataReading(int did, String label, String rawHex, String ascii, boolean ok) {
            this.did = did; this.label = label; this.rawHex = rawHex;
            this.asciiAttempt = ascii; this.responded = ok;
        }
    }

    /**
     * Read a list of BMW UDS DIDs from a module. Returns one VehicleDataReading per DID
     * with the raw payload (hex) and a best-effort ASCII decode. Use this to probe what
     * data your specific N52 DME variant actually exposes — different builds expose
     * different DIDs.
     *
     * Standard DIDs to try on DME:
     *   0xF150 — BMW vehicle data bundle
     *   0xF18C — VIN protection number / part number
     *   0xF18B — programming date
     *   0xF18A — system supplier
     *   0x1001 — ECU identification
     *   0x1010 — vehicle operational data
     *   0x1011 — vehicle operational data 2
     *   0x3F07 — module functional state
     */
    public synchronized java.util.List<VehicleDataReading> readVehicleData(
            BmwModule module, int[] dids, String[] labels) throws IOException {
        if (connection == null || !connection.isConnected()) throw new IOException("Not connected");
        stopAndJoinPollThread();
        java.util.ArrayList<VehicleDataReading> out = new java.util.ArrayList<>();
        try {
            setupModuleRouting(module);
            String tgt = module.headerHint.split(" ")[1];
            for (int i = 0; i < dids.length; i++) {
                int did = dids[i];
                String label = (labels != null && i < labels.length) ? labels[i] : String.format("DID %04X", did);
                String cmd = String.format("%s 22 %02X %02X", tgt, (did >> 8) & 0xFF, did & 0xFF);
                String raw = sendRawNoTimeout(cmd);
                if (raw == null) {
                    out.add(new VehicleDataReading(did, label, "", "", false));
                    continue;
                }
                String u = raw.toUpperCase();
                if (u.contains("NO DATA") || u.contains("CAN ERROR") || u.contains("BUS")
                        || u.contains("7F22")) {  // 0x7F = negative response
                    out.add(new VehicleDataReading(did, label, "", "", false));
                    continue;
                }
                String hex = ObdUtil.collectHex(raw);
                String marker = String.format("62%04X", did);
                int idx = hex.indexOf(marker);
                if (idx < 0) {
                    out.add(new VehicleDataReading(did, label, "", "", false));
                    continue;
                }
                String payload = hex.substring(idx + marker.length());
                // Best-effort ASCII conversion (printable chars only)
                StringBuilder ascii = new StringBuilder();
                for (int p = 0; p + 2 <= payload.length(); p += 2) {
                    try {
                        int b = Integer.parseInt(payload.substring(p, p + 2), 16);
                        if (b >= 0x20 && b < 0x7F) ascii.append((char) b);
                    } catch (NumberFormatException e) { break; }
                }
                out.add(new VehicleDataReading(did, label, payload, ascii.toString(), true));
            }
            sendAt("ATCRA");
            sendAt("ATAR");
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
        }
        return out;
    }

    /**
     * KWP2000 service 0x1A 0x80 (readEcuIdentification) — for legacy modules that
     * don't speak UDS. Returns the raw response hex, or null if the module didn't
     * respond. Some older E46/E60 modules respond to KWP but not UDS.
     */
    public synchronized String readKwpEcuId(BmwModule module) throws IOException {
        if (connection == null || !connection.isConnected()) throw new IOException("Not connected");
        stopAndJoinPollThread();
        try {
            setupModuleRouting(module);
            String tgt = module.headerHint.split(" ")[1];
            String cmd = tgt + " 1A 80";
            String raw = sendRawNoTimeout(cmd);
            sendAt("ATCRA");
            sendAt("ATAR");
            if (raw == null) return null;
            String u = raw.toUpperCase();
            if (u.contains("NO DATA") || u.contains("CAN ERROR") || u.contains("BUS")) return null;
            return raw.trim();
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
        }
    }

    /**
     * Run a single ObdCommand off-band (poll thread paused). Returns the parsed numeric value.
     * Used by the Odometer screen for one-shot reads of PIDs that aren't in the regular poll group.
     */
    public synchronized double runOneShot(ObdCommand cmd) throws IOException {
        if (connection == null || !connection.isConnected()) throw new IOException("Not connected");
        stopAndJoinPollThread();
        try {
            return cmd.run(connection.getInput(), connection.getOutput());
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
        }
    }

    /**
     * Read a UDS DID from a BMW module and interpret the response payload as an
     * N-byte big-endian unsigned integer. Returns null if the module didn't answer
     * or the response didn't contain a positive answer for this DID.
     *
     * Useful for "engine run distance" / "operating hours" type DIDs where the
     * payload format is just an integer, not a structured value.
     */
    public synchronized Double readUdsRawNumeric(BmwModule module, int did, int byteCount) throws IOException {
        if (connection == null || !connection.isConnected()) throw new IOException("Not connected");
        stopAndJoinPollThread();
        try {
            setupModuleRouting(module);
            String tgt = module.headerHint.split(" ")[1];
            String cmd = String.format("%s 22 %02X %02X", tgt, (did >> 8) & 0xFF, did & 0xFF);
            String raw = sendRawNoTimeout(cmd);
            sendAt("ATCRA");
            sendAt("ATAR");
            if (raw == null) return null;
            String hex = ObdUtil.collectHex(raw);
            String marker = String.format("62%04X", did);
            int idx = hex.indexOf(marker);
            if (idx < 0) return null;
            int p = idx + marker.length();
            if (p + byteCount * 2 > hex.length()) return null;
            long value = 0;
            for (int i = 0; i < byteCount; i++) {
                int b = Integer.parseInt(hex.substring(p + i * 2, p + i * 2 + 2), 16);
                value = (value << 8) | b;
            }
            return (double) value;
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
        }
    }

    /** ECU identification record — part number, software version, hardware version. */
    public static class ModuleIdentification {
        public final BmwModule module;
        public final String partNumber;       // ISO 14229 DID F187 — Vehicle manufacturer ECU spare-part number
        public final String softwareVersion;  // DID F189 — Vehicle manufacturer ECU software number
        public final String hardwareNumber;   // DID F191 — Vehicle manufacturer ECU hardware number
        public final String vinFromEcu;       // DID F190 — VIN as recorded in this ECU
        public ModuleIdentification(BmwModule m, String pn, String sw, String hw, String vin) {
            this.module = m;
            this.partNumber = pn;
            this.softwareVersion = sw;
            this.hardwareNumber = hw;
            this.vinFromEcu = vin;
        }
    }

    /**
     * Read identification record from a BMW module via UDS service 0x22 with the
     * standard ISO 14229 DIDs (F187 part number, F189 software version, F190 VIN,
     * F191 hardware number). Returns nulls for fields the module doesn't answer.
     * Call off the main thread — pauses the poll loop.
     */
    public synchronized ModuleIdentification readModuleId(BmwModule module) throws IOException {
        if (connection == null || !connection.isConnected()) throw new IOException("Not connected");
        stopAndJoinPollThread();
        try {
            ObdLogger.get().log(ObdLogger.Level.INFO, "ID read: " + module.name());
            setupModuleRouting(module);
            String tgt = module.headerHint.split(" ")[1];
            String pn = readUdsAsciiDid(tgt, 0xF187);
            String sw = readUdsAsciiDid(tgt, 0xF189);
            String vin = readUdsAsciiDid(tgt, 0xF190);
            String hw = readUdsAsciiDid(tgt, 0xF191);
            sendAt("ATCRA");
            sendAt("ATAR");
            return new ModuleIdentification(module, pn, sw, hw, vin);
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
        }
    }

    /** Read a UDS DID and decode the data bytes as ASCII (trimmed of nulls). Returns null on no-data. */
    private String readUdsAsciiDid(String tgt, int did) throws IOException {
        String cmd = String.format("%s 22 %02X %02X", tgt, (did >> 8) & 0xFF, did & 0xFF);
        String raw = sendRawNoTimeout(cmd);
        if (raw == null) return null;
        String u = raw.toUpperCase();
        if (u.contains("NO DATA") || u.contains("CAN ERROR") || u.contains("BUS")) return null;
        // Find positive response marker 62 <DID_HI> <DID_LO>
        String hex = ObdUtil.collectHex(raw);
        String marker = String.format("62%04X", did);
        int idx = hex.indexOf(marker);
        if (idx < 0) return null;
        int p = idx + marker.length();
        StringBuilder ascii = new StringBuilder();
        while (p + 2 <= hex.length()) {
            try {
                int b = Integer.parseInt(hex.substring(p, p + 2), 16);
                if (b >= 0x20 && b < 0x7F) ascii.append((char) b);
                p += 2;
            } catch (NumberFormatException e) { break; }
        }
        String s = ascii.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Common module routing setup — ATSH/ATCRA/flow-control. */
    private void setupModuleRouting(BmwModule module) throws IOException {
        sendAt("ATCAF1");
        sendAt("ATSH 6F1");
        sendAt("ATCRA " + module.responseId);
        sendAt("ATFCSH 6F1");
        sendAt("ATFCSD 30 00 00");
        sendAt("ATFCSM 1");
    }

    /** CBS service item that can be reset. Each item maps to a routine ID on the DME. */
    public enum CbsItem {
        OIL_SERVICE("Engine Oil", 0x01),
        BRAKE_FLUID("Brake Fluid", 0x02),
        FRONT_BRAKE_PADS("Front Brake Pads", 0x03),
        REAR_BRAKE_PADS("Rear Brake Pads", 0x04),
        AIR_FILTER("Air Filter", 0x05),
        SPARK_PLUGS("Spark Plugs", 0x06),
        MICRO_FILTER("Cabin Air Filter", 0x07),
        VEHICLE_INSPECTION("Vehicle Inspection", 0x08);

        public final String label;
        public final int subFn;
        CbsItem(String l, int s) { this.label = l; this.subFn = s; }
    }

    /**
     * Reset a CBS (Condition Based Service) counter on the DME.
     *
     * Approach: UDS routine 0x31 01 with BMW-specific routine ID 0xDF60 + sub-byte
     * identifying which service item. This is the common E65/E60 path observed in
     * INPA scripts. Returns true if the ECU acknowledges with a positive response (71).
     *
     * WARNING: this writes to the DME's persistent storage. Only run with the user's
     * informed consent and with engine OFF + ignition ON (Terminal 15).
     */
    public synchronized boolean resetCbsCounter(CbsItem item) throws IOException {
        if (connection == null || !connection.isConnected()) throw new IOException("Not connected");
        stopAndJoinPollThread();
        try {
            ObdLogger.get().log(ObdLogger.Level.INFO, "CBS reset: " + item.label);
            setupModuleRouting(BmwModule.DME);
            String tgt = BmwModule.DME.headerHint.split(" ")[1];
            // UDS RoutineControl: 31 01 DF 60 <item-byte>
            String cmd = String.format("%s 31 01 DF 60 %02X", tgt, item.subFn);
            String raw = sendRawNoTimeout(cmd);
            ObdLogger.get().log(ObdLogger.Level.RX, "cbs< " + raw);
            sendAt("ATCRA");
            sendAt("ATAR");
            if (raw == null) return false;
            String hex = ObdUtil.collectHex(raw);
            // Positive response: 71 01 DF 60 <item> — first byte 0x71 = service 0x31 + 0x40
            return hex.contains("7101DF60") || hex.contains("710 1DF60") || hex.startsWith("7101");
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
        }
    }

    /** Battery chemistry types supported by the E65 IBS. */
    public enum BatteryType {
        AGM("AGM (sealed)", 0x01),
        FLOODED("Flooded (standard)", 0x02),
        EFB("EFB", 0x03);
        public final String label;
        public final int code;
        BatteryType(String l, int c) { this.label = l; this.code = c; }
    }

    /**
     * Register a new battery with the IBS via the DME on E65.
     *
     * Procedure (BMW INPA / ISTA approach):
     *   1. Write capacity (Ah) and chemistry to DME via UDS 0x2E DID 0x4081 or
     *      similar (varies by DME variant — we use the well-documented routine
     *      0x31 01 DF 50 + capacity word + chemistry byte).
     *   2. DME forwards to IBS via internal LIN bus.
     *   3. Energy management adaptations are reset so the alternator regulator
     *      starts with a fresh charging curve.
     *
     * WARNING: incorrect capacity registration causes alternator over- or
     * under-charging. Always verify the new battery sticker before running.
     * Returns true on positive ECU acknowledgement.
     */
    public synchronized boolean registerBattery(int capacityAh, BatteryType type) throws IOException {
        if (connection == null || !connection.isConnected()) throw new IOException("Not connected");
        if (capacityAh < 40 || capacityAh > 110) {
            throw new IllegalArgumentException("Capacity out of range (40-110 Ah)");
        }
        stopAndJoinPollThread();
        try {
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "Battery register: " + capacityAh + "Ah " + type.label);
            setupModuleRouting(BmwModule.DME);
            String tgt = BmwModule.DME.headerHint.split(" ")[1];
            // UDS RoutineControl: 31 01 DF 50 <cap_hi> <cap_lo> <chemistry>
            int capHi = (capacityAh >> 8) & 0xFF;
            int capLo = capacityAh & 0xFF;
            String cmd = String.format("%s 31 01 DF 50 %02X %02X %02X",
                    tgt, capHi, capLo, type.code);
            String raw = sendRawNoTimeout(cmd);
            ObdLogger.get().log(ObdLogger.Level.RX, "batreg< " + raw);
            sendAt("ATCRA");
            sendAt("ATAR");
            if (raw == null) return false;
            String hex = ObdUtil.collectHex(raw);
            return hex.contains("7101DF50") || hex.startsWith("7101");
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
        }
    }

    /**
     * Start raw D-CAN frame capture using ELM327 monitor mode (ATMA).
     *
     * Disables the poll loop and switches the adapter to passive listen. Frames
     * arrive as text lines on the input stream; the caller reads them via
     * {@link #readSnifferLine}. Stop with {@link #stopSniffer}.
     *
     * Format per line is hexadecimal frame data as ELM327 emits it, e.g.:
     *   "7E8 03 41 00 8B"
     * for an 11-bit ID 0x7E8 with 4 data bytes.
     */
    public synchronized void startSniffer() throws IOException {
        if (connection == null || !connection.isConnected()) throw new IOException("Not connected");
        stopAndJoinPollThread();
        try {
            ObdLogger.get().log(ObdLogger.Level.INFO, "Sniffer start (ATMA)");
            sendAt("ATCAF0");  // No CAN auto-format — show raw frames
            sendAt("ATH1");    // Headers on
            sendAt("ATS1");    // Spaces on (easier parsing)
            // Send ATMA but DO NOT wait for '>' — it never returns until cancelled
            java.io.OutputStream out = connection.getOutput();
            out.write("ATMA\r".getBytes());
            out.flush();
        } catch (IOException e) {
            // Restore polling if we failed to start
            if (connection != null && connection.isConnected()) startPolling();
            throw e;
        }
    }

    /** Read one captured frame line. Returns null on timeout. Blocking. */
    public String readSnifferLine(int timeoutMs) throws IOException {
        if (connection == null || !connection.isConnected()) throw new IOException("Not connected");
        java.io.BufferedInputStream in = connection.getInput();
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int c = in.read();
                if (c == -1) return null;
                if (c == '\r' || c == '\n') {
                    String line = sb.toString().trim();
                    if (!line.isEmpty()) return line;
                } else if (c == '>') {
                    return sb.length() == 0 ? null : sb.toString().trim();
                } else {
                    sb.append((char) c);
                }
            } else {
                try { Thread.sleep(5); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted");
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString().trim();
    }

    /** Stop sniffer and resume polling. Send any key (a CR) to cancel ATMA. */
    public synchronized void stopSniffer() throws IOException {
        try {
            if (connection != null && connection.isConnected()) {
                connection.getOutput().write("\r".getBytes());
                connection.getOutput().flush();
                // Drain remaining buffered frames + prompt
                java.io.BufferedInputStream in = connection.getInput();
                long deadline = System.currentTimeMillis() + 1500;
                while (System.currentTimeMillis() < deadline) {
                    if (in.available() > 0) in.read();
                    else { try { Thread.sleep(20); } catch (InterruptedException ignored) {} }
                }
                sendAt("ATCAF1"); // restore for normal operation
            }
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
            ObdLogger.get().log(ObdLogger.Level.INFO, "Sniffer stop");
        }
    }

    /**
     * Replay captured CAN frames. Each frame string is in the format produced by
     * {@link #readSnifferLine} ("ID HEX HEX HEX ..."). Returns the number of frames
     * the ELM327 accepted (positive response). DANGEROUS: replay can affect the car.
     */
    public synchronized int replayFrames(java.util.List<String> frames, int intervalMs) throws IOException {
        if (connection == null || !connection.isConnected()) throw new IOException("Not connected");
        stopAndJoinPollThread();
        int sent = 0;
        try {
            sendAt("ATCAF0");
            for (String frame : frames) {
                String[] parts = frame.trim().split("\\s+");
                if (parts.length < 2) continue;
                String id = parts[0];
                StringBuilder data = new StringBuilder();
                for (int i = 1; i < parts.length; i++) data.append(parts[i]);
                sendAt("ATSH " + id);
                sendRawNoTimeout(data.toString());
                sent++;
                if (intervalMs > 0) {
                    try { Thread.sleep(intervalMs); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            }
            sendAt("ATSH 6F1");
            sendAt("ATCAF1");
        } finally {
            if (connection != null && connection.isConnected()) startPolling();
        }
        return sent;
    }

    private synchronized void startPolling() {
        // Reset the BLE watchdog so the 5-second stale-notify threshold is measured
        // from now, not from whenever the last notification arrived during any
        // preceding one-shot command or module scan (which could be 8+ seconds ago).
        if (connection != null) connection.resetBleWatchdog();
        running = true;
        pollThread = new Thread(this::pollLoop, "ObdPollThread");
        pollThread.setPriority(Thread.MIN_PRIORITY);
        pollThread.start();
    }

    /** Stops the poll thread and waits up to 2000 ms for it to finish. */
    private void stopAndJoinPollThread() {
        Thread old;
        synchronized (this) {
            running = false;
            old = pollThread;
            if (pollThread != null) {
                pollThread.interrupt();
                pollThread = null;
            }
        }
        // Do not join the current thread (occurs when pollLoop() calls disconnect()).
        if (old != null && old != Thread.currentThread()) {
            try {
                old.join(2000);
                if (old.isAlive()) {
                    // Stream close + interrupt should always unblock the loop;
                    // if not, force-close the underlying transport so the next read
                    // throws IOException and the thread can exit. Leaving it stuck
                    // would block subsequent reconnect attempts.
                    ObdLogger.get().log(ObdLogger.Level.ERROR,
                            "Poll thread did not exit within 2s; forcing transport close");
                    ObdConnection c = connection;
                    if (c != null) {
                        try { c.disconnect(); } catch (Exception e) {
                            ObdLogger.get().log(ObdLogger.Level.ERROR,
                                    "Force-disconnect failed: " + e.getMessage());
                        }
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void pollLoop() {
        int emptycycles = 0; // Consecutive cycles without any successful reading
        boolean firstCycle = true; // First cycle skips the leading sleep so the
                                   // user sees a value within ~50ms of connect,
                                   // not after a full interval delay (~250-500ms).
        // Per-cmd state machine: log only the first failure after success and
        // first success after failure, so we don't spam the log at poll cadence
        // but still capture every transition. Keyed by cmd.getName().
        java.util.Map<String, Boolean> cmdHealth = new java.util.HashMap<>();
        ObdLogger.get().log(ObdLogger.Level.INFO,
                "Poll loop start (interval=" + intervalSupplier.get() + "ms, "
                        + commandSupplier.get().size() + " cmds)");

        while (running) {
            ObdConnection conn = connection;
            if (conn == null || !conn.isConnected()) break;

            List<ObdCommand> commands = commandSupplier.get();
            boolean anySuccess = false;

            for (ObdCommand cmd : commands) {
                if (!running) return;
                ObdConnection c = connection;
                if (c == null || !c.isConnected()) return;
                String name = cmd.getName();
                // Skip PIDs the ECU has already shown it doesn't support. Massive
                // speedup on BMW E65: 8 of 11 dashboard PIDs return NRC every
                // cycle otherwise, wasting ~2 s of adapter time on garbage.
                if (cmd.isKnownUnsupported()) continue;
                try {
                    double value = cmd.run(c.getInput(), c.getOutput());
                    listener.onValue(name, value, cmd.getUnit());
                    anySuccess = true;
                    if (cmdHealth.get(name) != Boolean.TRUE) {
                        ObdLogger.get().log(ObdLogger.Level.INFO,
                                "Poll OK: " + name + "=" + value + " " + cmd.getUnit());
                        cmdHealth.put(name, Boolean.TRUE);
                    }
                } catch (Exception e) {
                    // IOException (timeout, ELM status, parse error) or similar –
                    // silently skip this command, keep connection open. But log
                    // the transition success->fail so we can see *why* the user
                    // suddenly sees no data.
                    if (cmdHealth.get(name) != Boolean.FALSE) {
                        ObdLogger.get().log(ObdLogger.Level.ERROR,
                                "Poll FAIL: " + name + " — " + e.getMessage());
                        cmdHealth.put(name, Boolean.FALSE);
                    }
                }
            }

            if (anySuccess) {
                emptycycles = 0;
            } else {
                emptycycles++;
                // BLE-only fast-path: if the adapter has not delivered any
                // notification bytes for >5s while we are actively writing,
                // GATT has silently dropped — treat as disconnected on the
                // first miss instead of waiting 3 cycles.
                long bleIdle = conn.bleMsSinceLastRx();
                boolean bleSilent = bleIdle > 5000;
                if (emptycycles >= 3 || bleSilent) {
                    listener.onError(bleSilent
                            ? "BLE notifications stalled (" + bleIdle + " ms)"
                            : "OBD adapter not responding");
                    disconnect();
                    return;
                }
            }

            try {
                // First cycle: tiny sleep so the connect-to-first-value gap is ~50ms
                // (UI just rendered the dashboard; we want a value to land before the
                // user's eye reaches the gauge). Subsequent cycles use the configured
                // interval.
                long sleepMs = firstCycle ? 50 : intervalSupplier.get();
                firstCycle = false;
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                // Interrupt has two meanings here:
                //   1. stop: stopAndJoinPollThread() sets running=false first, so
                //      the while(running) check at the top will exit cleanly.
                //   2. wake: swapPollGroup() interrupts to make the new poll group
                //      take effect immediately. running stays true; loop again.
                if (!running) return;
                // Clear the interrupt status so the next sleep/IO call doesn't
                // immediately throw again.
                Thread.interrupted();
            }
        }

        ObdConnection conn = connection;
        if (conn != null && !conn.isConnected()) {
            listener.onError("OBD adapter disconnected");
            disconnect();
        }
    }
}
