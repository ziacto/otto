package com.example.obd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OBD-II DTC (Diagnostic Trouble Code) helpers.
 * Pure parsing — no I/O. Handles Mode 03 (stored), Mode 07 (pending), Mode 04 (clear).
 */
public final class DtcUtil {

    private static final char[] LETTERS = {'P', 'C', 'B', 'U'};

    private DtcUtil() {}

    /**
     * Parse a raw ELM327 response for Mode 03, 07, or 0A.
     * Handles multi-frame responses (lines prefixed with "0:", "1:", ...).
     * Returns an empty list when the ECU reports no codes.
     */
    public static List<String> parseDtcResponse(String raw, int mode) {
        if (raw == null) return Collections.emptyList();
        String responseModeHex = String.format("%02X", 0x40 + mode); // 43 for mode 03, 47 for mode 07

        // Strip control chars, normalize whitespace, drop ISO-TP frame prefixes like "0:" "1:"
        String cleaned = raw.replace('\r', ' ').replace('\n', ' ');
        StringBuilder hex = new StringBuilder();
        for (String token : cleaned.split("\\s+")) {
            if (token.isEmpty()) continue;
            // Skip multi-frame prefixes "0:", "1:", "2:", ...
            if (token.length() == 2 && token.charAt(1) == ':') continue;
            // Skip total-length headers (lone hex triplets like "014" before the frames)
            if (token.length() == 3 && isHex(token)) continue;
            if (isHex(token)) hex.append(token);
        }
        String hexStr = hex.toString().toUpperCase();
        if (hexStr.length() < 2) return Collections.emptyList();

        // Find the mode-response byte
        int idx = hexStr.indexOf(responseModeHex);
        if (idx < 0) return Collections.emptyList();
        int p = idx + 2;
        if (p + 2 > hexStr.length()) return Collections.emptyList();

        // Next byte = number of DTCs (some ECUs omit it; we treat remaining bytes as pairs if so)
        int count;
        try {
            count = Integer.parseInt(hexStr.substring(p, p + 2), 16);
        } catch (NumberFormatException e) {
            return Collections.emptyList();
        }
        p += 2;

        List<String> codes = new ArrayList<>();
        // Each DTC is 2 bytes (4 hex chars). Stop at count (or at end of buffer).
        int maxFromBytes = (hexStr.length() - p) / 4;
        int limit = (count > 0) ? Math.min(count, maxFromBytes) : maxFromBytes;
        for (int i = 0; i < limit; i++) {
            int hi = Integer.parseInt(hexStr.substring(p, p + 2), 16);
            int lo = Integer.parseInt(hexStr.substring(p + 2, p + 4), 16);
            p += 4;
            if (hi == 0 && lo == 0) continue; // padding
            codes.add(decodeDtc(hi, lo));
        }
        return codes;
    }

    /** Decode a 2-byte DTC into its 5-character form, e.g. (0x01, 0x33) -> "P0133". */
    public static String decodeDtc(int byteHi, int byteLo) {
        char letter = LETTERS[(byteHi >> 6) & 0x03];
        int d1 = (byteHi >> 4) & 0x03;
        int d2 = byteHi & 0x0F;
        int d3 = (byteLo >> 4) & 0x0F;
        int d4 = byteLo & 0x0F;
        return "" + letter + Integer.toHexString(d1).toUpperCase()
                + Integer.toHexString(d2).toUpperCase()
                + Integer.toHexString(d3).toUpperCase()
                + Integer.toHexString(d4).toUpperCase();
    }

