package com.example.obd;
// Oxygen sensor 1 
// A:voltage B: Short term fuel trim, min value 0 -100, max value 1.275 99.2, unit V %, formula A/200 100/128B-100
public class O2SensorCommand extends ObdCommand {
    public O2SensorCommand() { super("0114"); }

    @Override public String getName() { return "O2 Sensor"; }// (B1S1)
    @Override public String getUnit() { return "V"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 4) return 0;
        int A = Integer.parseInt(parts[2], 16);
        return A / 200.0; // O2 voltage
    }
}