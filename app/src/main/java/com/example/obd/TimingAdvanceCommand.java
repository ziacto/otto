package com.example.obd;
// Timing advance
// min value -64, max value 63.5, unit ° before TDC, formula (A/2)-64
public class TimingAdvanceCommand extends ObdCommand {
    public TimingAdvanceCommand() { super("010E"); }

    @Override public String getName() { return "Timing Advance"; }
    @Override public String getUnit() { return "° BTDC"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        return (A / 2.0) - 64.0;
    }
}