    /** True if the Mode 04 clear response indicates success. */
    public static boolean isClearAcknowledged(String raw) {
        if (raw == null) return false;
        if (raw.toUpperCase().contains("OK")) return true;
        // 0x44 is the positive response byte for Mode 04 (0x40+0x04).
        // Match only as a standalone whitespace-delimited token, not as a
        // substring inside a longer data byte (e.g. "44XX" or "FF44").
        for (String token : raw.trim().toUpperCase().split("\\s+")) {
            if ("44".equals(token)) return true;
        }
        return false;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** Short human-readable hint for the most common generic DTCs. Returns null if unknown. */
    public static String lookupDescription(String code) {
        return DESCRIPTIONS.get(code);
    }

    private static final Map<String, String> DESCRIPTIONS = new HashMap<>();
    static {
        // P00xx — Fuel & air metering, auxiliary emission
        DESCRIPTIONS.put("P0010", "Camshaft Position Actuator Circuit (Bank 1)");
        DESCRIPTIONS.put("P0011", "Camshaft Position - Timing Over-Advanced (Bank 1)");
        DESCRIPTIONS.put("P0012", "Camshaft Position - Timing Over-Retarded (Bank 1)");
        DESCRIPTIONS.put("P0014", "Exhaust Camshaft Position - Timing Over-Advanced (Bank 1)");
        DESCRIPTIONS.put("P0015", "Exhaust Camshaft Position - Timing Over-Retarded (Bank 1)");
        DESCRIPTIONS.put("P0016", "Crankshaft / Camshaft Position Correlation (Bank 1 Sensor A)");
        DESCRIPTIONS.put("P0017", "Crankshaft / Camshaft Position Correlation (Bank 1 Sensor B)");
        DESCRIPTIONS.put("P0030", "HO2S Heater Control Circuit (Bank 1 Sensor 1)");
        DESCRIPTIONS.put("P0031", "HO2S Heater Control Circuit Low (Bank 1 Sensor 1)");
        DESCRIPTIONS.put("P0032", "HO2S Heater Control Circuit High (Bank 1 Sensor 1)");
        DESCRIPTIONS.put("P0036", "HO2S Heater Control Circuit (Bank 1 Sensor 2)");

        // P01xx — Fuel & air metering
        DESCRIPTIONS.put("P0100", "Mass Air Flow Circuit Malfunction");
        DESCRIPTIONS.put("P0101", "Mass Air Flow Circuit Range/Performance");
        DESCRIPTIONS.put("P0102", "Mass Air Flow Circuit Low Input");
        DESCRIPTIONS.put("P0103", "Mass Air Flow Circuit High Input");
        DESCRIPTIONS.put("P0105", "MAP/Barometric Pressure Circuit");
        DESCRIPTIONS.put("P0106", "MAP/Barometric Pressure Range/Performance");
        DESCRIPTIONS.put("P0107", "MAP/Barometric Pressure Low Input");
        DESCRIPTIONS.put("P0108", "MAP/Barometric Pressure High Input");
        DESCRIPTIONS.put("P0110", "Intake Air Temperature Circuit");
        DESCRIPTIONS.put("P0113", "Intake Air Temperature Circuit High");
        DESCRIPTIONS.put("P0115", "Engine Coolant Temperature Circuit");
        DESCRIPTIONS.put("P0117", "Engine Coolant Temperature Circuit Low");
        DESCRIPTIONS.put("P0118", "Engine Coolant Temperature Circuit High");
        DESCRIPTIONS.put("P0120", "Throttle/Pedal Position Sensor A Circuit");
        DESCRIPTIONS.put("P0121", "Throttle/Pedal Position Sensor A Range/Performance");
        DESCRIPTIONS.put("P0128", "Coolant Thermostat (Below Regulating Temperature)");
        DESCRIPTIONS.put("P0130", "O2 Sensor Circuit (Bank 1 Sensor 1)");
        DESCRIPTIONS.put("P0133", "O2 Sensor Circuit Slow Response (Bank 1 Sensor 1)");
        DESCRIPTIONS.put("P0134", "O2 Sensor Circuit No Activity Detected (Bank 1 Sensor 1)");
        DESCRIPTIONS.put("P0135", "O2 Sensor Heater Circuit (Bank 1 Sensor 1)");
        DESCRIPTIONS.put("P0136", "O2 Sensor Circuit (Bank 1 Sensor 2)");
        DESCRIPTIONS.put("P0137", "O2 Sensor Circuit Low Voltage (Bank 1 Sensor 2)");
        DESCRIPTIONS.put("P0138", "O2 Sensor Circuit High Voltage (Bank 1 Sensor 2)");
        DESCRIPTIONS.put("P0141", "O2 Sensor Heater Circuit (Bank 1 Sensor 2)");
        DESCRIPTIONS.put("P0150", "O2 Sensor Circuit (Bank 2 Sensor 1)");
        DESCRIPTIONS.put("P0153", "O2 Sensor Circuit Slow Response (Bank 2 Sensor 1)");
        DESCRIPTIONS.put("P0155", "O2 Sensor Heater Circuit (Bank 2 Sensor 1)");
        DESCRIPTIONS.put("P0156", "O2 Sensor Circuit (Bank 2 Sensor 2)");
        DESCRIPTIONS.put("P0161", "O2 Sensor Heater Circuit (Bank 2 Sensor 2)");
        DESCRIPTIONS.put("P0171", "System Too Lean (Bank 1)");
        DESCRIPTIONS.put("P0172", "System Too Rich (Bank 1)");
        DESCRIPTIONS.put("P0174", "System Too Lean (Bank 2)");
        DESCRIPTIONS.put("P0175", "System Too Rich (Bank 2)");

        // P02xx
        DESCRIPTIONS.put("P0201", "Injector Circuit/Open - Cylinder 1");
        DESCRIPTIONS.put("P0202", "Injector Circuit/Open - Cylinder 2");
        DESCRIPTIONS.put("P0203", "Injector Circuit/Open - Cylinder 3");
        DESCRIPTIONS.put("P0204", "Injector Circuit/Open - Cylinder 4");
        DESCRIPTIONS.put("P0205", "Injector Circuit/Open - Cylinder 5");
        DESCRIPTIONS.put("P0206", "Injector Circuit/Open - Cylinder 6");
        DESCRIPTIONS.put("P0217", "Engine Coolant Over-Temperature Condition");
        DESCRIPTIONS.put("P0218", "Transmission Over-Temperature Condition");

        // P03xx — Ignition
        DESCRIPTIONS.put("P0300", "Random/Multiple Cylinder Misfire Detected");
        DESCRIPTIONS.put("P0301", "Cylinder 1 Misfire Detected");
        DESCRIPTIONS.put("P0302", "Cylinder 2 Misfire Detected");
        DESCRIPTIONS.put("P0303", "Cylinder 3 Misfire Detected");
        DESCRIPTIONS.put("P0304", "Cylinder 4 Misfire Detected");
        DESCRIPTIONS.put("P0305", "Cylinder 5 Misfire Detected");
        DESCRIPTIONS.put("P0306", "Cylinder 6 Misfire Detected");
        DESCRIPTIONS.put("P0307", "Cylinder 7 Misfire Detected");
        DESCRIPTIONS.put("P0308", "Cylinder 8 Misfire Detected");
        DESCRIPTIONS.put("P0325", "Knock Sensor 1 Circuit (Bank 1)");
        DESCRIPTIONS.put("P0335", "Crankshaft Position Sensor A Circuit");
        DESCRIPTIONS.put("P0340", "Camshaft Position Sensor A Circuit (Bank 1)");

        // P04xx — Auxiliary emissions
        DESCRIPTIONS.put("P0401", "EGR Flow Insufficient Detected");
        DESCRIPTIONS.put("P0402", "EGR Flow Excessive Detected");
        DESCRIPTIONS.put("P0403", "EGR Control Circuit");
        DESCRIPTIONS.put("P0411", "Secondary Air Injection System - Incorrect Flow");
        DESCRIPTIONS.put("P0420", "Catalyst System Efficiency Below Threshold (Bank 1)");
        DESCRIPTIONS.put("P0421", "Warm Up Catalyst Efficiency Below Threshold (Bank 1)");
        DESCRIPTIONS.put("P0430", "Catalyst System Efficiency Below Threshold (Bank 2)");
        DESCRIPTIONS.put("P0440", "EVAP System Malfunction");
        DESCRIPTIONS.put("P0441", "EVAP Incorrect Purge Flow");
        DESCRIPTIONS.put("P0442", "EVAP System Leak Detected (Small)");
        DESCRIPTIONS.put("P0455", "EVAP System Leak Detected (Large)");
        DESCRIPTIONS.put("P0456", "EVAP System Leak Detected (Very Small)");

        // P05xx — Vehicle speed, idle
        DESCRIPTIONS.put("P0500", "Vehicle Speed Sensor A");
        DESCRIPTIONS.put("P0506", "Idle Control System RPM Lower Than Expected");
        DESCRIPTIONS.put("P0507", "Idle Control System RPM Higher Than Expected");

        // P06xx — Computer
        DESCRIPTIONS.put("P0601", "Internal Control Module Memory Checksum Error");
        DESCRIPTIONS.put("P0606", "ECM/PCM Processor Fault");

        // P07xx — Transmission
        DESCRIPTIONS.put("P0700", "Transmission Control System Malfunction");
        DESCRIPTIONS.put("P0715", "Input/Turbine Speed Sensor A Circuit");

        // Boost / turbo — common on 730i / 730li N52/N54/N55 BMW
        DESCRIPTIONS.put("P0234", "Turbo/Supercharger Overboost Condition");
        DESCRIPTIONS.put("P0299", "Turbo/Supercharger Underboost Condition");
    }
}
