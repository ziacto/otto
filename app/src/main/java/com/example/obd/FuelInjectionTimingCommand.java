package com.example.obd;
// Fuel injection timing – angle of injection relative to TDC.
// PID 0x5D, 2 bytes, -210 to 301.992°, formula (256A+B)/128 - 210
public class FuelInjectionTimingCommand extends ObdCommand {
    public FuelInjectionTimingCommand() { super("015D"); }

    @Override public String getName() { return "Injection Timing"; }
    @Override public String getUnit() { return "°"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 4) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        int B = Integer.parseInt(parts[3], 16);
        return (256 * A + B) / 128.0 - 210.0;
    }
}