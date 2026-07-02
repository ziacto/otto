package com.example.obd;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private ImageButton btnMenu;

    private ObdManagerFast obdManager;
    private BluetoothHelper bluetoothHelper;

    private final AnalyticsController analyticsController = new AnalyticsController();
    private final SprintController sprintController = new SprintController();
    private FaultCodesController faultCodesController;
    private final DebugLogController debugLogController = new DebugLogController();
    private final DashboardController dashboardController = new DashboardController();
    private final SensorsController sensorsController = new SensorsController();
    private final GarageController garageController = new GarageController();
    private final LiveDataBrowserController liveDataController = new LiveDataBrowserController();
    private final KnowledgeBaseController knowledgeController = new KnowledgeBaseController();
    private final ServiceFunctionsController serviceController = new ServiceFunctionsController();
    private final GaugeDashboardController gaugeDashController = new GaugeDashboardController();
    private final HudDashboardController hudDashController = new HudDashboardController();
    private final OdometerController odometerController = new OdometerController();
    private final AiEstimatorController aiEstimatorController = new AiEstimatorController();
    private final CarAdvisorController carAdvisorController = new CarAdvisorController();
    private final ScanReportsController scanReportsController = new ScanReportsController();
    private ConnectFlowController connectFlowController;
    private boolean connectFlowActive = false;
    private boolean analyticsActive = false;
    private boolean sprintActive = false;
    private boolean faultCodesActive = false;
    private boolean debugLogActive = false;
    private boolean dashboardActive = false;
    private boolean sensorsActive = false;
    private boolean garageActive = false;
    private boolean liveDataActive = false;
    private boolean knowledgeActive = false;
    private boolean serviceActive = false;
    private boolean gaugeDashActive = false;
    private boolean hudDashActive = false;
    private boolean odometerActive = false;
    private boolean aiEstimatorActive = false;
    private boolean carAdvisorActive = false;
    private boolean scanReportsActive = false;

    /**
     * Single-shot image picker used by AiEstimatorController. ActivityResult
     * contracts must be registered before onStart, so we own the launcher here
     * and route the selected Uri back to whoever asked for it most recently.
     */
    private Consumer<Uri> pendingPhotoCallback;
    private ActivityResultLauncher<String> photoPicker;

    // Spoken alerts (TTS, hysteresis, throttle) live in AlertManager — see #5 refactor.
    private final AlertManager alertManager = new AlertManager();
    private TextView connectionPill;
    private TextView dtcChip;
    private TextView thermalChip;
    // Latest thermal readings used to drive the status chip — kept on the UI thread.
    private double lastCoolant = Double.NaN, lastOil = Double.NaN;
    private final Handler connStateHandler = new Handler(Looper.getMainLooper());
    private final Runnable connStateTick = new Runnable() {
        @Override public void run() {
            updateConnectionBanner();
            connStateHandler.postDelayed(this, 1500);
        }
    };

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

    private PollGroup currentGroup = PollGroup.GROUP_DASHBOARD;

    private void updateConnectMenuTitle(MenuItem item) {
        if (obdManager != null && obdManager.isConnected()) {
            item.setTitle("Disconnect");
        } else {
            item.setTitle("Connect");
        }
    }

    /**
     * Called by AiEstimatorController to pick an image. Registers the launcher
     * once in onCreate; here we just stash the callback and fire the picker.
     */
    public void pickAiPhoto(Consumer<Uri> callback) {
        pendingPhotoCallback = callback;
        if (photoPicker != null) photoPicker.launch("image/*");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Install splash BEFORE super.onCreate so the OS displays the splash window
        // with our icon + brand background while the heavy MainActivity inflates.
        // postSplashScreenTheme in styles.xml swaps us back to Theme.OBD on first frame.
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        photoPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    Consumer<Uri> cb = pendingPhotoCallback;
                    pendingPhotoCallback = null;
                    if (cb != null) cb.accept(uri);
                });
        setContentView(R.layout.activity_main);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.navigation_view);
        btnMenu = findViewById(R.id.btnMenu);
        connectionPill = findViewById(R.id.connectionPill);
        dtcChip = findViewById(R.id.dtcChip);
        thermalChip = findViewById(R.id.thermalChip);

        alertManager.init(this);

        obdManager = new ObdManagerFast(this, new SpeedPollerListener() {
            @Override
            public void onValue(String name, double value, String unit) {
                DataLogger.getInstance().record(name, value);
                TrendEngine.record(MainActivity.this, name, value);
                LastValuesCache.get(MainActivity.this).record(name, value);
                alertManager.check(name, value);
                if ("Coolant Temp".equals(name)) lastCoolant = value;
                else if ("Oil Temp".equals(name)) lastOil = value;
                // Accumulate app-tracked distance on every speed sample, regardless
                // of which screen is open — so the odometer keeps adding up while
                // the user is in any dashboard view.
                if ("Vehicle Speed".equals(name)) {
                    OdometerTracker.get(MainActivity.this).recordSpeed(value);
                }
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

        faultCodesController = new FaultCodesController(obdManager);

        bluetoothHelper.tryAutoConnect();
        ObdService.bind(bluetoothHelper, obdManager);
        ObdService.startIfNeeded(this);

        // Debug: allow triggering the self-test straight from `adb shell am start`
        // via `--es action selftest`. Keeps the QA loop scriptable without
        // needing to poke the drawer manually.
        String debugAction = getIntent() == null ? null : getIntent().getStringExtra("action");
        if ("selftest".equals(debugAction)) {
            new Thread(() -> {
                SelfTestRunner.Report r = SelfTestRunner.run(this);
                ObdLogger.get().log(ObdLogger.Level.INFO,
                        "SELFTEST RESULT " + (r.allPassed ? "PASS" : "FAIL"));
                ObdLogger.get().log(ObdLogger.Level.INFO, "SELFTEST REPORT:\n" + r.text);
            }, "SelfTestFromIntent").start();
        } else if ("simulator".equals(debugAction)) {
            new Thread(() -> {
                try { obdManager.connectSimulator(); }
                catch (Exception e) {
                    ObdLogger.get().log(ObdLogger.Level.ERROR, "Sim boot failed: " + e);
                }
            }, "SimBootFromIntent").start();
        } else if ("fuel_probe".equals(debugAction)) {
            // Walk BMW UDS candidate DIDs for fuel level. Writes each raw
            // response + decoded value to obd-diag.log so we can see which
            // DID (if any) the user's DME actually answers.
            new Thread(() -> {
                FuelLevelProbe.Result r = FuelLevelProbe.run(this, obdManager);
                ObdLogger.get().log(ObdLogger.Level.INFO,
                        "FUEL_PROBE " + (r.bestMatch != null ? "PASS" : "FAIL")
                                + "\n" + r.report);
            }, "FuelProbe").start();
        } else if ("ai_chat_test".equals(debugAction)) {
            // Dry-run: exercise the AI chat + Google Search grounding wire
            // end-to-end without needing to pick a photo, tap through the
            // estimator UI, or type a question. Bypasses vision entirely by
            // passing a canned estimate JSON. The response goes to obd-diag.log.
            String question = getIntent().getStringExtra("q");
            if (question == null || question.isEmpty()) {
                question = "How much does an OEM BMW E65 water pump cost in Deira, Dubai, and which suppliers stock it?";
            }
            final String finalQuestion = question;
            new Thread(() -> {
                String apiKey = AiSettings.getEffectiveKey(this);
                if (apiKey == null || apiKey.isEmpty()) {
                    ObdLogger.get().log(ObdLogger.Level.ERROR, "AI_CHAT_TEST: no API key configured");
                    return;
                }
                String fakeEstimate = "{"
                        + "\"identified_part\":\"Water pump (electric, BMW E65 730li N52)\","
                        + "\"confidence\":\"high\","
                        + "\"severity\":\"Drive carefully\","
                        + "\"summary\":\"Coolant leak from the water pump housing. On the N52 engine the pump is electric, mounted on the front timing cover. Common failure around 120-150k km due to internal impeller cracking.\","
                        + "\"parts\":[{\"name\":\"Electric water pump\",\"oem_number\":\"11517586925\",\"price_aed_low\":900,\"price_aed_high\":1600}]"
                        + "}";
                ObdLogger.get().log(ObdLogger.Level.INFO,
                        "AI_CHAT_TEST start — question: " + finalQuestion);
                try {
                    GeminiVisionProvider provider = new GeminiVisionProvider(apiKey);
                    AiVisionProvider.ChatReply reply = provider.chatFollowup(
                            fakeEstimate, null,
                            new java.util.ArrayList<AiVisionProvider.ChatTurn>(),
                            finalQuestion);
                    ObdLogger.get().log(ObdLogger.Level.INFO,
                            "AI_CHAT_TEST reply (" + reply.text.length() + " chars, "
                                    + reply.sourceUrls.size() + " sources):");
                    ObdLogger.get().log(ObdLogger.Level.INFO, reply.text);
                    for (String u : reply.sourceUrls) {
                        ObdLogger.get().log(ObdLogger.Level.INFO, "  source: " + u);
                    }
                    ObdLogger.get().log(ObdLogger.Level.INFO,
                            "AI_CHAT_TEST DONE " + (reply.text.length() > 0 ? "PASS" : "FAIL"));
                } catch (Exception e) {
                    ObdLogger.get().log(ObdLogger.Level.ERROR,
                            "AI_CHAT_TEST failed: " + e.getMessage());
                }
            }, "AiChatTest").start();
        }

        // On first launch (no previous content), show the Welcome screen by default
        // so the user has a clear Connect button instead of a blank container.
        FrameLayout startupContainer = findViewById(R.id.content_container);
        if (startupContainer != null && startupContainer.getChildCount() == 0) {
            startupContainer.post(() -> {
                MenuItem welcome = navigationView.getMenu().findItem(R.id.nav_welcome);
                if (welcome != null) {
                    navigationView.getMenu().performIdentifierAction(welcome.getItemId(), 0);
                }
            });
        }

        // Bottom nav routes to the most common destinations; drawer keeps the long-tail screens.
        BottomNavigationView bottom = findViewById(R.id.bottomNav);
        bottom.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            MenuItem target = null;
            if (id == R.id.nav_tab_drive)    target = navigationView.getMenu().findItem(R.id.nav_dashboard);
            else if (id == R.id.nav_tab_diagnose) target = navigationView.getMenu().findItem(R.id.nav_faultcodes);
            else if (id == R.id.nav_tab_garage)   target = navigationView.getMenu().findItem(R.id.nav_garage);
            if (target != null) navigationView.getMenu().performIdentifierAction(target.getItemId(), 0);
            return true;
        });

        btnMenu.setOnClickListener(v -> drawerLayout.openDrawer(navigationView));

        // Tapping the connection pill opens the guided connect flow when we're
        // offline. On a healthy connection it's a no-op — the pill is just a
        // status indicator.
        if (connectionPill != null) {
            connectionPill.setOnClickListener(v -> {
                if (obdManager == null || !obdManager.isConnected()) {
                    openConnectFlow();
                }
            });
        }

        MenuItem connectItem = navigationView.getMenu().findItem(R.id.nav_disconnect);
        updateConnectMenuTitle(connectItem);

        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            drawerLayout.closeDrawer(navigationView);

            // Detach any active special controllers
            if (analyticsActive) { analyticsController.detach(); analyticsActive = false; }
            if (sprintActive) { sprintController.detach(); sprintActive = false; }
            if (faultCodesActive) { faultCodesController.detach(); faultCodesActive = false; }
            if (debugLogActive) { debugLogController.detach(); debugLogActive = false; }
            if (dashboardActive) { dashboardController.detach(); dashboardActive = false; }
            if (sensorsActive) { sensorsController.detach(); sensorsActive = false; }
            if (garageActive) { garageController.detach(); garageActive = false; }
            if (liveDataActive) { liveDataController.detach(); liveDataActive = false; }
            if (knowledgeActive) { knowledgeController.detach(); knowledgeActive = false; }
            if (serviceActive) { serviceController.detach(); serviceActive = false; }
            if (gaugeDashActive) { gaugeDashController.detach(); gaugeDashActive = false; }
            if (hudDashActive) { hudDashController.detach(); hudDashActive = false; }
            if (odometerActive) { odometerController.detach(); odometerActive = false; }
            if (aiEstimatorActive) { aiEstimatorController.detach(); aiEstimatorActive = false; }
            if (carAdvisorActive) { carAdvisorController.detach(); carAdvisorActive = false; }
            if (scanReportsActive) { scanReportsController.detach(); scanReportsActive = false; }

            // Default: let the OS decide; keep-screen-on cleared per nav.
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            View newView = null;
            String title = "";

            if (id == R.id.nav_welcome) {
                newView = getLayoutInflater().inflate(R.layout.layout_welcome, null);
                title = "Hello";
                // Wire the Connect button + status text
                final View welcomeView = newView;
                newView.post(() -> wireWelcomeScreen(welcomeView));

            } else if (id == R.id.nav_dashboard) {
                newView = getLayoutInflater().inflate(R.layout.layout_dashboard, null);
                title = "Dashboard (730li)";
                currentGroup = PollGroup.GROUP_DASHBOARD;
                restartIfConnected();
                // Keep-screen-on while Dashboard is up, but stay in portrait — user prefers
                // the phone orientation to follow their hand, not the app.
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                final View dashView = newView;
                newView.post(() -> {
                    dashboardController.attach(dashView, MainActivity.this, obdManager);
                    dashboardActive = true;
                });

            } else if (id == R.id.nav_sensors) {
                newView = getLayoutInflater().inflate(R.layout.layout_sensors, null);
                title = "Car & Sensors";
                final View sensView = newView;
                newView.post(() -> {
                    sensorsController.attach(sensView);
                    sensorsActive = true;
                });

            } else if (id == R.id.nav_garage) {
                newView = getLayoutInflater().inflate(R.layout.layout_garage, null);
                title = "Garage";
                final View garView = newView;
                newView.post(() -> {
                    garageController.attach(garView);
                    garageActive = true;
                });

            } else if (id == R.id.nav_faultcodes) {
                newView = getLayoutInflater().inflate(R.layout.layout_faultcodes, null);
                title = "Fault Codes / Reset";
                final View dtcView = newView;
                newView.post(() -> {
                    faultCodesController.attach(dtcView);
                    faultCodesActive = true;
                });

            } else if (id == R.id.nav_debuglog) {
                newView = getLayoutInflater().inflate(R.layout.layout_debuglog, null);
                title = "Debug Log";
                final View logView = newView;
                newView.post(() -> {
                    debugLogController.attach(logView);
                    debugLogActive = true;
                });

            } else if (id == R.id.nav_live_all || id == R.id.nav_live_powertrain
                    || id == R.id.nav_live_thermal || id == R.id.nav_live_fuel
                    || id == R.id.nav_live_electrical || id == R.id.nav_live_performance
                    || id == R.id.nav_live_emissions) {
                newView = getLayoutInflater().inflate(R.layout.layout_livedata, null);
                SensorInfo.Category cat = null;
                PollGroup group = PollGroup.GROUP_DASHBOARD;
                if (id == R.id.nav_live_powertrain) {
                    cat = SensorInfo.Category.POWERTRAIN; group = PollGroup.GROUP_LIVE_POWERTRAIN;
                    title = "Live · Powertrain";
                } else if (id == R.id.nav_live_thermal) {
                    cat = SensorInfo.Category.THERMAL; group = PollGroup.GROUP_LIVE_THERMAL;
                    title = "Live · Thermal";
                } else if (id == R.id.nav_live_fuel) {
                    cat = SensorInfo.Category.FUEL; group = PollGroup.GROUP_LIVE_FUEL;
                    title = "Live · Fuel & Air";
                } else if (id == R.id.nav_live_electrical) {
                    cat = SensorInfo.Category.ELECTRICAL; group = PollGroup.GROUP_LIVE_ELECTRICAL;
                    title = "Live · Electrical";
                } else if (id == R.id.nav_live_performance) {
                    cat = SensorInfo.Category.PERFORMANCE; group = PollGroup.GROUP_LIVE_PERFORMANCE;
                    title = "Live · Performance";
                } else if (id == R.id.nav_live_emissions) {
                    cat = SensorInfo.Category.EMISSIONS; group = PollGroup.GROUP_LIVE_EMISSIONS;
                    title = "Live · Emissions";
                } else {
                    title = "Live Data — All sensors";
                }
                currentGroup = group;
                restartIfConnected();
                final View liveView = newView;
                final SensorInfo.Category presetCat = cat;
                newView.post(() -> {
                    liveDataController.attach(liveView, presetCat);
                    liveDataActive = true;
                });

            } else if (id == R.id.nav_analytics) {
                newView = getLayoutInflater().inflate(R.layout.layout_analytics, null);
                title = "Data Analysis";
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

            } else if (id == R.id.nav_knowledge) {
                newView = getLayoutInflater().inflate(R.layout.layout_knowledge, null);
                title = "Knowledge Base";
                final View kbView = newView;
                newView.post(() -> {
                    knowledgeController.attach(kbView);
                    knowledgeActive = true;
                });

            } else if (id == R.id.nav_service) {
                newView = getLayoutInflater().inflate(R.layout.layout_service_functions, null);
                title = "Service Functions";
                final View svcView = newView;
                newView.post(() -> {
                    serviceController.attach(svcView, obdManager);
                    serviceActive = true;
                });

            } else if (id == R.id.nav_dashboard_gauges) {
                newView = getLayoutInflater().inflate(R.layout.layout_gauge_dashboard, null);
                title = "Gauge Dashboard";
                currentGroup = PollGroup.GROUP_DASHBOARD;
                restartIfConnected();
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                final View gdView = newView;
                newView.post(() -> {
                    gaugeDashController.attach(gdView, obdManager);
                    gaugeDashActive = true;
                });

            } else if (id == R.id.nav_dashboard_hud) {
                newView = getLayoutInflater().inflate(R.layout.layout_hud_dashboard, null);
                title = "HUD Dashboard";
                currentGroup = PollGroup.GROUP_HUD_DASHBOARD;
                restartIfConnected();
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                final View hudView = newView;
                newView.post(() -> {
                    hudDashController.attach(hudView, obdManager);
                    hudDashActive = true;
                });

            } else if (id == R.id.nav_odometer) {
                newView = getLayoutInflater().inflate(R.layout.layout_odometer, null);
                title = "Odometer";
                final View odoView = newView;
                newView.post(() -> {
                    odometerController.attach(odoView, obdManager);
                    odometerActive = true;
                });

            } else if (id == R.id.nav_ai_estimator) {
                newView = getLayoutInflater().inflate(R.layout.layout_ai_estimator, null);
                title = "AI Repair Estimator";
                final View aiView = newView;
                newView.post(() -> {
                    aiEstimatorController.attach(aiView, MainActivity.this, obdManager);
                    aiEstimatorActive = true;
                });

            } else if (id == R.id.nav_car_advisor) {
                newView = getLayoutInflater().inflate(R.layout.layout_car_advisor, null);
                title = "Help Me Buy a Car";
                final View advView = newView;
                newView.post(() -> {
                    carAdvisorController.attach(advView, MainActivity.this);
                    carAdvisorActive = true;
                });

            } else if (id == R.id.nav_scan_reports) {
                newView = getLayoutInflater().inflate(R.layout.layout_scan_reports, null);
                title = "Scan Reports";
                final View reportsView = newView;
                newView.post(() -> {
                    scanReportsController.attach(reportsView, MainActivity.this);
                    scanReportsActive = true;
                });

            } else if (id == R.id.nav_disconnect) {
                if (obdManager.isConnected()) {
                    obdManager.disconnect();
                    Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
                } else {
                    // Offline: guided flow instead of the raw picker dialog.
                    openConnectFlow();
                }
                updateConnectMenuTitle(item);
                return true;
            } else if (id == R.id.nav_clear_data) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Clear app data?")
                        .setMessage("This wipes:\n"
                                + "  • diag + crash logs\n"
                                + "  • cached last-known sensor values\n"
                                + "  • 30-day trend samples\n\n"
                                + "Vehicle profile (VIN, model) and Bluetooth pairing stay.")
                        .setPositiveButton("Clear", (d, w) -> {
                            AppDataCleaner.Result res = AppDataCleaner.clearAll(this);
                            new androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle("Done")
                                    .setMessage(res.describe())
                                    .setPositiveButton("OK", null)
                                    .show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            } else if (id == R.id.nav_simulator) {
                Toast.makeText(this, "Starting simulator…", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    try {
                        obdManager.connectSimulator();
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Simulator running — open Dashboard",
                                    Toast.LENGTH_LONG).show();
                            MenuItem dash = navigationView.getMenu().findItem(R.id.nav_dashboard);
                            if (dash != null) {
                                navigationView.getMenu()
                                        .performIdentifierAction(dash.getItemId(), 0);
                            }
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this,
                                "Simulator failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
                    }
                }, "SimulatorBoot").start();
                return true;
            } else if (id == R.id.nav_fuel_probe) {
                Toast.makeText(this, "Probing fuel-level DIDs — this takes ~15 s…",
                        Toast.LENGTH_LONG).show();
                new Thread(() -> {
                    FuelLevelProbe.Result r = FuelLevelProbe.run(this, obdManager);
                    runOnUiThread(() ->
                            new androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle(r.bestMatch != null
                                            ? "Found a working DID"
                                            : "No DID responded")
                                    .setMessage(r.report)
                                    .setPositiveButton("OK", null)
                                    .show());
                }, "FuelProbeUI").start();
                return true;
            } else if (id == R.id.nav_selftest) {
                Toast.makeText(this, "Running self-test (about 12 s)…",
                        Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    SelfTestRunner.Report r = SelfTestRunner.run(this);
                    runOnUiThread(() ->
                            new androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle(r.allPassed ? "Self-test PASSED" : "Self-test FAILED")
                                    .setMessage(r.text)
                                    .setPositiveButton("OK", null)
                                    .show());
                }, "SelfTest").start();
                return true;
            } else if (id == R.id.nav_bt_reset) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Reset Bluetooth pairing?")
                        .setMessage("Use this when the OBD adapter is stuck and no data is coming through.\n\n"
                                + "This will:\n"
                                + "  • disconnect the current OBD session\n"
                                + "  • unpair the saved OBD adapter\n"
                                + "  • open Bluetooth settings so you can pair it again\n\n"
                                + "Reboot the adapter (unplug/re-plug) before re-pairing for best results.")
                        .setPositiveButton("Reset", (d, w) -> bluetoothHelper.clearBluetoothCache())
                        .setNegativeButton("Cancel", null)
                        .show();
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

    /**
     * Notify the poll thread that the active group changed.
     * Non-blocking: the supplier already re-reads {@code currentGroup} on every
     * loop iteration; we just interrupt the sleep so the swap takes effect now
     * instead of waiting up to one cycle.
     */
    private void restartIfConnected() {
        if (obdManager != null && obdManager.isConnected()) {
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "Poll group -> " + currentGroup
                            + " (interval=" + currentGroup.getIntervalMs() + "ms, "
                            + currentGroup.getSensors().size() + " cmds)");
            obdManager.swapPollGroup();
        }
    }

    /** Bind the Welcome screen's Connect button + live status to BluetoothHelper. */
    private void wireWelcomeScreen(View root) {
        TextView statusTv = root.findViewById(R.id.tvWelcomeStatus);
        android.widget.Button btn = root.findViewById(R.id.btnWelcomeConnect);
        if (statusTv == null || btn == null) return;

        // Vector-based hero — combines four subtle motion layers so the car
        // reads as "alive" without any bitmap cost. Each animator is created
        // once and GCs with the View.
        //   1. translationY  — floating up and down 5dp / 2.4s
        //   2. translationX  — slow horizontal drift ±7dp / 6.8s
        //   3. scaleY        — gentle breathing 1.00 → 1.04 / 2.4s
        //   4. rotation      — barely-perceptible tilt ±0.8° / 4.6s
        View car = root.findViewById(R.id.imgWelcomeCar);
        if (car != null) {
            float density = getResources().getDisplayMetrics().density;
            android.view.animation.AccelerateDecelerateInterpolator ease =
                    new android.view.animation.AccelerateDecelerateInterpolator();

            android.animation.ObjectAnimator floatY =
                    android.animation.ObjectAnimator.ofFloat(car, "translationY",
                            0f, -5f * density);
            floatY.setDuration(2400L);
            floatY.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
            floatY.setRepeatMode(android.animation.ObjectAnimator.REVERSE);
            floatY.setInterpolator(ease);

            android.animation.ObjectAnimator drift =
                    android.animation.ObjectAnimator.ofFloat(car, "translationX",
                            -7f * density, 7f * density);
            drift.setDuration(6800L);
            drift.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
            drift.setRepeatMode(android.animation.ObjectAnimator.REVERSE);
            drift.setInterpolator(ease);

            android.animation.ObjectAnimator breathe =
                    android.animation.ObjectAnimator.ofFloat(car, "scaleY",
                            1.0f, 1.04f);
            breathe.setDuration(2400L);
            breathe.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
            breathe.setRepeatMode(android.animation.ObjectAnimator.REVERSE);
            breathe.setInterpolator(ease);

            android.animation.ObjectAnimator tilt =
                    android.animation.ObjectAnimator.ofFloat(car, "rotation",
                            -0.8f, 0.8f);
            tilt.setDuration(4600L);
            tilt.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
            tilt.setRepeatMode(android.animation.ObjectAnimator.REVERSE);
            tilt.setInterpolator(ease);

            floatY.start();
            drift.start();
            breathe.start();
            tilt.start();
        }

        Runnable refresh = () -> {
            boolean connected = obdManager != null && obdManager.isConnected();
            if (connected) {
                statusTv.setText("● Connected");
                statusTv.setTextColor(android.graphics.Color.parseColor("#03DAC5"));
                btn.setText("GO TO DASHBOARD");
            } else {
                statusTv.setText("● Not connected");
                statusTv.setTextColor(android.graphics.Color.parseColor("#FF6E6E"));
                btn.setText("CONNECT TO CAR");
            }
        };
        refresh.run();
        btn.setOnClickListener(v -> {
            if (obdManager != null && obdManager.isConnected()) {
                // Already connected — jump straight to the live dashboard.
                MenuItem dash = navigationView.getMenu().findItem(R.id.nav_dashboard);
                if (dash != null) {
                    navigationView.getMenu().performIdentifierAction(dash.getItemId(), 0);
                }
            } else {
                openConnectFlow();
            }
        });
    }

    /**
     * Swap the guided connect flow into the content container. Entry point for
     * the Welcome button, the disconnect drawer item when offline, and the
     * connection pill tap when offline. On successful connect the flow calls
     * onDone which routes the user to the dashboard.
     */
    void openConnectFlow() {
        if (connectFlowActive) return;
        detachAllControllers();
        View newView = getLayoutInflater().inflate(R.layout.layout_connect_flow, null);
        FrameLayout container = findViewById(R.id.content_container);
        container.removeAllViews();
        container.addView(newView);
        TextView tvTitle = findViewById(R.id.tvTitle);
        if (tvTitle != null) tvTitle.setText("Connect");
        if (connectFlowController == null) {
            connectFlowController = new ConnectFlowController(this, bluetoothHelper, obdManager);
        }
        connectFlowController.setOnDone(() -> {
            connectFlowActive = false;
            connectFlowController.detach();
            // After success, drop the user on the live dashboard. On cancel
            // (no active connection) show Welcome instead so they aren't
            // staring at a blank container.
            MenuItem target = navigationView.getMenu().findItem(
                    obdManager != null && obdManager.isConnected()
                            ? R.id.nav_dashboard
                            : R.id.nav_welcome);
            if (target != null) {
                navigationView.getMenu().performIdentifierAction(target.getItemId(), 0);
            }
        });
        connectFlowController.attach(newView);
        connectFlowActive = true;
    }

    /** Detach every screen controller that owns UI callbacks so a swap doesn't
     *  leave a stale onValue routing to a detached view. Central place so new
     *  controllers only have to add one line. */
    private void detachAllControllers() {
        if (analyticsActive) { analyticsController.detach(); analyticsActive = false; }
        if (sprintActive) { sprintController.detach(); sprintActive = false; }
        if (faultCodesActive) { faultCodesController.detach(); faultCodesActive = false; }
        if (debugLogActive) { debugLogController.detach(); debugLogActive = false; }
        if (dashboardActive) { dashboardController.detach(); dashboardActive = false; }
        if (sensorsActive) { sensorsController.detach(); sensorsActive = false; }
        if (garageActive) { garageController.detach(); garageActive = false; }
        if (liveDataActive) { liveDataController.detach(); liveDataActive = false; }
        if (knowledgeActive) { knowledgeController.detach(); knowledgeActive = false; }
        if (serviceActive) { serviceController.detach(); serviceActive = false; }
        if (gaugeDashActive) { gaugeDashController.detach(); gaugeDashActive = false; }
        if (hudDashActive) { hudDashController.detach(); hudDashActive = false; }
        if (odometerActive) { odometerController.detach(); odometerActive = false; }
        if (aiEstimatorActive) { aiEstimatorController.detach(); aiEstimatorActive = false; }
        if (carAdvisorActive) { carAdvisorController.detach(); carAdvisorActive = false; }
        if (scanReportsActive) { scanReportsController.detach(); scanReportsActive = false; }
        if (connectFlowActive && connectFlowController != null) {
            connectFlowController.detach();
            connectFlowActive = false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions,
                                           @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // ConnectFlow uses request code 2001 for BT_CONNECT / BT_SCAN. Route the
        // result back so the flow can advance to the next step.
        if (requestCode == 2001 && connectFlowActive && connectFlowController != null) {
            connectFlowController.onPermissionResult();
        }
    }


    /**
     * Refresh the unified status bar:
     *   • Connection pill (left) — blue=connected, amber=reconnecting, red=error/disconnected
     *   • Thermal chip (right) — visible only when coolant ≥95°C or oil ≥105°C
     * DTC chip is updated by {@link #setDtcCount}.
     */
    private void updateConnectionBanner() {
        if (connectionPill != null) {
            boolean connected = obdManager != null && obdManager.isConnected();
            ObdService.ConnectionState st = ObdService.getConnectionState();
            int attempts = ObdService.getReconnectAttempts();
            String text;
            int bgRes;
            if (connected) {
                text = "● Connected";
                bgRes = R.drawable.status_pill_ok;
            } else if (st == ObdService.ConnectionState.CONNECTING) {
                text = "● Searching…";
                bgRes = R.drawable.status_pill_warn;
            } else if (st == ObdService.ConnectionState.RECONNECTING) {
                text = "● Reconnecting " + attempts;
                bgRes = R.drawable.status_pill_warn;
            } else if (st == ObdService.ConnectionState.ERROR) {
                text = "● Adapter unreachable";
                bgRes = R.drawable.status_pill_err;
            } else {
                text = "● Disconnected";
                bgRes = R.drawable.status_pill_err;
            }
            connectionPill.setText(text);
            connectionPill.setBackgroundResource(bgRes);
        }

        if (thermalChip != null) {
            // Hysteresis: once shown, keep showing until the value drops a few °C
            // below the trigger. Prevents the chip from flapping on/off when the
            // coolant oscillates around 95 °C in stop-and-go traffic.
            boolean wasShowing = thermalChip.getVisibility() == View.VISIBLE;
            String hot = null;
            if (!Double.isNaN(lastCoolant)
                    && (lastCoolant >= 95.0 || (wasShowing && lastCoolant >= 92.0))) {
                hot = "🌡 " + (int) lastCoolant + "°C";
            } else if (!Double.isNaN(lastOil)
                    && (lastOil >= 105.0 || (wasShowing && lastOil >= 102.0))) {
                hot = "🛢 " + (int) lastOil + "°C";
            }
            if (hot == null) {
                thermalChip.setVisibility(View.GONE);
            } else {
                thermalChip.setText(hot);
                thermalChip.setVisibility(View.VISIBLE);
            }
        }
    }

    /** Called from FaultCodesController after a scan to drive the DTC chip. */
    public void setDtcCount(int n) {
        if (dtcChip == null) return;
        if (n <= 0) {
            dtcChip.setVisibility(View.GONE);
        } else {
            dtcChip.setText(n + (n == 1 ? " DTC" : " DTCs"));
            dtcChip.setBackgroundResource(R.drawable.status_pill_warn);
            dtcChip.setTextColor(0xFF000000);
            dtcChip.setVisibility(View.VISIBLE);
        }
    }

    private void updateUI(String name, double value, String unit) {
        if (sprintActive && "Vehicle Speed".equals(name)) {
            sprintController.onSpeedValue(value);
        }

        if (dashboardActive && dashboardController.isAttached()) {
            dashboardController.onValue(name, value);
            return;
        }

        if (gaugeDashActive && gaugeDashController.isAttached()) {
            gaugeDashController.onValue(name, value);
            return;
        }

        if (hudDashActive && hudDashController.isAttached()) {
            hudDashController.onValue(name, value);
            return;
        }

        if (liveDataActive && liveDataController.isAttached()) {
            liveDataController.onValue(name, value);
            return;
        }

        FrameLayout container = findViewById(R.id.content_container);
        if (container.getChildCount() == 0) return;
        View activeLayout = container.getChildAt(0);
        String formatted = String.format("%.1f %s", value, unit);

        // Legacy fallback for screens that still ship hidden TextViews matching the
        // historical "Name: value unit" pipeline (welcome / debuglog placeholders, etc.).
        // The 10 dashboard tile views are updated through DashboardController above.
        int tvId = 0;
        switch (name) {
            case "Vehicle Speed":     tvId = R.id.tvSpeed; break;
            case "RPM":               tvId = R.id.tvRpm; break;
            case "Coolant Temp":      tvId = R.id.tvCoolant; break;
            case "Intake Air Temp":   tvId = R.id.tvIntake; break;
            case "Throttle Position": tvId = R.id.tvThrottle; break;
            case "Battery Voltage":   tvId = R.id.tvBattery; break;
            case "Engine Load":       tvId = R.id.tvEngineLoad; break;
            case "Fuel Level":        tvId = R.id.tvFuelLevel; break;
            case "Mass Air Flow":     tvId = R.id.tvMassAirFlow; break;
            case "Oil Temp":          tvId = R.id.tvOil; break;
        }

        if (tvId != 0) {
            TextView tv = activeLayout.findViewById(tvId);
            if (tv != null) tv.setText(name + ": " + formatted);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        connStateHandler.post(connStateTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        connStateHandler.removeCallbacks(connStateTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // onPause normally already removes connStateTick, but if the Activity is
        // destroyed without going through onPause (e.g. finish() from background),
        // the anonymous Runnable would otherwise leak this Activity until process end.
        connStateHandler.removeCallbacksAndMessages(null);
        analyticsController.detach();
        sprintController.detach();
        debugLogController.detach();
        dashboardController.detach();
        sensorsController.detach();
        garageController.detach();
        liveDataController.detach();
        knowledgeController.detach();
        serviceController.detach();
        gaugeDashController.detach();
        hudDashController.detach();
        odometerController.detach();
        aiEstimatorController.detach();
        carAdvisorController.detach();
        scanReportsController.detach();
        if (faultCodesController != null) faultCodesController.detach();
        if (obdManager != null && obdManager.isConnected()) obdManager.disconnect();
        alertManager.shutdown();
        ObdService.stop(this);
    }
}
