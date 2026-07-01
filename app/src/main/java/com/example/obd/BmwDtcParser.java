package com.example.obd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * UDS service 0x19 sub-function 0x02 (read DTC by status mask) response parser.
 * Response format per ISO 14229: positive response = 0x59 0x02 [mask] [DTC1_hi DTC1_mid DTC1_lo status]+
 *
 * BMW DTCs from non-DME modules are 3-byte vs the 2-byte powertrain (Mode 03) format.
 * We render them as a hex 6-character code prefixed with the module-letter convention
 * BMW themselves use in ISTA (e.g. "5E20" for a DSC code, "4F86" for an EGS code).
 *
 * Because manufacturer code lookups are out of scope, we surface raw codes; the
 * fault-codes UI can later overlay descriptions from a BMW-specific dictionary.
 */
public final class BmwDtcParser {

    private BmwDtcParser() {}

    public static List<String> parseUdsDtcResponse(String raw) {
        if (raw == null) return Collections.emptyList();
        String hex = ObdUtil.collectHex(raw);
        if (hex.isEmpty()) return Collections.emptyList();

        int idx = hex.indexOf("5902");
        if (idx < 0) return Collections.emptyList();
        int p = idx + 4 + 2; // skip 59 02 + status-availability mask byte
        List<String> codes = new ArrayList<>();
        // DTC entries are 4 bytes each: 3 bytes DTC + 1 byte status
        while (p + 8 <= hex.length()) {
            String dtcHex = hex.substring(p, p + 6);
            // status byte at p+6..p+8 — ignored for surface listing
            if (!"000000".equals(dtcHex)) {
                codes.add(formatBmwDtc(dtcHex));
            }
            p += 8;
        }
        return codes;
    }

    /** Render 3-byte UDS DTC as a 6-character hex string matching BMW ISTA format (e.g. "2F4100"). */
    private static String formatBmwDtc(String h6) {
        // Keep all 3 bytes — the first byte is the high byte of the DTC group code,
        // not a module prefix. Dropping it collapses distinct codes that share the
        // same lower 2 bytes (e.g. 0x2F4100 vs 0x4F4100 would both become "4100").
        return h6.length() == 6 ? h6 : h6;
    }
}
