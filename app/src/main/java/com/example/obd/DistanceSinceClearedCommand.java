package com.example.obd;

/**
 * Mode 01 PID 0x31 — Distance since DTCs cleared (km).
 *
 * Returns 2 bytes A and B; value = (256A + B) km. Range 0-65535 km, resolution 1 km.
 *
 * On E65 this is NOT the total odometer — that lives in KOMBI on K-CAN and is
 * unreachable via OBD. This PID resets to 0 every time you clear DTCs. Useful for:
 *   • Confirming a repair is durable: "I cleared codes and have driven X km clean"
 *   • A floor estimate when the cluster odometer is unreadable
 *   • Detecting a tampered car: if this number is very high vs the cluster, fault
 *     codes haven't been cleared in a long time
 */
public class DistanceSinceClearedCommand extends ObdCommand {
    public DistanceSinceClearedCommand() { super("0131"); }

    @Override public String getName() { return "Distance Since Cleared"; }
    @Override public String getUnit() { return "km"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 4) return 0;
        int A = Integer.parseInt(parts[2], 16);
        int B = Integer.parseInt(parts[3], 16);
        return (A * 256) + B;
    }
}
