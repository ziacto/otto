package com.example.obd;
// Driver's demand engine – percent torque (what the driver requests).
// Compare with ActualEngineTorqueCommand to see if turbo delivers the demanded torque.
// PID 0x61, 1 byte, -125 to 130%, formula A-125
public class DriverDemandTorqueCommand extends ObdCommand {
    public DriverDemandTorqueCommand() { super("0161"); }

    @Override public String getName() { return "Demand Torque"; }
    @Override public String getUnit() { return "%"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) return 0;
        int A = Integer.parseInt(parts[2], 16);
        return A - 125.0;
    }
}