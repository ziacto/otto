package com.example.obd;
// Short term fuel trim (STFT)—Bank 1 
// min value -100 (Reduce Fuel: Too Rich), max value 99.2 (Add Fuel: Too Lean), unit %, formula (100/128)A − 100
public class ShortTermFuelTrimCommand extends ObdCommand {
    public ShortTermFuelTrimCommand() { super("0106"); }

    @Override public String getName() { return "Short Term Fuel Trim"; }
    @Override public String getUnit() { return "%"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) return 0;
        int A = Integer.parseInt(parts[2], 16);
        return (100.0 / 128.0) * A - 100.0;
    }
}