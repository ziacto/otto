package com.example.obd;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.navigation.NavigationView;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class MainActivity extends AppCompatActivity {
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private ImageButton btnMenu;

    private ObdManagerFast obdManager;
    private BluetoothHelper bluetoothHelper;

    private final AnalyticsController analyticsController = new AnalyticsController();
    private final SprintController sprintController = new SprintController();
    private boolean analyticsActive = false;
    private boolean sprintActive = false;

    public enum PollGroup {
        // fast: speed-sensitive views
        // Intervals account for cheap ELM327 clone latency (~300-400ms per command)
        GROUP_SPEED          (500,    List.of(new SpeedCommand())),
        GROUP_SPEED_RPM      (800,    Arrays.asList(new SpeedCommand(), new RpmCommand())),
        GROUP_SPRINT         (200,    List.of(new SpeedCommand())),
        GROUP_ENGINE_CONTROL (2000,   Arrays.asList(
                new RpmCommand(), new MassAirFlowCommand(),
                new BoostPressureCommand(), new ThrottlePositionCommand(),
                new ActualEngineTorqueCommand(), new DriverDemandTorqueCommand(),
                new AcceleratorPedalPositionCommand())),
        GROUP_TURBO_MONITORING(3000,  Arrays.asList(
                new BoostPressureCommand(), new ChargeAirTemperatureCommand(),
                new BarometricPressureCommand(),
                new ActualEngineTorqueCommand(), new DriverDemandTorqueCommand())),
        GROUP_PERFORMANCE_DYNAMIC(6000, Arrays.asList(
                new FuelConsumptionCommand(), new TimingAdvanceCommand(),
                new O2SensorCommand(), new FuelInjectionTimingCommand(),
                new RelativeThrottlePositionCommand())),
        GROUP_FUEL_CONTROL   (10000,  Arrays.asList(
                new FuelPressureCommand(), new FuelLevelCommand(),
                new EngineLoadCommand(), new LambdaCommand(),
                new ShortTermFuelTrimCommand(), new LongTermFuelTrimCommand(),
                new CommandedLambdaCommand())),
        GROUP_TEMP_PRESSURE  (12000,  Arrays.asList(
                new CoolantTempCommand(), new BoostPressureCommand(), new IntakeAirTempCommand())),
        GROUP_THERMAL_CONTROL(15000,  Arrays.asList(
                new CoolantTempCommand(), new IntakeAirTempCommand(),
                new ChargeAirTemperatureCommand(), new AmbientAirTempCommand())),
        GROUP_NORMAL         (15000,  Arrays.asList(
                new CoolantTempCommand(), new OilTempCommand(), new O2SensorCommand())),
        GROUP_ELECTRICAL     (15000,  Arrays.asList(
                new BatteryVoltageCommand(), new ControlModuleVoltageCommand(),
                new ThrottlePositionCommand(), new EngineRunTimeCommand())),
        // analytics: 10 cmds × ~400ms adapter latency ≈ 4s per cycle, plus this sleep
        GROUP_ANALYTICS      (1000,   Arrays.asList(
                new RpmCommand(), new SpeedCommand(), new BoostPressureCommand(),
                new ThrottlePositionCommand(), new EngineLoadCommand(),
                new MassAirFlowCommand(), new LambdaCommand(),
                new CoolantTempCommand(), new IntakeAirTempCommand(),
                new TimingAdvanceCommand()));

        private final int intervalMs;
        private final List<ObdCommand> sensors;
        PollGroup(int intervalMs, List<ObdCommand> sensors) {
            this.intervalMs = intervalMs;
            this.sensors = sensors;
        }
        public int getIntervalMs() { return intervalMs; }
        public List<ObdCommand> getSensors() { return sensors; }
    }

    private PollGroup currentGroup = PollGroup.GROUP_SPEED_RPM;

    private void updateConnectMenuTitle(MenuItem item) {
        if (obdManager != null && obdManager.isConnected()) {
            item.setTitle("Disconnect");
        } else {
            item.setTitle("Connect");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.navigation_view);
        btnMenu = findViewById(R.id.btnMenu);

        obdManager = new ObdManagerFast(this, new SpeedPollerListener() {
            @Override
            public void onValue(String name, double value, String unit) {
                DataLogger.getInstance().record(name, value);
                runOnUiThread(() -> updateUI(name, value, unit));
            }
            @Override
            public void onError(String msg) {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
            }
        }, () -> currentGroup.getIntervalMs(), () -> currentGroup.getSensors());

        bluetoothHelper = new BluetoothHelper(this, obdManager);
        bluetoothHelper.requestNeededPermissions();

        btnMenu.setOnClickListener(v -> drawerLayout.openDrawer(navigationView));

        MenuItem connectItem = navigationView.getMenu().findItem(R.id.nav_disconnect);
        updateConnectMenuTitle(connectItem);

        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            drawerLayout.closeDrawer(navigationView);

            // Detach any active special controllers
            if (analyticsActive) { analyticsController.detach(); analyticsActive = false; }
            if (sprintActive) { sprintController.detach(); sprintActive = false; }

            View newView = null;
            String title = "";

            if (id == R.id.nav_welcome) {
                newView = getLayoutInflater().inflate(R.layout.layout_welcome, null);
                title = "Hello";

            } else if (id == R.id.nav_speed) {
                newView = getLayoutInflater().inflate(R.layout.layout_speed, null);
                title = "Speed only";
                currentGroup = PollGroup.GROUP_SPEED;
                restartIfConnected();

            } else if (id == R.id.nav_speed_rpm) {
                newView = getLayoutInflater().inflate(R.layout.layout_speed_rpm, null);
                title = "Speed & RPM";
                currentGroup = PollGroup.GROUP_SPEED_RPM;
                restartIfConnected();

            } else if (id == R.id.nav_temp_pressure) {
                newView = getLayoutInflater().inflate(R.layout.layout_temp_pressure, null);
                title = "Temperatures / Pressure";
                currentGroup = PollGroup.GROUP_TEMP_PRESSURE;
                restartIfConnected();

            } else if (id == R.id.nav_electrical) {
                newView = getLayoutInflater().inflate(R.layout.layout_electrical, null);
                title = "Electrical";
                currentGroup = PollGroup.GROUP_ELECTRICAL;
                restartIfConnected();

            } else if (id == R.id.nav_thermalcontrol) {
                newView = getLayoutInflater().inflate(R.layout.layout_thermalcontrol, null);
                title = "Thermal Control";
                currentGroup = PollGroup.GROUP_THERMAL_CONTROL;
                restartIfConnected();

            } else if (id == R.id.nav_enginecontrol) {
                newView = getLayoutInflater().inflate(R.layout.layout_enginecontrol, null);
                title = "Engine Control";
                currentGroup = PollGroup.GROUP_ENGINE_CONTROL;
                restartIfConnected();

            } else if (id == R.id.nav_fuelcontrol) {
                newView = getLayoutInflater().inflate(R.layout.layout_fuelcontrol, null);
                title = "Fuel Control";
                currentGroup = PollGroup.GROUP_FUEL_CONTROL;
                restartIfConnected();

            } else if (id == R.id.nav_performancedynamic) {
                newView = getLayoutInflater().inflate(R.layout.layout_performancedynamic, null);
                title = "Performance Dynamic";
                currentGroup = PollGroup.GROUP_PERFORMANCE_DYNAMIC;
                restartIfConnected();

            } else if (id == R.id.nav_turbomonitoring) {
                newView = getLayoutInflater().inflate(R.layout.layout_turbomonitoring, null);
                title = "Turbo Monitoring";
                currentGroup = PollGroup.GROUP_TURBO_MONITORING;
                restartIfConnected();

            } else if (id == R.id.nav_normal) {
                newView = getLayoutInflater().inflate(R.layout.layout_normal,
                        (ViewGroup) findViewById(R.id.content_container), false);
                title = "Coolant & Oil";
                currentGroup = PollGroup.GROUP_NORMAL;
                restartIfConnected();

            } else if (id == R.id.nav_analytics) {
                newView = getLayoutInflater().inflate(R.layout.layout_analytics, null);
                title = "Daten-Analyse";
                currentGroup = PollGroup.GROUP_ANALYTICS;
                restartIfConnected();
                final View analyticsView = newView;
                newView.post(() -> {
                    analyticsController.attach(analyticsView);
                    analyticsActive = true;
                });

            } else if (id == R.id.nav_sprint) {
                newView = getLayoutInflater().inflate(R.layout.layout_sprint, null);
                title = "0–100 Timer";
                currentGroup = PollGroup.GROUP_SPRINT;
                restartIfConnected();
                final View sprintView = newView;
                newView.post(() -> {
                    sprintController.attach(sprintView);
                    sprintActive = true;
                });

            } else if (id == R.id.nav_disconnect) {
                if (obdManager.isConnected()) {
                    obdManager.disconnect();
                    Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
                } else {
                    bluetoothHelper.showPairedDevicesAndConnect();
                }
                updateConnectMenuTitle(item);
                return true;
            }

            if (newView != null) {
                FrameLayout container = findViewById(R.id.content_container);
                container.removeAllViews();
                container.addView(newView);
                TextView tvTitle = findViewById(R.id.tvTitle);
                tvTitle.setText(title);
            }

            return true;
        });
    }

    private void restartIfConnected() {
        if (obdManager != null && obdManager.isConnected()) obdManager.restartPolling();
    }

    private void updateUI(String name, double value, String unit) {
        if (sprintActive && "Vehicle Speed".equals(name)) {
            sprintController.onSpeedValue(value);
        }

        FrameLayout container = findViewById(R.id.content_container);
        if (container.getChildCount() == 0) return;
        View activeLayout = container.getChildAt(0);
        String formatted = String.format("%.1f %s", value, unit);

        int tvId = 0;
        switch (name) {
            case "Vehicle Speed":       tvId = R.id.tvSpeed; break;
            case "RPM":                 tvId = R.id.tvRpm; break;
            case "Coolant Temp":        tvId = R.id.tvCoolant; break;
            case "Intake Air Temp":     tvId = R.id.tvIntake; break;
            case "Boost Pressure":      tvId = R.id.tvBoost; break;
            case "Throttle Position":   tvId = R.id.tvThrottle; break;
            case "Battery Voltage":     tvId = R.id.tvBattery; break;
            case "Ambient Air Temperature": tvId = R.id.tvAmbientAir; break;
            case "Charge Air Temperature":  tvId = R.id.tvChargeAir; break;
            case "Barometric Pressure":     tvId = R.id.tvBarometricPressure; break;
            case "Engine Load":         tvId = R.id.tvEngineLoad; break;
            case "Fuel Consumption":    tvId = R.id.tvFuelConsumption; break;
            case "Fuel Level":          tvId = R.id.tvFuelLevel; break;
            case "Fuel Pressure":       tvId = R.id.tvFuelPressure; break;
            case "Lambda":              tvId = R.id.tvLambda; break;
            case "Mass Air Flow":       tvId = R.id.tvMassAirFlow; break;
            case "O2 Sensor":           tvId = R.id.tvO2; break;
            case "Timing Advance":      tvId = R.id.tvTimingAdvance; break;
            case "Oil Temp":              tvId = R.id.tvOil; break;
            case "Short Term Fuel Trim":  tvId = R.id.tvStft; break;
            case "Long Term Fuel Trim":   tvId = R.id.tvLtft; break;
            case "Engine Run Time":       tvId = R.id.tvEngineRunTime; break;
            case "ECU Voltage":           tvId = R.id.tvEcuVoltage; break;
            case "Engine Torque":          tvId = R.id.tvEngineTorque; break;
            case "Demand Torque":          tvId = R.id.tvDemandTorque; break;
            case "Accelerator Pedal":      tvId = R.id.tvAccelPedal; break;
            case "Relative Throttle":      tvId = R.id.tvRelThrottle; break;
            case "Commanded Lambda":       tvId = R.id.tvCommandedLambda; break;
            case "Injection Timing":       tvId = R.id.tvInjectionTiming; break;
        }

        if (tvId != 0) {
            TextView tv = activeLayout.findViewById(tvId);
            if (tv != null) tv.setText(name + ": " + formatted);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analyticsController.detach();
        sprintController.detach();
        if (obdManager != null && obdManager.isConnected()) obdManager.disconnect();
    }
}
