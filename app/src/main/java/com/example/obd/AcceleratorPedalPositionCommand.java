package com.example.obd;
// Accelerator pedal position D
// PID 0x49, 1 byte, 0-100%, formula (100/255)*A
public class AcceleratorPedalPositionCommand extends ObdCommand {
    public AcceleratorPedalPositionCommand() { super("0149"); }

    @Override public String getName() { return "Accelerator Pedal"; }
    @Override public String getUnit() { return "%"; }

    @Override
    public double parseResult(String rawResponse) {
        String[] parts = rawResponse.split(" ");
        if (parts.length < 3) return 0;
        int A = Integer.parseInt(parts[2], 16);
        return (A * 100.0) / 255.0;
    }
}