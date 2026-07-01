package com.example.obd;

/**
 * Response generator for {@link SimulatedObdTransport}. Turns an ELM/OBD
 * request string into the exact ASCII payload a healthy ELM327 hooked to a
 * warm-idling BMW E65 730li would emit.
 *
 * Value semantics matter — the app's parsers do real math on these bytes, so
 * a sloppy answer produces a plausible-but-wrong dashboard (e.g. Coolant=95°C
 * even though the byte says 0x77 which is 79°C). Values are ramped by a
 * simple wall-clock model so the dashboard shows motion instead of a static
 * still-life.
 */
public final class SimEngine {

    private final long start = System.currentTimeMillis();

    /**
     * @param req the raw command up to but not including '\r'. May be an
     *            AT command ("ATZ", "ATE0", "ATSP0") or a Mode 01/09/etc.
     *            request in hex ("0100", "010C").
     * @return the ASCII payload the app should read next, or "?" for an
     *         unknown request. The transport appends "\r>" so callers do
     *         not need to include the prompt marker.
     */
    public String respond(String req) {
        if (req == null) return "?";
        String r = req.toUpperCase().replaceAll("\\s+", "");

        // ELM AT commands
        if (r.equals("ATZ"))    return "ELM327 v2.2";
        if (r.equals("ATE0"))   return "OK";
        if (r.equals("ATL0"))   return "OK";
        if (r.equals("ATH0"))   return "OK";
        if (r.equals("ATH1"))   return "OK";
        if (r.equals("ATS0"))   return "OK";
        if (r.equals("ATS1"))   return "OK";
        if (r.startsWith("ATST")) return "OK";
        if (r.startsWith("ATAT")) return "OK";
        if (r.startsWith("ATCAF")) return "OK";
        if (r.startsWith("ATSP")) return "OK";
        if (r.startsWith("ATSH")) return "OK";
        if (r.startsWith("ATCRA")) return "OK";
        if (r.startsWith("ATFCS")) return "OK";
        if (r.equals("ATAR"))   return "OK";
        if (r.equals("ATRV"))   return String.format(java.util.Locale.US, "%.1fV", batteryV());
        if (r.equals("ATDPN"))  return "6"; // ISO 15765-4 CAN 11/500
        if (r.equals("ATDP"))   return "ISO 15765-4 (CAN 11/500)";

        // Mode 01 requests (2-byte command 01<PID>)
        if (r.startsWith("01") && r.length() == 4) {
            int pid;
            try { pid = Integer.parseInt(r.substring(2, 4), 16); }
            catch (NumberFormatException e) { return "NO DATA"; }
            return mode01(pid);
        }

        // Mode 09 requests
        if (r.startsWith("09") && r.length() == 4) {
            int pid;
            try { pid = Integer.parseInt(r.substring(2, 4), 16); }
            catch (NumberFormatException e) { return "NO DATA"; }
            return mode09(pid);
        }

        // Mode 03 (DTCs), 04 (clear), 07 (pending), 0A (permanent)
        if (r.equals("03"))     return "43 00"; // no stored DTCs
        if (r.equals("04"))     return "44";    // clear ack
        if (r.equals("07"))     return "47 00";
        if (r.equals("0A"))     return "4A 00";

        // BMW module reads (UDS 19/22/31 …) — respond with "NO DATA" so higher-
        // level code cleanly reports "module didn't answer" instead of stalling.
        return "NO DATA";
    }

    // ============ Time-varying values ============

    /** Wall-clock phase, 0..1, over a 60-second cycle. Drives all ramps. */
    private double phase() {
        long dt = System.currentTimeMillis() - start;
        return (dt % 60_000L) / 60_000.0;
    }

    private double rpm() {
        // Idles at 780, revs to 3600, back to idle — smooth cosine sweep.
        double p = phase();
        double t = 0.5 - 0.5 * Math.cos(2 * Math.PI * p);
        return 780.0 + t * (3600.0 - 780.0);
    }

    private double speedKmh() {
        // Follows RPM roughly but with slower ramp so it looks like a real drive.
        double p = phase();
        if (p < 0.15) return 0.0;
        if (p > 0.85) return 0.0;
        double t = (p - 0.15) / 0.7;
        return 90.0 * (0.5 - 0.5 * Math.cos(Math.PI * t));
    }

    private double coolantC() {
        // Warms from 60 to 92°C across the first two minutes, holds after.
        long secs = (System.currentTimeMillis() - start) / 1000;
        if (secs > 120) return 92.0 + Math.sin(secs / 30.0) * 1.5;
        return 60.0 + (32.0 * secs) / 120.0;
    }

    private double oilC()          { return coolantC() + 4.0; }
    private double intakeC()       { return 26.0 + Math.sin(phase() * 6.28) * 3.0; }
    private double ambientC()      { return 24.0; }
    private double throttlePct()   {
        double p = phase();
        return 12.0 + 60.0 * Math.max(0, Math.sin(p * Math.PI * 2));
    }
    private double loadPct()       { return throttlePct() * 0.85 + 8.0; }
    private double mafGps()        { return 3.0 + throttlePct() * 0.5; }
    private double fuelLevelPct()  { return 68.0; }
    private double batteryV()      { return 14.1 + Math.sin(phase() * 6.28) * 0.15; }
    private double timingBtdc()    { return 8.0 + throttlePct() * 0.15; }
    private double engineTorqPct() { return loadPct() * 0.9; }
    private double lambda()        { return 1.00 + Math.sin(phase() * 6.28) * 0.02; }
    private double shortFuelTrim() { return -1.5 + Math.sin(phase() * 6.28) * 1.0; }
    private double longFuelTrim()  { return  0.5; }

