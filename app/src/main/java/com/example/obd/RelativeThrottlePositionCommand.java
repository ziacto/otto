package com.example.obd;
// Relative throttle position – calibrated to learned closed position; more accurate than absolute.
// PID 0x45, 1 byte, 0-100%, formula (100/255)*A
public class RelativeThrottlePositionCommand extends ObdCommand {
    public RelativeThrottlePositionCommand() { super("0145"); }

    @Override public String getName() { return "Relative Throttle"; }
    @Override public String getUnit() { return "%"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        return (A * 100.0) / 255.0;
    }
}