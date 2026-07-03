package com.example.obd;
// Mass air flow sensor air flow rate
// min value 0, max value 655.35, unit g/s, formula (256A+B)/100
public class MassAirFlowCommand extends ObdCommand {
    public MassAirFlowCommand() { super("0110"); }

    @Override public String getName() { return "Mass Air Flow"; }
    @Override public String getUnit() { return "g/s"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 4) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        int B = Integer.parseInt(parts[3], 16);
        return ((A * 256.0) + B) / 100.0;
    }
}