    // ============ Mode 01 responses ============

    private String mode01(int pid) {
        switch (pid) {
            case 0x00: return hex("41 00", 0x80, 0x18, 0x00, 0x11);
                       // supports 01, 0C, 0D — legacy answer to keep protocol probe happy
            case 0x01: {
                // MIL off, 0 DTCs, monitors ready
                return hex("41 01", 0x00, 0x07, 0xFF, 0x00);
            }
            case 0x04: return hex("41 04", byteFromPct(loadPct()));
            case 0x05: return hex("41 05", byteFromTemp(coolantC()));
            case 0x06: return hex("41 06", (int) (128 + shortFuelTrim() * 1.28));
            case 0x07: return hex("41 07", (int) (128 + longFuelTrim() * 1.28));
            case 0x0C: {
                int val = (int) Math.round(rpm() * 4.0);
                return hex("41 0C", (val >> 8) & 0xFF, val & 0xFF);
            }
            case 0x0D: return hex("41 0D", clamp((int) Math.round(speedKmh()), 0, 255));
            case 0x0E: return hex("41 0E", (int) (128 + timingBtdc() * 2));
            case 0x0F: return hex("41 0F", byteFromTemp(intakeC()));
            case 0x10: {
                int val = (int) Math.round(mafGps() * 100.0);
                return hex("41 10", (val >> 8) & 0xFF, val & 0xFF);
            }
            case 0x11: return hex("41 11", byteFromPct(throttlePct()));
            case 0x1F: {
                // engine run time — seconds since sim start
                int secs = (int) ((System.currentTimeMillis() - start) / 1000);
                return hex("41 1F", (secs >> 8) & 0xFF, secs & 0xFF);
            }
            case 0x21: {
                // distance since MIL — 0
                return hex("41 21", 0x00, 0x00);
            }
            case 0x24: // O2 sensor 1 wide range (lambda + voltage)
            case 0x34: {
                int lam = (int) Math.round(lambda() * 32768.0);
                return hex("41 " + String.format("%02X", pid),
                        (lam >> 8) & 0xFF, lam & 0xFF, 0x80, 0x00);
            }
            case 0x2C: return hex("41 2C", byteFromPct(50.0)); // commanded EGR
            case 0x2F: return hex("41 2F", byteFromPct(fuelLevelPct()));
            case 0x30: return hex("41 30", 3); // warmups since DTC clear
            case 0x31: return hex("41 31", 0x01, 0x2C); // distance since DTC clear
            case 0x33: return hex("41 33", 100); // barometric kPa
            case 0x42: return hex("41 42", (int) (batteryV() * 1000) >> 8 & 0xFF,
                                                (int) (batteryV() * 1000) & 0xFF);
            case 0x45: return hex("41 45", byteFromPct(throttlePct() * 0.9));
            case 0x46: return hex("41 46", byteFromTemp(ambientC()));
            case 0x49: return hex("41 49", byteFromPct(throttlePct() * 1.05));
            case 0x5C: return hex("41 5C", byteFromTemp(oilC()));
            case 0x61: return hex("41 61", (int) (125 + engineTorqPct())); // driver demand
            case 0x62: return hex("41 62", (int) (125 + engineTorqPct())); // actual torque
            case 0x5E: return hex("41 5E", ((int) (mafGps() * 20 * 20)) >> 8 & 0xFF,
                                                ((int) (mafGps() * 20 * 20)) & 0xFF);
            default:
                // Simulate NRC "sub-function not supported" for unknown PIDs so the
                // NRC filter in ObdCommand gets exercised too.
                return "7F 01 12";
        }
    }

    private String mode09(int pid) {
        switch (pid) {
            case 0x02:
                // Real ELM emits multi-frame ISO-TP for VIN. Simplify to the
                // decoded form the app already tolerates. WBAHL61050D T12345.
                return "49 02 01 00 00 00 57 42 41 48 4C 36 31 30 35 30 44 54 31 32 33 34 35";
            case 0x04:
                return "49 04 01 00 00 42 4D 57 45 36 35"; // "BMWE65"
            case 0x0A:
                return "49 0A 01 " + toAscii("N52 DME");
            default:
                return "NO DATA";
        }
    }

    // ============ helpers ============

    private static int byteFromTemp(double celsius) { return clamp((int) Math.round(celsius + 40), 0, 255); }
    private static int byteFromPct(double pct)      { return clamp((int) Math.round(pct * 2.55), 0, 255); }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }

    private static String hex(String prefix, int... bytes) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int b : bytes) sb.append(' ').append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }

    /** Convert an ASCII string to " HH HH HH ..." hex bytes for Mode 09 replies. */
    private static String toAscii(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", (int) s.charAt(i)));
        }
        return sb.toString();
    }
}
