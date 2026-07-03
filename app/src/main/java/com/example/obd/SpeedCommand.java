package com.example.obd;
// Vehicle speed
// min value 0, max value 255, unit km/h, formula A
public class SpeedCommand extends ObdCommand {
    public SpeedCommand() { super("010D"); }

    @Override public String getName() { return "Vehicle Speed"; }
    @Override public String getUnit() { return "km/h"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        return Integer.parseInt(parts[2], 16);
    }
}