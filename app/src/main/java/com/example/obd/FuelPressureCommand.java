package com.example.obd;
// Fuel pressure, gauge pressure
// min vlaue 0, max value 765, unit kPa, formula 3A
public class FuelPressureCommand extends ObdCommand{
    public FuelPressureCommand() {super("010A");}

    @Override public String getName() { return "Fuel Pressure"; }
    @Override public String getUnit() { return "kPa"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        return A * 3.0; //kPa
    }
}