package com.example.obd;
// Control module voltage 
// min value 0, max value 65.535, unit V, formula (256A+B)/1000 
// NO DATA is silently skipped if the vehicle doesn't support this PID
public class ControlModuleVoltageCommand extends ObdCommand {
    public ControlModuleVoltageCommand() { super("0142"); }

    @Override public String getName() { return "ECU Voltage"; }
    @Override public String getUnit() { return "V"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 4) throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        int A = Integer.parseInt(parts[2], 16);
        int B = Integer.parseInt(parts[3], 16);
        return (256 * A + B) / 1000.0;
    }
}
