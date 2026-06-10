package com.example.obd;
// Engine speed
// min value 0, max value 16,383.75, unit rpm, formula (256A+B)/4
public class RpmCommand extends ObdCommand {
    public RpmCommand() { super("010C"); }

    @Override public String getName() { return "RPM"; }
    @Override public String getUnit() { return "1/min"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 4) return 0;
        int A = Integer.parseInt(parts[2], 16);
        int B = Integer.parseInt(parts[3], 16);
        return ((A * 256) + B) / 4.0;
    }
}