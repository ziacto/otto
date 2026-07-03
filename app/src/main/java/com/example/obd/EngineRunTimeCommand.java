package com.example.obd;
// Run time since engine start
// min value 0, max value 65,535, unit s, formula 256A+B
public class EngineRunTimeCommand extends ObdCommand {
    public EngineRunTimeCommand() { super("011F"); }

    @Override public String getName() { return "Engine Run Time"; }
    @Override public String getUnit() { return "s"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 4) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        int B = Integer.parseInt(parts[3], 16);
        return 256 * A + B;
    }
}