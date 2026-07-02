package com.example.obd;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One-shot workflow that walks candidate BMW UDS Mode 22 DIDs looking for a
 * plausible fuel level reading. On the BMW E65 the DME (engine ECU, D-CAN,
 * response 0x612) does not answer Mode 01 PID 2F, so fuel level has to come
 * from either:
 *   1. A cached copy inside the DME, exposed via one of several undocumented
 *      Mode 22 DIDs (community-reported candidates in {@link #CANDIDATE_DIDS}).
 *   2. The KOMBI instrument cluster on K-CAN, reachable only via a K-DCAN
 *      cable through the ZGM gateway. Our current setup can't reach that path.
 *
 * The probe reads every candidate, logs both the raw response and any
 * decoded percentage to {@code obd-diag.log}, and returns the best match
 * (a DID that returned a plausible 0-100 % value). If nothing matches, the
 * log tells us exactly what each DID answered so a follow-up session can
 * decode by hand.
 */
public final class FuelLevelProbe {

    private FuelLevelProbe() {}

    /** DIDs to try, in order. Add new candidates here as we find them. */
    private static final BmwMode22Pid.Did[] CANDIDATE_DIDS = {
            BmwMode22Pid.Did.FUEL_LEVEL_DME_400C,
            BmwMode22Pid.Did.FUEL_LEVEL_DME_4021,
            BmwMode22Pid.Did.FUEL_LEVEL_DME_402A,
            BmwMode22Pid.Did.FUEL_LEVEL_DME_5C40
    };

    public static class Result {
        public final BmwMode22Pid.Did bestMatch; // null if no candidate returned a plausible value
        public final Double bestValue;
        public final String report;
        Result(BmwMode22Pid.Did m, Double v, String r) {
            this.bestMatch = m;
            this.bestValue = v;
            this.report = r;
        }
    }

    /**
     * Blocking. Call off the UI thread. If the adapter isn't connected, the
     * probe aborts with a report saying so.
     */
    public static Result run(Context ctx, ObdManagerFast obdManager) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fuel level probe — trying ").append(CANDIDATE_DIDS.length).append(" DIDs\n");

        if (obdManager == null || !obdManager.isConnected()) {
            String msg = "aborted: OBD adapter not connected";
            ObdLogger.get().log(ObdLogger.Level.ERROR, "FuelLevelProbe " + msg);
            return new Result(null, null, sb.append(msg).toString());
        }

        BmwMode22Pid.Did best = null;
        Double bestVal = null;
        List<String> lines = new ArrayList<>();
        for (BmwMode22Pid.Did did : CANDIDATE_DIDS) {
            String rawResp;
            Double decoded;
            try {
                rawResp = obdManager.readUdsRawResponse(did.module, did.did);
            } catch (Exception e) {
                rawResp = "EXCEPTION: " + e.getMessage();
            }
            try {
                decoded = obdManager.readMode22Pid(did);
            } catch (Exception e) {
                decoded = null;
            }
            String status;
            if (decoded == null) {
                status = "no-data";
            } else if (decoded >= 0.0 && decoded <= 100.0) {
                status = String.format(java.util.Locale.US, "OK %.1f%%", decoded);
                // First plausible value wins — subsequent candidates may
                // return junk that happens to fall in range, but the ordered
                // candidate list means the most likely DID is tested first.
                if (best == null) { best = did; bestVal = decoded; }
            } else {
                status = String.format(java.util.Locale.US,
                        "out-of-range (%.1f)", decoded);
            }
            String line = String.format("  %-14s (DID 0x%04X): %s | raw=%s",
                    did.name(), did.did, status,
                    rawResp == null ? "" : (rawResp.length() > 80 ? rawResp.substring(0, 80) + "…" : rawResp));
            lines.add(line);
            ObdLogger.get().log(ObdLogger.Level.INFO, "FuelProbe " + line.trim());
        }
        for (String l : lines) sb.append(l).append('\n');
        if (best != null) {
            String w = String.format(java.util.Locale.US,
                    "→ Best match: %s (%.1f %%)", best.name(), bestVal);
            sb.append(w).append('\n');
            ObdLogger.get().log(ObdLogger.Level.INFO, "FuelProbe " + w);
        } else {
            sb.append("→ No candidate returned a plausible reading.\n");
            sb.append("  Try running the probe with the engine off + ignition on,\n");
            sb.append("  and share the log lines above so a KOMBI-side DID can be added.\n");
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "FuelProbe FAIL — no DID returned 0-100 %");
        }
        return new Result(best, bestVal, sb.toString());
    }

    /** DIDs in the order the probe tries them — used by the drawer report screen. */
    public static List<BmwMode22Pid.Did> candidates() {
        return Arrays.asList(CANDIDATE_DIDS);
    }
}
