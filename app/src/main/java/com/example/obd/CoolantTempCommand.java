package com.example.obd;
// Engine coolant temperature 
// min value -40, max value 215, unit °C, formula A-40
public class CoolantTempCommand extends ObdCommand {
    public CoolantTempCommand() { super("0105"); }

    @Override public String getName() { return "Coolant Temp"; }
    @Override public String getUnit() { return "°C"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) return 0;
        return Integer.parseInt(parts[2], 16) - 40;
    }
}