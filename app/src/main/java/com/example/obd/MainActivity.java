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

    // Single-active-screen bookkeeping. Exactly one controller is attached at
    // a time, so one (screenId, detach) pair replaces the 17 parallel booleans
    // whose hand-copied detach sweeps kept drifting apart — the nav listener's
    // copy omitted the connect flow, which permanently killed the Connect
    // button after a single drawer navigation.
    private static final int SCREEN_NONE = 0;
    private static final int SCREEN_CONNECT_FLOW = -2; // not a drawer item, needs its own id
    private int activeScreenId = SCREEN_NONE;
    private Runnable activeDetach = null;
    // Attaches are posted (they need the view laid out); the generation stamp
    // drops a posted attach that a faster second navigation has superseded,
    // so a controller can't end up attached to an orphaned view.
    private int attachGeneration = 0;

    /** Detach whatever screen is active and cancel any not-yet-run posted attach. */
    private void detachActiveController() {
        attachGeneration++;
        Runnable d = activeDetach;
        activeDetach = null;
        activeScreenId = SCREEN_NONE;
        if (d != null) d.run();
    }

    /** Mark {@code screenId} active and run {@code attach} once {@code root} is laid out. */
    private void setActiveScreen(int screenId, View root, Consumer<View> attach, Runnable detach) {
        activeScreenId = screenId;
        activeDetach = detach;
        final int gen = ++attachGeneration;
        root.post(() -> {
            if (gen != attachGeneration) return; // superseded by a later navigation
            attach.accept(root);
        });
    }

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
    // Latest thermal readings used to drive the status chip. Written on the
    // poll thread (onValue runs there before its runOnUiThread hop), read on
    // the UI thread by updateConnectionBanner — hence volatile.
    private volatile double lastCoolant = Double.NaN;
    private volatile double lastOil = Double.NaN;
    private final Handler connStateHandler = new Handler(Looper.getMainLooper());
    private final Runnable connStateTick = new Runnable() {
        @Override public void run() {
            updateConnectionBanner();
            connStateHandler.postDelayed(this, 1500);
        }
    };

    // Poll-group definitions live in PollGroup.java so the transport layer can
    // reference them without depending on this Activity class.
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

        handleDebugIntent(getIntent());

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

            // Action items first — they don't swap the content view, so the
            // active screen (e.g. a live dashboard) stays attached and keeps
            // updating. The old flow detached every controller up front, which
            // froze the visible screen after e.g. running the self-test.
            if (handleActionItem(id, item)) return true;

            // Screen navigation: exactly one controller is active at a time.
            detachActiveController();

            // Default: let the OS decide; keep-screen-on cleared per nav.
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            View newView = null;
            String title = "";

            if (id == R.id.nav_welcome) {
                newView = getLayoutInflater().inflate(R.layout.layout_welcome, null);
                title = "Hello";
                // Wire the Connect button + hero animation; detach cancels the
                // four INFINITE animators, which previously stacked up on every
                // Welcome visit and burned frames against detached views.
                setActiveScreen(id, newView, this::wireWelcomeScreen, this::stopWelcomeAnimators);

            } else if (id == R.id.nav_dashboard) {
                newView = getLayoutInflater().inflate(R.layout.layout_dashboard, null);
                title = "Dashboard (730li)";
                currentGroup = PollGroup.GROUP_DASHBOARD;
                restartIfConnected();
                // Keep-screen-on while Dashboard is up (app is portrait-locked
                // in the manifest per user preference).
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setActiveScreen(id, newView,
                        v -> dashboardController.attach(v, MainActivity.this, obdManager),
                        dashboardController::detach);

            } else if (id == R.id.nav_sensors) {
                newView = getLayoutInflater().inflate(R.layout.layout_sensors, null);
                title = "Car & Sensors";
                setActiveScreen(id, newView, sensorsController::attach, sensorsController::detach);

            } else if (id == R.id.nav_garage) {
                newView = getLayoutInflater().inflate(R.layout.layout_garage, null);
                title = "Garage";
                setActiveScreen(id, newView, garageController::attach, garageController::detach);

            } else if (id == R.id.nav_faultcodes) {
                newView = getLayoutInflater().inflate(R.layout.layout_faultcodes, null);
                title = "Fault Codes / Reset";
                setActiveScreen(id, newView, faultCodesController::attach, faultCodesController::detach);

            } else if (id == R.id.nav_debuglog) {
                newView = getLayoutInflater().inflate(R.layout.layout_debuglog, null);
                title = "Debug Log";
                setActiveScreen(id, newView, debugLogController::attach, debugLogController::detach);

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
                final SensorInfo.Category presetCat = cat;
                setActiveScreen(id, newView,
                        v -> liveDataController.attach(v, presetCat),
                        liveDataController::detach);

            } else if (id == R.id.nav_analytics) {
                newView = getLayoutInflater().inflate(R.layout.layout_analytics, null);
                title = "Data Analysis";
                currentGroup = PollGroup.GROUP_ANALYTICS;
                restartIfConnected();
                setActiveScreen(id, newView, analyticsController::attach, analyticsController::detach);

            } else if (id == R.id.nav_sprint) {
                newView = getLayoutInflater().inflate(R.layout.layout_sprint, null);
                title = "0–100 Timer";
                currentGroup = PollGroup.GROUP_SPRINT;
                restartIfConnected();
                setActiveScreen(id, newView, sprintController::attach, sprintController::detach);

            } else if (id == R.id.nav_knowledge) {
                newView = getLayoutInflater().inflate(R.layout.layout_knowledge, null);
                title = "Knowledge Base";
                setActiveScreen(id, newView, knowledgeController::attach, knowledgeController::detach);

            } else if (id == R.id.nav_service) {
                newView = getLayoutInflater().inflate(R.layout.layout_service_functions, null);
                title = "Service Functions";
                setActiveScreen(id, newView,
                        v -> serviceController.attach(v, obdManager),
                        serviceController::detach);

            } else if (id == R.id.nav_dashboard_gauges) {
                newView = getLayoutInflater().inflate(R.layout.layout_gauge_dashboard, null);
                title = "Gauge Dashboard";
                currentGroup = PollGroup.GROUP_DASHBOARD;
                restartIfConnected();
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setActiveScreen(id, newView,
                        v -> gaugeDashController.attach(v, obdManager),
                        gaugeDashController::detach);

            } else if (id == R.id.nav_dashboard_hud) {
                newView = getLayoutInflater().inflate(R.layout.layout_hud_dashboard, null);
                title = "HUD Dashboard";
                currentGroup = PollGroup.GROUP_HUD_DASHBOARD;
                restartIfConnected();
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setActiveScreen(id, newView,
                        v -> hudDashController.attach(v, obdManager),
                        hudDashController::detach);

            } else if (id == R.id.nav_odometer) {
                newView = getLayoutInflater().inflate(R.layout.layout_odometer, null);
                title = "Odometer";
                setActiveScreen(id, newView,
                        v -> odometerController.attach(v, obdManager),
                        odometerController::detach);

            } else if (id == R.id.nav_ai_estimator) {
                newView = getLayoutInflater().inflate(R.layout.layout_ai_estimator, null);
                title = "AI Repair Estimator";
                setActiveScreen(id, newView,
                        v -> aiEstimatorController.attach(v, MainActivity.this, obdManager),
                        aiEstimatorController::detach);

            } else if (id == R.id.nav_car_advisor) {
                newView = getLayoutInflater().inflate(R.layout.layout_car_advisor, null);
                title = "Help Me Buy a Car";
                setActiveScreen(id, newView,
                        v -> carAdvisorController.attach(v, MainActivity.this),
                        carAdvisorController::detach);

            } else if (id == R.id.nav_scan_reports) {
                newView = getLayoutInflater().inflate(R.layout.layout_scan_reports, null);
                title = "Scan Reports";
                setActiveScreen(id, newView,
                        v -> scanReportsController.attach(v, MainActivity.this),
                        scanReportsController::detach);
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
     * Drawer items that perform an action instead of swapping the content view.
     * Returns true when {@code id} was handled here — the caller then leaves
     * the currently active screen alone.
     */
    private boolean handleActionItem(int id, MenuItem item) {
        if (id == R.id.nav_disconnect) {
            if (obdManager.isConnected()) {
                // Persist what the trackers have accumulated before the data
                // source goes away — otherwise the last flush interval's worth
                // of odometer distance / gauge seeds is lost.
                OdometerTracker.get(this).flush();
                LastValuesCache.get(this).flush();
                obdManager.disconnect();
                Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
            } else {
                // Offline: guided flow instead of the raw picker dialog.
                openConnectFlow();
            }
            updateConnectMenuTitle(item);
            return true;
        }
        if (id == R.id.nav_clear_data) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Clear app data?")
                    .setMessage("This wipes:\n"
                            + "  • diag + crash logs\n"
                            + "  • DTC event log\n"
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
        }
        if (id == R.id.nav_simulator) {
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
        }
        if (id == R.id.nav_fuel_probe) {
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
        }
        if (id == R.id.nav_selftest) {
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
        }
        if (id == R.id.nav_bt_reset) {
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
        return false;
    }

    /**
     * Debug hooks for `adb shell am start --es action <name>` — keeps the QA
     * loop scriptable without poking the drawer. Also invoked from
     * {@link #onNewIntent} (launchMode=singleTop) so the hooks work against a
     * warm activity; previously they were read in onCreate only, making
     * scripted runs a silent no-op once the app was already open.
     */
    private void handleDebugIntent(android.content.Intent intent) {
        String debugAction = intent == null ? null : intent.getStringExtra("action");
        if (debugAction == null) return;
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
            String question = intent.getStringExtra("q");
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
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDebugIntent(intent);
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

    /** Welcome hero animators — cancelled on detach via {@link #stopWelcomeAnimators}. */
    private final java.util.ArrayList<android.animation.Animator> welcomeAnimators =
            new java.util.ArrayList<>();

    private void stopWelcomeAnimators() {
        for (android.animation.Animator a : welcomeAnimators) a.cancel();
        welcomeAnimators.clear();
    }

    /** Bind the Welcome screen's Connect button + live status to BluetoothHelper. */
    private void wireWelcomeScreen(View root) {
        TextView statusTv = root.findViewById(R.id.tvWelcomeStatus);
        android.widget.Button btn = root.findViewById(R.id.btnWelcomeConnect);
        if (statusTv == null || btn == null) return;

        // Vector-based hero — combines four subtle motion layers so the car
        // reads as "alive" without any bitmap cost. The animators are INFINITE,
        // so they are registered in welcomeAnimators and cancelled on detach —
        // "GCs with the View" was wishful thinking; each Welcome visit used to
        // stack four more animators ticking against detached views.
        stopWelcomeAnimators();
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
            welcomeAnimators.add(floatY);
            welcomeAnimators.add(drift);
            welcomeAnimators.add(breathe);
            welcomeAnimators.add(tilt);
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
        if (activeScreenId == SCREEN_CONNECT_FLOW) return;
        detachActiveController();
        // A dashboard entry point (pill tap) would otherwise carry its
        // keep-screen-on flag into the connect flow.
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
            detachActiveController();
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
        // Attach synchronously — the flow doesn't need a measured view, and a
        // posted attach would leave a window where the BT receiver isn't
        // registered while the screen is already visible.
        connectFlowController.attach(newView);
        activeScreenId = SCREEN_CONNECT_FLOW;
        activeDetach = connectFlowController::detach;
        attachGeneration++; // invalidate any still-pending posted attach
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions,
                                           @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // ConnectFlow uses request code 2001 for BT_CONNECT / BT_SCAN. Route the
        // result back so the flow can advance to the next step.
        if (requestCode == 2001 && activeScreenId == SCREEN_CONNECT_FLOW
                && connectFlowController != null) {
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
        // Dispatch on isAttached() alone: only one controller can be attached
        // at a time now, so the old parallel boolean checks (whose desync could
        // starve the genuinely active dashboard) are gone.
        if (activeScreenId == R.id.nav_sprint && "Vehicle Speed".equals(name)) {
            sprintController.onSpeedValue(value);
        }

        if (dashboardController.isAttached()) {
            dashboardController.onValue(name, value);
            return;
        }

        if (gaugeDashController.isAttached()) {
            gaugeDashController.onValue(name, value);
            return;
        }

        if (hudDashController.isAttached()) {
            hudDashController.onValue(name, value);
            return;
        }

        if (liveDataController.isAttached()) {
            liveDataController.onValue(name, value);
            return;
        }

        FrameLayout container = findViewById(R.id.content_container);
        if (container.getChildCount() == 0) return;
        View activeLayout = container.getChildAt(0);
        String formatted = String.format(java.util.Locale.US, "%.1f %s", value, unit);

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
        // Cheap prefs writes — make sure accumulated odometer distance and the
        // gauge-seed cache survive a process kill while backgrounded.
        OdometerTracker.get(this).flush();
        LastValuesCache.get(this).flush();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // onPause normally already removes connStateTick, but if the Activity is
        // destroyed without going through onPause (e.g. finish() from background),
        // the anonymous Runnable would otherwise leak this Activity until process end.
        connStateHandler.removeCallbacksAndMessages(null);
        detachActiveController();
        alertManager.shutdown();
        // Only tear down the OBD session when the Activity is going away for
        // good. On a system-initiated recreate the service keeps the reconnect
        // watchdog alive and onCreate re-binds fresh instances.
        if (isFinishing()) {
            if (obdManager != null) obdManager.disconnect();
            ObdService.stop(this);
            // Clear the service's static refs — they hold this Activity's
            // context and would leak the instance otherwise.
            ObdService.unbind();
        }
    }
}
