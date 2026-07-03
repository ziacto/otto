package com.example.obd;
// Intake air temperature
// min value -40, max value 215, unit °C, formula A − 40
public class ChargeAirTemperatureCommand extends ObdCommand {
    public ChargeAirTemperatureCommand() { super("010F"); }

    @Override public String getName() { return "Charge Air Temperature"; }
    @Override public String getUnit() { return "°C"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        return A  - 40.0;
    }
}
