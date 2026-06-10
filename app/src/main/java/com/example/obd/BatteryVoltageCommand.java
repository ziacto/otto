package com.example.obd;

public class BatteryVoltageCommand extends ObdCommand {
    // ATRV reads the voltage at the OBD adapter, this equals the Vehicle battery.
    // PID 0142 ("control module voltage") not supported by many car models
    // Thats why I choose to use ATRV
    public BatteryVoltageCommand() { super("ATRV"); }

    @Override public String getName() { return "Battery Voltage"; }
    @Override public String getUnit() { return "V"; }

    @Override
    public double parseResult(String rawResponse) {
        // clean response
        String clean = rawResponse.replaceAll("[^0-9.]", "").trim();
        if (clean.isEmpty()) return 0;
        try {
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
