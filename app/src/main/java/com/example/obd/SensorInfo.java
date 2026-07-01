package com.example.obd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of every PID this app polls, with its description, normal range,
 * BMW-specific notes, and category. Powers the Live Data browser.
 *
 * <p>Each entry is the same name we already use across {@link MainActivity}'s
 * {@code onValue} callback, so the browser can subscribe by name without any
 * extra plumbing.</p>
 */
public final class SensorInfo {

    private SensorInfo() {}

    public enum Category {
        POWERTRAIN("Powertrain"),
        THERMAL("Thermal"),
        FUEL("Fuel & Air"),
        ELECTRICAL("Electrical"),
        PERFORMANCE("Performance"),
        EMISSIONS("Emissions");
        public final String label;
        Category(String label) { this.label = label; }
    }

    /** Static metadata for one sensor (everything except the live reading). */
    public static final class Spec {
        public final String name;
        public final String unit;
        public final Category category;
        public final double minNormal;
        public final double maxNormal;
        public final String description;
        public final String bmwNote;
        Spec(String name, String unit, Category cat, double min, double max,
             String desc, String bmwNote) {
            this.name = name; this.unit = unit; this.category = cat;
            this.minNormal = min; this.maxNormal = max;
            this.description = desc; this.bmwNote = bmwNote;
        }

        /** OK / WARN / OUT_OF_RANGE classification for a given reading. */
        public Status statusOf(double v) {
            if (Double.isNaN(v)) return Status.UNKNOWN;
            double span = maxNormal - minNormal;
            double soft = span * 0.1; // 10% softening for "warn" band
            if (v < minNormal - soft || v > maxNormal + soft) return Status.OUT_OF_RANGE;
            if (v < minNormal || v > maxNormal) return Status.WARN;
            return Status.OK;
        }
    }

    public enum Status { UNKNOWN, OK, WARN, OUT_OF_RANGE }

    /**
     * Single source of truth for sensor metadata. Insertion order = display order
     * within each category. Adding a sensor here makes it appear in the Live Data
     * browser automatically — wire up the matching ObdCommand in
     * {@link MainActivity.PollGroup} to feed it.
     */
    private static final Map<String, Spec> REGISTRY = new LinkedHashMap<>();

