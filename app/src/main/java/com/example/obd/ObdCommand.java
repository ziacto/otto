package com.example.obd;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;

public abstract class ObdCommand {
    private final String command;
    private static final int TIMEOUT_MS = 8000;
    // Consecutive NO-DATA count; once this crosses UNSUPPORTED_STREAK the poll
    // loop skips this command for the rest of the connection. Saves ~200-300 ms
    // per cycle per unsupported PID on the E65 DME (which only answers 010C,
    // 010D, 0101 over standard Mode 01 — every other PID returns NRC "7F 01 12").
    private static final int UNSUPPORTED_STREAK = 4;
    private volatile int consecutiveNoData = 0;

    protected ObdCommand(String command) {
        this.command = command;
    }

    public abstract String getName();
    public abstract String getUnit();
    public abstract double parseResult(String rawResponse);

    /** True once the ECU has answered NO DATA / NRC to this PID {@code UNSUPPORTED_STREAK} times in a row. */
    public boolean isKnownUnsupported() { return consecutiveNoData >= UNSUPPORTED_STREAK; }

    /** Called by the manager on connect so a fresh session re-probes every PID. */
    public void resetSupportState() { consecutiveNoData = 0; }

    public double run(BufferedInputStream in, OutputStream out) throws IOException {
        // Flush buffer – discard leftover bytes from the previous command
        while (in.available() > 0) in.read();

        out.write((command + "\r").getBytes());
        out.flush();

        StringBuilder res = new StringBuilder();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        boolean done = false;

        outer:
        while (!done && System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
            int avail = in.available();
            if (avail > 0) {
                // Drain everything buffered before sleeping again — cuts per-cmd
                // latency from ~20ms × bytes to ~20ms × frames.
                while (avail-- > 0) {
                    int c = in.read();
                    if (c == -1 || c == '>') { done = true; continue outer; }
                    res.append((char) c);
                }
            } else {
                try { Thread.sleep(10); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted");
                }
            }
        }

        String raw = res.toString().trim();

        // Try to isolate a positive Mode-01 response FIRST — before checking
        // for ELM status keywords. Real hardware sometimes returns mixed
        // buffers like "01 12 41 06 83 STOPPED" where valid Mode-01 data
        // (`41 06 83`) is followed by a stale STOPPED marker from a prior
        // command. If we ran the ELM-status check first, "STOPPED" would
        // cause us to throw away perfectly good data. See crash from log
        // 2026-07-01 18:33:58 for the exact response shape.
        String isolated = isolatePositiveResponse(raw);
        if (isolated != null) {
            try {
                double v = parseResult(isolated);
                consecutiveNoData = 0;
                return v;
            } catch (Exception e) {
                throw new IOException("Parse error [" + getName() + "]: " + raw);
            }
        }

        // No positive response — fall back to ELM-status detection.
        if (raw.isEmpty() || isElm327Status(raw)) {
            consecutiveNoData++;
            throw new IOException("ELM: " + raw);
        }
        // Unknown but non-empty response — treat as NO DATA.
        consecutiveNoData++;
        throw new IOException("ELM: NO DATA");
    }

    /**
     * For OBD requests like "010C" — locate the last "41 0C ..." positive-response
     * slice in the raw ELM buffer and return it as a space-separated hex string.
     * Returns null if no positive response is present (typical when the ECU
     * replies "7F 01 12" or when only a stale prompt is buffered). For non-OBD
     * commands (ATRV, ATZ, ...) the raw string is returned unchanged so the
     * existing ATRV voltage parser keeps working.
     */
    /** Package-visible for {@link ParserSmokeTest}. */
    String isolatePositiveResponse(String raw) {
        if (command.length() < 4) return raw;
        char c0 = command.charAt(0);
        if (!Character.isDigit(c0)) return raw; // AT-style command, no NRC concept
        int reqMode, pid;
        try {
            reqMode = Integer.parseInt(command.substring(0, 2), 16);
            pid     = Integer.parseInt(command.substring(2, 4), 16);
        } catch (NumberFormatException e) {
            return raw;
        }
        String hex = raw.replaceAll("\\s+", "").toUpperCase();
        String posMarker = String.format("%02X%02X", reqMode + 0x40, pid);
        int posIdx = hex.lastIndexOf(posMarker);
        if (posIdx < 0) return null;
        // Rebuild as "AA BB CC ..." so existing parseResult(split(" ")) keeps working.
        StringBuilder sb = new StringBuilder();
        for (int i = posIdx; i + 2 <= hex.length(); i += 2) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }

    private static boolean isElm327Status(String r) {
        String u = r.toUpperCase();
        return u.contains("NO DATA")
            || u.contains("ERROR")
            || u.contains("CONNECT")
            || u.contains("SEARCHING")
            || u.contains("STOPPED")
            || u.contains("UNABLE")
            || u.contains("BUS")
            || u.contains("FB ERR")
            || u.contains("DATA ERR")
            || u.trim().equals("?")
            || u.trim().equals("OK");
    }
}
