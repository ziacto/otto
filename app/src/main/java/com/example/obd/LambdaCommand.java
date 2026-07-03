package com.example.obd;
// Oxygen Sensor 1 Ab: Air-Fuel Equivalence Ratio(lamda)
// min value 0 -128, max value <2 <128, unit ratio mA, formula 2/65536(256A+B), (256C+D)/256-128
public class LambdaCommand extends ObdCommand {
    public LambdaCommand() { super("0134"); }

    @Override public String getName() { return "Lambda"; }//(Air-Fuel Ratio)
    @Override public String getUnit() { return "λ"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 4) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        int B = Integer.parseInt(parts[3], 16);
        return ((256 * A) + B) / 32768.0;
    }
}