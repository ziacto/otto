package com.example.obd;
// Actual engine - percent torque
// min value -125, max value 130, unit %, formula A-125
public class ActualEngineTorqueCommand extends ObdCommand {
    public ActualEngineTorqueCommand() { super("0162"); }

    @Override public String getName() { return "Engine Torque"; }
    @Override public String getUnit() { return "%"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) return 0;
        int A = Integer.parseInt(parts[2], 16);
        return A - 125.0;
    }
}
