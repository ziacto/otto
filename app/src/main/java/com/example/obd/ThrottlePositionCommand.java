package com.example.obd;
// Throttle position
// min value 0, max value 100, unit %, formula (100/255)A
public class ThrottlePositionCommand extends ObdCommand {
    public ThrottlePositionCommand() { super("0111"); }

    @Override public String getName() { return "Throttle Position"; }
    @Override public String getUnit() { return "%"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) return 0;
        int A = Integer.parseInt(parts[2], 16);
        return (A * 100.0) / 255.0;
    }
}