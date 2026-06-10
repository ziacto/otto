package com.example.obd;
// absolute barometric pressure
// min value 0, max value 255, unit kPA, formula A
public class BarometricPressureCommand extends ObdCommand {
    public BarometricPressureCommand() { super("0133"); }

    @Override public String getName() { return "Barometric Pressure"; }
    @Override public String getUnit() { return "kPa"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) return 0;
        int A = Integer.parseInt(parts[2], 16);
        return A;
    }
}
