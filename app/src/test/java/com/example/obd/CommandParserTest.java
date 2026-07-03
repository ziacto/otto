package com.example.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Table-driven JVM tests for every ObdCommand subclass's parseResult().
 *
 * Two invariants per command:
 *   1. A canonical positive response decodes to the value given by the
 *      standard OBD-II formula (SAE J1979) documented in each class.
 *   2. A truncated / malformed frame THROWS instead of silently returning a
 *      fabricated 0 — a cut-off frame must never surface as "0 km/h",
 *      "0% fuel" or "MIL off" in trend storage or on the dashboard.
 *      (ObdCommand.run() catches the exception and maps it to a per-command
 *      FAIL, so throwing here is the safe path.)
 *
 * A genuinely parsed zero stays a valid reading — see the valid-zero cases
 * (e.g. ActualEngineTorque A=0x7D → 0%).
 *
 * Runs on the plain JVM, no Robolectric — same style as ParserSmokeTest.
 */
public class CommandParserTest {

    private static final double EPS = 1e-9;

    /** One row of the table: command, a valid frame + expected value, a truncated frame. */
    private static final class Case {
        final ObdCommand cmd;
        final String valid;
        final double expected;
        final String truncated;

        Case(ObdCommand cmd, String valid, double expected, String truncated) {
            this.cmd = cmd;
            this.valid = valid;
            this.expected = expected;
            this.truncated = truncated;
        }
    }

