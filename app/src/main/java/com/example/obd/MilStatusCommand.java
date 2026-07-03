package com.example.obd;

/**
 * Mode 01 PID 0x01 — Monitor status since DTCs cleared.
 *
 * Returns 4 bytes A B C D. We care primarily about byte A:
 *   bit 7 = MIL (check engine light) ON / OFF
 *   bits 0-6 = number of DTCs currently stored
 *
 * We pack the result into a single double so it fits the ObdCommand contract:
 *   value = dtcCount + (MIL_ON ? 1000 : 0)
 *
 * Consumers (gauge dashboard warning lights) decode with:
 *   boolean milOn = value >= 1000;
 *   int    count  = (int)(value % 1000);
 *
 * This is the cheapest way to know "is the engine warning light on the cluster
 * lit up right now?" — Mode 01 PID 0x01 is supported by every OBD-II module.
 */
public class MilStatusCommand extends ObdCommand {
    public MilStatusCommand() { super("0101"); }

    @Override public String getName() { return "MIL Status"; }
    @Override public String getUnit() { return ""; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        int count = A & 0x7F;
        boolean milOn = (A & 0x80) != 0;
        return (milOn ? 1000 : 0) + count;
    }

    /** Decode helpers for callers. */
    public static boolean isMilOn(double value) { return value >= 1000; }
    public static int dtcCount(double value) { return (int) (value % 1000); }
}
