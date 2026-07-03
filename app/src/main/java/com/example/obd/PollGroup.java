package com.example.obd;

import java.util.Arrays;
import java.util.List;

/**
 * Poll-group definitions: which PIDs each screen polls and at what cadence.
 *
 * Lives in its own file (not inside MainActivity) so the transport layer
 * (ObdManagerFast) can reset per-command support state without depending on
 * the Activity class — that dependency made the manager untestable off-device.
 * ObdCommand instances are shared across reconnects; interval trade-offs are
 * documented per group.
 */
public enum PollGroup {
    // 730li dashboard: NA inline-6, no boost. Covers the live readings most owners care about.
    GROUP_DASHBOARD      (500,    Arrays.asList(
            new RpmCommand(), new SpeedCommand(),
            new CoolantTempCommand(), new OilTempCommand(),
            new ThrottlePositionCommand(), new EngineLoadCommand(),
            new BatteryVoltageCommand(), new FuelLevelCommand(),
            new MassAirFlowCommand(), new IntakeAirTempCommand(),
            new MilStatusCommand())),

    // HUD dashboard: extended sensor set (gear estimation, lambda, fuel trims, timing)
    GROUP_HUD_DASHBOARD  (700,    Arrays.asList(
            new RpmCommand(), new SpeedCommand(),
            new CoolantTempCommand(), new OilTempCommand(),
            new ThrottlePositionCommand(), new EngineLoadCommand(),
            new BatteryVoltageCommand(), new FuelLevelCommand(),
            new MassAirFlowCommand(), new IntakeAirTempCommand(),
            new MilStatusCommand(), new TimingAdvanceCommand(),
            new ActualEngineTorqueCommand(), new LambdaCommand(),
            new ShortTermFuelTrimCommand(), new LongTermFuelTrimCommand(),
            new AmbientAirTempCommand())),

    // Live Data browser groups — narrow command sets per car system so the
    // poll loop stays under ~2s per cycle on slow ELM clones.
    GROUP_LIVE_POWERTRAIN (700, Arrays.asList(
            new RpmCommand(), new SpeedCommand(), new ThrottlePositionCommand(),
            new EngineLoadCommand(), new RelativeThrottlePositionCommand(),
            new AcceleratorPedalPositionCommand(), new ActualEngineTorqueCommand(),
            new DriverDemandTorqueCommand(), new TimingAdvanceCommand())),
    GROUP_LIVE_THERMAL    (1500, Arrays.asList(
            new CoolantTempCommand(), new OilTempCommand(),
            new IntakeAirTempCommand(), new ChargeAirTemperatureCommand(),
            new AmbientAirTempCommand())),
    GROUP_LIVE_FUEL       (1000, Arrays.asList(
            new MassAirFlowCommand(), new FuelLevelCommand(), new FuelPressureCommand(),
            new FuelConsumptionCommand(), new LambdaCommand(), new CommandedLambdaCommand(),
            new ShortTermFuelTrimCommand(), new LongTermFuelTrimCommand(),
            new FuelInjectionTimingCommand())),
    GROUP_LIVE_ELECTRICAL (2000, Arrays.asList(
            new BatteryVoltageCommand(), new ControlModuleVoltageCommand(),
            new EngineRunTimeCommand())),
    GROUP_LIVE_PERFORMANCE(1000, Arrays.asList(
            new RpmCommand(), new SpeedCommand(), new BoostPressureCommand(),
            new BarometricPressureCommand(), new EngineLoadCommand(),
            new ActualEngineTorqueCommand(), new TimingAdvanceCommand(),
            new EngineRunTimeCommand())),
    GROUP_LIVE_EMISSIONS  (2000, Arrays.asList(
            new O2SensorCommand(), new LambdaCommand(),
            new ShortTermFuelTrimCommand(), new LongTermFuelTrimCommand())),

    // analytics: 10 cmds × ~400ms adapter latency ≈ 4s per cycle, plus this sleep
    GROUP_ANALYTICS      (1000,   Arrays.asList(
            new RpmCommand(), new SpeedCommand(), new BoostPressureCommand(),
            new ThrottlePositionCommand(), new EngineLoadCommand(),
            new MassAirFlowCommand(), new LambdaCommand(),
            new CoolantTempCommand(), new IntakeAirTempCommand(),
            new TimingAdvanceCommand())),
    // sprint: tight loop on speed only for the 0-100 timer
    GROUP_SPRINT         (200,    List.of(new SpeedCommand()));

    private final int intervalMs;
    private final List<ObdCommand> sensors;
    PollGroup(int intervalMs, List<ObdCommand> sensors) {
        this.intervalMs = intervalMs;
        this.sensors = sensors;
    }
    public int getIntervalMs() { return intervalMs; }
    public List<ObdCommand> getSensors() { return sensors; }
}
