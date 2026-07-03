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
        // clean response — never fabricate 0 V from garbage; a fake dead-battery
        // reading would flow into trends/dashboards as real data.
        String clean = rawResponse.replaceAll("[^0-9.]", "").trim();
        if (clean.isEmpty()) {
            throw new IllegalStateException("Truncated response [" + getName() + "]: " + rawResponse);
        }
        try {
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Malformed response [" + getName() + "]: " + rawResponse);
        }
    }
}
