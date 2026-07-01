package com.example.obd;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BMW UDS Mode 22 (ReadDataByIdentifier) DID registry for E60/E65/E90-era cars.
 *
 * Each entry binds a 16-bit DID to a label, unit, and a pure decoder function.
 * Requests are issued on D-CAN with ATSH 6F1 + ATCRA &lt;module response id&gt; — see
 * {@link ObdManagerFast#readMode22Pid}. ELM327 must be in CAF1 mode so the adapter
 * assembles multi-frame ISO-TP responses on our behalf.
 *
 * <p>DID values below are sourced from community INPA/BMHat dumps for E65 (DME MSV70/MSV80,
 * EGS GS19, IBS). They are <b>not</b> guaranteed across model years — log the raw response
 * and verify against a live car before trusting any new entry. Adding a DID is free; getting
 * the scaling wrong is a misdiagnosis. When in doubt, leave the entry off and read raw bytes.
 */
public final class BmwMode22Pid {

    private BmwMode22Pid() {}

    /** Pure decoder from data bytes (post-service-code, post-DID) to a numeric value. */
    public interface Decoder {
        Double decode(byte[] data);
    }

    public enum Did {
        // --- DME (engine) ---
        VANOS_INTAKE_ANGLE(
                BmwModule.DME, 0x4138, "VANOS Intake", "°CA",
                d -> d.length >= 1 ? (d[0] & 0xFF) * 0.375 - 50.0 : null),

        VANOS_EXHAUST_ANGLE(
                BmwModule.DME, 0x4139, "VANOS Exhaust", "°CA",
                d -> d.length >= 1 ? (d[0] & 0xFF) * 0.375 - 50.0 : null),

        // N52B30 low-pressure returnless fuel rail (350-400 kPa / 3.5-4.0 bar).
        // 2-byte value × 0.1 = bar. Normal range at idle: ~3.5 bar.
        FUEL_RAIL_PRESSURE(
                BmwModule.DME, 0x4304, "Fuel Rail Pressure", "bar",
                d -> d.length >= 2 ? ((d[0] & 0xFF) * 256 + (d[1] & 0xFF)) * 0.1 : null),

        MAF_TARGET(
                BmwModule.DME, 0x418D, "MAF Target", "g/s",
                d -> d.length >= 2 ? ((d[0] & 0xFF) * 256 + (d[1] & 0xFF)) / 100.0 : null),

        // Per-cylinder misfire counters. Counts since last clear.
        // Note: MSV70 base DID is 0x4151; MSV80 same. If all return 0/NO DATA,
        // check DME variant — MSV70 early builds may not expose these.
        CYL1_MISFIRE(BmwModule.DME, 0x4151, "Cyl 1 Misfire", "ct",
                d -> d.length >= 2 ? (double) ((d[0] & 0xFF) * 256 + (d[1] & 0xFF)) : null),
        CYL2_MISFIRE(BmwModule.DME, 0x4152, "Cyl 2 Misfire", "ct",
                d -> d.length >= 2 ? (double) ((d[0] & 0xFF) * 256 + (d[1] & 0xFF)) : null),
        CYL3_MISFIRE(BmwModule.DME, 0x4153, "Cyl 3 Misfire", "ct",
                d -> d.length >= 2 ? (double) ((d[0] & 0xFF) * 256 + (d[1] & 0xFF)) : null),
        CYL4_MISFIRE(BmwModule.DME, 0x4154, "Cyl 4 Misfire", "ct",
                d -> d.length >= 2 ? (double) ((d[0] & 0xFF) * 256 + (d[1] & 0xFF)) : null),
        CYL5_MISFIRE(BmwModule.DME, 0x4155, "Cyl 5 Misfire", "ct",
                d -> d.length >= 2 ? (double) ((d[0] & 0xFF) * 256 + (d[1] & 0xFF)) : null),
        CYL6_MISFIRE(BmwModule.DME, 0x4156, "Cyl 6 Misfire", "ct",
                d -> d.length >= 2 ? (double) ((d[0] & 0xFF) * 256 + (d[1] & 0xFF)) : null),

        // --- EGS (transmission) ---
        // DID 0x4119 belongs to the EGS (GS19 TCU), not DME. Routing to DME returns NO DATA.
        TRANS_FLUID_TEMP(
                BmwModule.EGS, 0x4119, "Trans Fluid Temp", "°C",
                d -> d.length >= 1 ? (double) ((d[0] & 0xFF) - 50) : null);

        public final BmwModule module;
        public final int did;          // 16-bit identifier
        public final String label;
        public final String unit;
        public final Decoder decoder;

        Did(BmwModule module, int did, String label, String unit, Decoder decoder) {
            this.module = module;
            this.did = did;
            this.label = label;
            this.unit = unit;
            this.decoder = decoder;
        }

        /** Hex string of the 16-bit DID, e.g. "4119". */
        public String didHex() { return String.format("%04X", did); }
    }

    /**
     * Extract the data bytes for a Mode 22 response, i.e. the payload after the
     * "62 HI LO" UDS service-code + DID echo. Returns an empty array if the response
     * does not contain a positive answer for the requested DID.
     *
     * The raw response may include ELM ISO-TP framing prefixes ("0:", "1:"…), the
     * BMW source-byte echo (e.g. "F1"), or line breaks — same shape as
     * {@link DtcUtil#parseDtcResponse}. Uses {@link ObdUtil#collectHex} to normalize.
     */
    public static byte[] parseMode22Data(String raw, int did) {
        if (raw == null) return new byte[0];
        String u = raw.toUpperCase();
        if (u.contains("NO DATA") || u.contains("CAN ERROR") || u.contains("BUS")) {
            return new byte[0];
        }
        String hex = ObdUtil.collectHex(raw);
        String marker = String.format("62%04X", did);
        int idx = hex.indexOf(marker);
        if (idx < 0) return new byte[0];
        int p = idx + marker.length();
        int byteCount = (hex.length() - p) / 2;
        byte[] out = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            try {
                out[i] = (byte) Integer.parseInt(hex.substring(p + i * 2, p + i * 2 + 2), 16);
            } catch (NumberFormatException e) {
                return Arrays.copyOf(out, i);
            }
        }
        return out;
    }

    /** Decode a Mode 22 response into a label→formatted-value map for one or more DIDs. */
    public static Map<String, String> formatReadings(Map<Did, Double> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<Did, Double> e : values.entrySet()) {
            Did d = e.getKey();
            Double v = e.getValue();
            out.put(d.label, v == null ? "—" : String.format("%.1f %s", v, d.unit));
        }
        return out;
    }
}
