package com.example.obd;
//Intake manifold absolute pressure
// min value 0, max value 255, unit kPa, formula A 
public class BoostPressureCommand extends ObdCommand {
    public BoostPressureCommand() { super("010B"); }

    @Override public String getName() { return "Boost Pressure"; }
    @Override public String getUnit() { return "bar"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int kpa = Integer.parseInt(parts[2], 16);
        return kpa / 100.0; // Convert kPa to bar
    }
}