    // Expected values computed from the formula in each command's header comment.
    private static final List<Case> CASES = Arrays.asList(
            // -- 1-byte PIDs (need >= 3 tokens; truncated = header only) --
            // (100/255)*A: A=0xFF=255 -> 100.0
            new Case(new AcceleratorPedalPositionCommand(), "41 49 FF", 100.0, "41 49"),
            // A-125: A=0x7D=125 -> 0.0 (valid ZERO must still parse)
            new Case(new ActualEngineTorqueCommand(), "41 62 7D", 0.0, "41 62"),
            // A-40: A=0x3C=60 -> 20.0
            new Case(new AmbientAirTempCommand(), "41 46 3C", 20.0, "41 46"),
            // A: A=0x65=101 -> 101.0
            new Case(new BarometricPressureCommand(), "41 33 65", 101.0, "41 33"),
            // A/100 (kPa->bar): A=0x64=100 -> 1.0
            new Case(new BoostPressureCommand(), "41 0B 64", 1.0, "41 0B"),
            // A-40: A=0x50=80 -> 40.0
            new Case(new ChargeAirTemperatureCommand(), "41 0F 50", 40.0, "41 0F"),
            // A-40: A=0x7B=123 -> 83.0
            new Case(new CoolantTempCommand(), "41 05 7B", 83.0, "41 05"),
            // A-125: A=0xC8=200 -> 75.0
            new Case(new DriverDemandTorqueCommand(), "41 61 C8", 75.0, "41 61"),
            // (100/255)*A: A=0xFF=255 -> 100.0
            new Case(new EngineLoadCommand(), "41 04 FF", 100.0, "41 04"),
            // (100/255)*A: A=0xFF=255 -> 100.0
            new Case(new FuelLevelCommand(), "41 2F FF", 100.0, "41 2F"),
            // 3A: A=0x64=100 -> 300.0
            new Case(new FuelPressureCommand(), "41 0A 64", 300.0, "41 0A"),
            // A-40: A=0x4B=75 -> 35.0
            new Case(new IntakeAirTempCommand(), "41 0F 4B", 35.0, "41 0F"),
            // (100/128)*A-100: A=0x90=144 -> 12.5
            new Case(new LongTermFuelTrimCommand(), "41 07 90", 12.5, "41 07"),
            // A-40: A=0x82=130 -> 90.0
            new Case(new OilTempCommand(), "41 5C 82", 90.0, "41 5C"),
            // (100/255)*A: A=0x33=51 -> 20.0
            new Case(new RelativeThrottlePositionCommand(), "41 45 33", 20.0, "41 45"),
            // (100/128)*A-100: A=0x83=131 -> 2.34375
            new Case(new ShortTermFuelTrimCommand(), "41 06 83", 2.34375, "41 06"),
            // A: A=0x3C -> 60 km/h
            new Case(new SpeedCommand(), "41 0D 3C", 60.0, "41 0D"),
            // (100/255)*A: A=0x66=102 -> 40.0
            new Case(new ThrottlePositionCommand(), "41 11 66", 40.0, "41 11"),
            // A/2-64: A=0x90=144 -> 8.0
            new Case(new TimingAdvanceCommand(), "41 0E 90", 8.0, "41 0E"),
            // MIL status packing: A=0x83 -> MIL on + 3 DTCs -> 1000 + 3
            new Case(new MilStatusCommand(), "41 01 83 07 FF 00", 1003.0, "41 01"),
            // MIL off, 0 DTCs is a VALID zero, not an error
            new Case(new MilStatusCommand(), "41 01 00 07 FF 00", 0.0, "41 01"),

            // -- 2-byte PIDs (need >= 4 tokens; truncated = A present, B missing) --
            // (2/65536)*(256A+B): A=0x80,B=0x00 -> 1.0 (stoichiometric)
            new Case(new CommandedLambdaCommand(), "41 44 80 00", 1.0, "41 44 80"),
            // (256A+B)/1000: A=0x37=55,B=0x5A=90 -> 14.17 V
            new Case(new ControlModuleVoltageCommand(), "41 42 37 5A", 14.17, "41 42 37"),
            // 256A+B: A=0x01,B=0x90=144 -> 400 km
            new Case(new DistanceSinceClearedCommand(), "41 31 01 90", 400.0, "41 31 01"),
            // 256A+B: A=0x00,B=0x3C -> 60 s
            new Case(new EngineRunTimeCommand(), "41 1F 00 3C", 60.0, "41 1F 00"),
            // (256A+B)/20: A=0x00,B=0x64=100 -> 5.0 L/h
            new Case(new FuelConsumptionCommand(), "41 5E 00 64", 5.0, "41 5E 00"),
            // (256A+B)/128-210: A=0x6A=106,B=0x00 -> 2.0 deg
            new Case(new FuelInjectionTimingCommand(), "41 5D 6A 00", 2.0, "41 5D 6A"),
            // (256A+B)/32768: A=0x80,B=0x00 -> 1.0
            new Case(new LambdaCommand(), "41 34 80 00", 1.0, "41 34 80"),
            // (256A+B)/100: A=0x0B=11,B=0xB8=184 -> 30.0 g/s
            new Case(new MassAirFlowCommand(), "41 10 0B B8", 30.0, "41 10 0B"),
            // A/200: A=0x96=150 -> 0.75 V (B byte present but unused)
            new Case(new O2SensorCommand(), "41 14 96 80", 0.75, "41 14 96"),
            // (256A+B)/4: A=0x1A,B=0xF8 -> 1726 rpm
            new Case(new RpmCommand(), "41 0C 1A F8", 1726.0, "41 0C 1A"),

            // -- ELM AT command (ATRV voltage text, no PID framing) --
            new Case(new BatteryVoltageCommand(), "12.4V", 12.4, "")
    );

    @Test
    public void validResponses_decodeToFormulaValues() {
        for (Case c : CASES) {
            assertEquals(
                    c.cmd.getClass().getSimpleName() + " must decode \"" + c.valid + "\"",
                    c.expected, c.cmd.parseResult(c.valid), EPS);
        }
    }

    @Test
    public void truncatedResponses_throwInsteadOfFabricatingZero() {
        for (Case c : CASES) {
            try {
                double v = c.cmd.parseResult(c.truncated);
                fail(c.cmd.getClass().getSimpleName() + " returned fabricated value " + v
                        + " for truncated frame \"" + c.truncated + "\" — must throw");
            } catch (RuntimeException expected) {
                // pass — ObdCommand.run() maps this to a per-command FAIL
            }
        }
    }

    /** ATRV noise with no digits at all must also throw, not read as 0 V. */
    @Test
    public void batteryVoltage_garbageThrows() {
        try {
            double v = new BatteryVoltageCommand().parseResult("?V ERR");
            fail("Garbage ATRV response parsed as " + v + " — must throw");
        } catch (RuntimeException expected) {
            // pass
        }
    }
}
