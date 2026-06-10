package com.example.obd;
// Engine oil temperature
// min value -40, max value 210, unit °C, formula A-40
public class OilTempCommand extends ObdCommand {
    public OilTempCommand() { super("015C"); }

    @Override public String getName() { return "Oil Temp"; }
    @Override public String getUnit() { return "°C"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) return 0;
        return Integer.parseInt(parts[2], 16) - 40;
    }
}