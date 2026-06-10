package com.example.obd;
// Fuel Tank Level Input 
// min value 0, max value 100, unit %, formula (100/255)A
public class FuelLevelCommand extends ObdCommand {
    public FuelLevelCommand() { super("012F"); }

    @Override public String getName() { return "Fuel Level"; }
    @Override public String getUnit() { return "%"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) return 0;
        int A = Integer.parseInt(parts[2], 16);
        return (100.0/255.0)*A;
    }
}