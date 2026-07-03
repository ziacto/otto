package com.example.obd;
// Commanded Air-Fuel Equivalence Ratio (target lambda set by ECU).
// Compare with LambdaCommand (actual) to see fuel trim behavior.
// PID 0x44, 2 bytes, 0 to <2, formula (2/65536)*(256A+B)
public class CommandedLambdaCommand extends ObdCommand {
    public CommandedLambdaCommand() { super("0144"); }

    @Override public String getName() { return "Commanded Lambda"; }
    @Override public String getUnit() { return "λ"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 4) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        int B = Integer.parseInt(parts[3], 16);
        return (2.0 / 65536.0) * (256 * A + B);
    }
}