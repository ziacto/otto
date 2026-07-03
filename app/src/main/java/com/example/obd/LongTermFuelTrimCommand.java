package com.example.obd;
//Long term fuel trim (LTFT)—Bank 1 
// min value -100 (Reduce Fuel: Too Rich), max value 99.2 (Add Fuel: Too Lean), unit %, formula (100/128)A − 100
public class LongTermFuelTrimCommand extends ObdCommand {
    public LongTermFuelTrimCommand() { super("0107"); }

    @Override public String getName() { return "Long Term Fuel Trim"; }
    @Override public String getUnit() { return "%"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        return (100.0 / 128.0) * A - 100.0;
    }
}