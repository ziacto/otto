package com.example.obd;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsers for OBD-II modes outside of DTC read/clear:
 *   Mode 01 PID 01 — MIL state, DTC count, readiness monitors
 *   Mode 02 PID xx — freeze frame sensor snapshot
 *   Mode 09 PID 04/0A — ASCII strings (CALID, ECU name)
 *
 * All parsing is pure; no I/O. Inputs are raw ELM327 responses (the same shape
 * that {@link DtcUtil} consumes — multi-frame prefixes, whitespace, '>'-terminated).
 */
public final class ObdUtil {

    private ObdUtil() {}

    // ----- Readiness (Mode 01 PID 01) -----

    public static final class Readiness {
        public boolean milOn;
        public int dtcCount;
        public final Map<String, String> monitors = new LinkedHashMap<>(); // name -> "n/a" | "ready" | "not ready"
        public String raw;
    }

    /**
     * Parse Mode 01 PID 01 (4 data bytes A B C D after the response header).
     * SAE J1979 bit layout. Returns null on unparseable input.
     */
    public static Readiness parseReadiness(String raw) {
        String hex = collectHex(raw);
        int idx = hex.indexOf("4101");
        if (idx < 0 || idx + 2 + 8 > hex.length()) return null;

        int a, b, c, d;
        try {
            a = Integer.parseInt(hex.substring(idx + 4, idx + 6), 16);
            b = Integer.parseInt(hex.substring(idx + 6, idx + 8), 16);
            c = Integer.parseInt(hex.substring(idx + 8, idx + 10), 16);
            d = Integer.parseInt(hex.substring(idx + 10, idx + 12), 16);
        } catch (NumberFormatException e) {
            return null;
        }

        Readiness r = new Readiness();
        r.raw = raw;
        r.milOn = (a & 0x80) != 0;
        r.dtcCount = a & 0x7F;

        // Continuous monitors (byte B, low nibble = supported bit, high nibble = status bit)
        // J1979: B bit0=Misfire supported, bit1=Fuel sys supported, bit2=Components supported
        //        B bit4=Misfire incomplete, bit5=Fuel sys incomplete, bit6=Components incomplete
        addCont(r.monitors, "Misfire",     (b & 0x01) != 0, (b & 0x10) == 0);
        addCont(r.monitors, "Fuel System", (b & 0x02) != 0, (b & 0x20) == 0);
        addCont(r.monitors, "Components",  (b & 0x04) != 0, (b & 0x40) == 0);

        // Non-continuous monitors (spark ignition; N52 is petrol so this set applies)
        // C bit0..7 = supported (1=yes), D bit0..7 = status (1=not ready, 0=ready)
        String[] names = {
                "Catalyst", "Heated Catalyst", "Evap System", "Secondary Air",
                "A/C Refrigerant", "O2 Sensor", "O2 Heater", "EGR System"
        };
        for (int i = 0; i < 8; i++) {
            boolean supported = (c & (1 << i)) != 0;
            boolean ready = (d & (1 << i)) == 0;
            addCont(r.monitors, names[i], supported, ready);
        }
        return r;
    }

    private static void addCont(Map<String, String> m, String name, boolean supported, boolean ready) {
        m.put(name, !supported ? "n/a" : (ready ? "ready" : "not ready"));
    }

    // ----- Freeze frame (Mode 02 PID xx, frame 00) -----

    /**
     * Parse a single Mode 02 PID response. Returns a human-readable value with units,
     * or null if the ECU returned NO DATA / unparseable.
     */
    public static String parseFreezeFramePid(String raw, int pid) {
        if (raw == null) return null;
        String u = raw.toUpperCase();
        if (u.contains("NO DATA") || u.contains("NODATA")) return null;
        String hex = collectHex(raw);
        // Response prefix for Mode 02 is "42" then the PID, then frame #, then data bytes
        String marker = String.format("42%02X", pid);
        int idx = hex.indexOf(marker);
        if (idx < 0) return null;
        int p = idx + marker.length() + 2; // skip frame number byte
        return decodePid(pid, hex, p);
    }

    /** Decode common Mode 01/02 PIDs starting at hex offset p. */
    private static String decodePid(int pid, String hex, int p) {
        try {
            switch (pid) {
                case 0x02: { // DTC that triggered freeze
                    if (p + 4 > hex.length()) return null;
                    int hi = Integer.parseInt(hex.substring(p, p + 2), 16);
                    int lo = Integer.parseInt(hex.substring(p + 2, p + 4), 16);
                    if (hi == 0 && lo == 0) return "(none stored)";
                    return DtcUtil.decodeDtc(hi, lo);
                }
                case 0x04: { // engine load %
                    int a = Integer.parseInt(hex.substring(p, p + 2), 16);
                    return String.format("%.1f %%", a * 100.0 / 255.0);
                }
                case 0x05: { // coolant °C
                    int a = Integer.parseInt(hex.substring(p, p + 2), 16);
                    return (a - 40) + " °C";
                }
                case 0x0C: { // RPM
                    int a = Integer.parseInt(hex.substring(p, p + 2), 16);
                    int b = Integer.parseInt(hex.substring(p + 2, p + 4), 16);
                    return String.format("%.0f rpm", (a * 256 + b) / 4.0);
                }
                case 0x0D: { // speed km/h
                    int a = Integer.parseInt(hex.substring(p, p + 2), 16);
                    return a + " km/h";
                }
                case 0x0F: { // intake air temp °C
                    int a = Integer.parseInt(hex.substring(p, p + 2), 16);
                    return (a - 40) + " °C";
                }
                case 0x10: { // MAF g/s
                    int a = Integer.parseInt(hex.substring(p, p + 2), 16);
                    int b = Integer.parseInt(hex.substring(p + 2, p + 4), 16);
                    return String.format("%.2f g/s", (a * 256 + b) / 100.0);
                }
                case 0x11: { // throttle %
                    int a = Integer.parseInt(hex.substring(p, p + 2), 16);
                    return String.format("%.1f %%", a * 100.0 / 255.0);
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ----- Mode 09 ASCII strings (CALID, ECU name) -----

    /**
     * Parse a Mode 09 response containing ASCII bytes (CALID PID 04, ECU name PID 0A).
     * Returns the printable string, or null if not parseable.
     */
    public static String parseMode09Ascii(String raw, int pid) {
        if (raw == null) return null;
        String hex = collectHex(raw);
        String marker = String.format("49%02X", pid);
        int idx = hex.indexOf(marker);
        if (idx < 0) return null;
        // Skip response (49 + PID) + the message count byte
        int p = idx + 4 + 2;
        StringBuilder sb = new StringBuilder();
        while (p + 2 <= hex.length()) {
            int b;
            try { b = Integer.parseInt(hex.substring(p, p + 2), 16); }
            catch (NumberFormatException e) { break; }
            if (b >= 0x20 && b < 0x7F) sb.append((char) b);
            p += 2;
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    // ----- shared hex collector (mirrors DtcUtil internals) -----

    static String collectHex(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replace('\r', ' ').replace('\n', ' ');
        StringBuilder hex = new StringBuilder();
        for (String tok : cleaned.split("\\s+")) {
            if (tok.isEmpty()) continue;
            if (tok.length() == 2 && tok.charAt(1) == ':') continue;
            if (tok.length() == 3 && isHex(tok)) continue;
            if (isHex(tok)) hex.append(tok);
        }
        return hex.toString().toUpperCase();
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }
}
