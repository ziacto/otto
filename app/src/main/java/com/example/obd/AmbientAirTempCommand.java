package com.example.obd;
// Ambient air temperature
// min value -40, max value 215 units °C, formula A-40
public class AmbientAirTempCommand extends ObdCommand {
    public AmbientAirTempCommand() { super("0146"); }

    @Override public String getName() { return "Ambient Air Temperature"; }
    @Override public String getUnit() { return "°C"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        return A - 40.0;
    }
}
