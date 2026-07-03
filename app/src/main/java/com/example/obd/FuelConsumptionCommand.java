package com.example.obd;
// Engine fuel rate 
// min vlaue 0, max value 3212.75, unit L/h, formula (256A+B)/20
// NO DATA if not supported
public class FuelConsumptionCommand extends ObdCommand {
    public FuelConsumptionCommand() { super("015E"); }

    @Override public String getName() { return "Fuel Consumption"; }
    @Override public String getUnit() { return "L/h"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 4) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        int B = Integer.parseInt(parts[3], 16);
        return (256 * A + B) / 20.0;
    }
}