    static {
        // POWERTRAIN
        add("RPM", "rpm", Category.POWERTRAIN, 600, 6700,
                "Engine crankshaft speed. Drives every other rotation-based PID.",
                "N52 idle ≈ 700 rpm. Redline at 6700; fuel cut-off at ~7000.");
        add("Vehicle Speed", "km/h", Category.POWERTRAIN, 0, 250,
                "Road speed reported by the DSC module over CAN.",
                "E65 speedo is a CAN message from DSC — not a separate VSS. Sudden drop = wheel speed sensor.");
        add("Engine Load", "%", Category.POWERTRAIN, 0, 90,
                "Calculated load — current torque vs maximum at this RPM.",
                "Idle 12–18%. Cruising flat 25–40%. WOT briefly 90%+.");
        add("Throttle Position", "%", Category.POWERTRAIN, 0, 100,
                "Drive-by-wire throttle plate angle.",
                "Closed should sit 12–16% (idle bypass). Stuck at 0% = TPS fault.");
        add("Relative Throttle", "%", Category.POWERTRAIN, 0, 100,
                "Throttle position relative to its learned closed-position.",
                "Useful when absolute throttle reads high at idle — confirms adaptation drift.");
        add("Accelerator Pedal", "%", Category.POWERTRAIN, 0, 100,
                "Driver's pedal angle sent over CAN to the DME.",
                "Should track 1:1 with your foot. Lag suggests pedal sensor wear.");
        add("Engine Torque", "%", Category.POWERTRAIN, 0, 100,
                "Actual computed engine torque (% of max).",
                "Diverges from Demand Torque when DSC or DME limits power.");
        add("Demand Torque", "%", Category.POWERTRAIN, 0, 100,
                "What the driver / cruise / DSC is asking for.",
                "Stable difference vs Actual = correct. Gap = traction control or limp.");
        add("Timing Advance", "°", Category.POWERTRAIN, -10, 40,
                "Spark timing in degrees before TDC.",
                "N52 cruise ≈ 12–25°. Pulled back = knock detected, check fuel & temps.");

        // THERMAL
        add("Coolant Temp", "°C", Category.THERMAL, 80, 105,
                "Engine coolant temperature at the head.",
                "N52 thermostat opens ~88°C. Past 108°C is danger; past 115°C is damage.");
        add("Oil Temp", "°C", Category.THERMAL, 80, 115,
                "Engine oil temperature.",
                "Read via PID 0x5C on N52 (later DMEs). Past 130°C = back off.");
        add("Intake Air Temp", "°C", Category.THERMAL, -10, 55,
                "Air temperature entering the intake.",
                "Should be ambient + a few °C at speed; +20°C at idle is normal heat soak.");
        add("Charge Air Temperature", "°C", Category.THERMAL, -10, 60,
                "Post-intercooler air temp (turbo engines only).",
                "N52 is NA — no CAT reading. N54/N55 see <50°C cruising, 70+ under boost.");
        add("Ambient Air Temperature", "°C", Category.THERMAL, -40, 50,
                "Outside-air sensor reading.",
                "Drives the heated-mirror trigger and the optional A/C auto-mode.");

        // FUEL & AIR
        add("Mass Air Flow", "g/s", Category.FUEL, 2, 250,
                "Air mass entering the engine per second.",
                "Idle ≈ 3–5 g/s. WOT ≈ 90–120 g/s on N52. Used in fuel calc.");
        add("Fuel Level", "%", Category.FUEL, 0, 100,
                "Tank level from the lift-pump sender.",
                "Twin-sender saddle tank on early E65 — readings jump at half-tank.");
        add("Fuel Pressure", "kPa", Category.FUEL, 300, 500,
                "Low-pressure side fuel rail pressure.",
                "N52 spec 350–400 kPa key-on. Pressure decay >10% over 5 min = leak-down.");
        add("Fuel Consumption", "L/h", Category.FUEL, 0, 30,
                "Calculated instantaneous fuel rate.",
                "Idle ≈ 1.0 L/h. Cruise 7–9 L/h. WOT 25+ L/h.");
        add("Lambda", "λ", Category.FUEL, 0.97, 1.03,
                "Air/fuel ratio measured by the wideband O2.",
                "1.00 = stoich. <1 rich (acceleration), >1 lean (cruise/coast).");
        add("Commanded Lambda", "λ", Category.FUEL, 0.92, 1.05,
                "Target AFR the DME is asking for.",
                "Compare with measured Lambda — drift = O2 or fuel-trim issue.");
        add("Short Term Fuel Trim", "%", Category.FUEL, -10, 10,
                "Live fuel correction the DME is applying right now.",
                ">+10% = lean (vacuum leak / weak fuel pump). <-10% = rich (injector / MAF).");
        add("Long Term Fuel Trim", "%", Category.FUEL, -8, 8,
                "Long-window average trim, learned over many cycles.",
                "Drift from zero is your trend signal. >+8% steady = chase a vacuum leak.");
        add("Injection Timing", "°", Category.FUEL, -10, 30,
                "Injector pulse timing relative to crank.",
                "More relevant on direct-injection (N54/N55). NA N52 sits ~5°.");

        // ELECTRICAL
        add("Battery Voltage", "V", Category.ELECTRICAL, 13.6, 14.6,
                "Adapter-measured KL30 (constant battery) voltage.",
                "Below 12.3 V key-off = battery degrading. Above 14.8 V = alternator overcharging.");
        add("ECU Voltage", "V", Category.ELECTRICAL, 13.5, 14.6,
                "Internal DME supply rail.",
                "Sags by >0.5 V vs adapter voltage = wiring resistance.");

        // PERFORMANCE / TURBO
        add("Boost Pressure", "kPa", Category.PERFORMANCE, 95, 250,
                "Manifold absolute pressure.",
                "N52 NA: ~100 kPa idle, drops to ~30 kPa at WOT in vacuum. Turbo: spikes >200.");
        add("Barometric Pressure", "kPa", Category.PERFORMANCE, 90, 105,
                "Atmospheric pressure. Used to detect altitude.",
                "Sea level ≈ 101. Drop indicates climb or weather.");
        add("Engine Run Time", "s", Category.PERFORMANCE, 0, Double.MAX_VALUE,
                "Seconds since engine start.",
                "Resets on every key-off → start. Useful with TrendEngine.");

        // EMISSIONS
        add("O2 Sensor", "V", Category.EMISSIONS, 0.1, 0.9,
                "Pre-cat narrowband O2 voltage.",
                "Should swing 0.1–0.9 V at idle warm. Flat-line = O2 or heater dead.");
    }

    private static void add(String name, String unit, Category cat,
                            double min, double max, String desc, String bmwNote) {
        REGISTRY.put(name, new Spec(name, unit, cat, min, max, desc, bmwNote));
    }

    /** Spec for a sensor by name (the same name the poll loop emits). */
    public static Spec spec(String name) { return REGISTRY.get(name); }

    /** All sensor names known to the app, in display order. */
    public static List<String> allNames() {
        return new ArrayList<>(REGISTRY.keySet());
    }

    /** Sensor names for one category. */
    public static List<String> namesInCategory(Category cat) {
        List<String> out = new ArrayList<>();
        for (Spec s : REGISTRY.values()) if (s.category == cat) out.add(s.name);
        return out;
    }

    /** All categories, in display order. */
    public static List<Category> allCategories() {
        return Collections.unmodifiableList(Arrays.asList(Category.values()));
    }
}
