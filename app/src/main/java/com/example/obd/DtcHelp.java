package com.example.obd;

import android.net.Uri;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Curated help payload for a DTC: parts to check, YouTube search query, AI prompt,
 * and ready-to-launch deeplinks for free chat tools and parts retailers.
 *
 * <p>We deliberately do NOT call any AI API ourselves — no API key, no paywall, no
 * server. Instead each DTC produces a perfectly-crafted prompt that the user can
 * paste (or one-tap launch) into whichever free chat tool they prefer: ChatGPT,
 * Claude, Gemini, or Perplexity. The app is the smart prompt builder; the AI lives
 * in the user's browser. Same logic for parts: we build searches against
 * RockAuto / FCP Euro / ECS Tuning / eBay so the user picks their store.</p>
 *
 * <p>The static {@link #PARTS} and {@link #PROCEDURES} maps cover the common E65
 * faults from {@link DtcDictionary}. Codes not in the maps fall back to the DTC
 * title from the dictionary — the help screen still works, just with less detail.</p>
 */
public final class DtcHelp {

    private DtcHelp() {}

    /** What a user can search for, buy, or test for this code. */
    public static final class Help {
        public final String code;
        public final String title;
        public final String severity;
        public final String cause;
        public final int diyRating;          // 1 easy .. 5 specialist
        public final List<String> parts;     // ["VANOS solenoid", "Oil filter"...]
        public final String procedure;       // Short DIY hint, one paragraph
        Help(String code, String title, String severity, String cause, int diy,
             List<String> parts, String procedure) {
            this.code = code; this.title = title; this.severity = severity;
            this.cause = cause; this.diyRating = diy; this.parts = parts; this.procedure = procedure;
        }
    }

    /**
     * Pure curated map of DTC → likely-parts + procedure hint. Sourced from BMW
     * E65/E60 forum consensus (Bimmerforums, E65club, BimmerPost). The DTC
     * dictionary already supplies title/severity/cause — this layer adds the
     * "what do I buy?" and "what do I do?" answers.
     */
    private static final Map<String, String[]> PARTS = new HashMap<>();
    private static final Map<String, String> PROCEDURES = new HashMap<>();

    static {
        // VANOS family
        parts("P0010", "VANOS intake solenoid", "Engine oil + filter (clean galleries)");
        parts("P0011", "VANOS intake solenoid", "Engine oil 5W-30 LL-01");
        parts("P0012", "VANOS intake solenoid", "Engine oil 5W-30 LL-01");
        parts("P0014", "VANOS exhaust solenoid");
        parts("P0015", "VANOS exhaust solenoid");
        proc("P0010", "Remove the VANOS solenoid (10 mm hex), inspect the o-ring, soak in carb cleaner. Replace if pitted. Always replace o-ring when reinstalling.");
        proc("P0011", "Check oil level first — low pressure mimics this code. If oil OK, clean intake VANOS solenoid as above.");

        // Timing chain (serious)
        parts("P0016", "Timing chain kit", "Camshaft sensor", "Chain guides");
        parts("P0017", "Timing chain kit", "Exhaust camshaft sensor");
        proc("P0016", "Stretched chain is an N52 weak point past 100k km. Do NOT keep driving — chain can skip a tooth and bend valves. Plan a full timing job with chain + guides + tensioner.");

        // MAF / Air metering
        parts("P0100", "MAF sensor (Bosch HFM)", "MAF cleaner spray");
        parts("P0101", "MAF sensor (Bosch HFM)", "Intake boot");
        parts("P0113", "MAF sensor (combined IAT)", "Intake air temp sensor");
        proc("P0100", "Remove MAF (2 torx), spray Bosch MAF cleaner into the sensor element from 6 inches away. Let dry 10 min. Reinstall. Replace only if cleaning didn't help.");
        proc("P0101", "Inspect the intake boot between MAF and throttle for cracks. A pinhole leak between MAF and throttle is the most common cause.");

        // Coolant
        parts("P0117", "Coolant temp sensor", "Coolant G48 mix");
        parts("P0118", "Coolant temp sensor", "Wiring loom check");
        parts("P0128", "Thermostat assembly", "Coolant", "Thermostat housing gasket");
        proc("P0128", "Stuck-open thermostat. Replace with OEM Mahle/Behr unit. Don't fit an aftermarket cheapie — failure rate is high.");

        // O2 / Catalyst
        parts("P0130", "Pre-cat O2 sensor (Bank 1 Sensor 1) — Bosch");
        parts("P0133", "Pre-cat O2 sensor (Bank 1 Sensor 1) — Bosch");
        parts("P0135", "Pre-cat O2 sensor (Bank 1 Sensor 1) — Bosch");
        parts("P0141", "Post-cat O2 sensor (Bank 1 Sensor 2)");
        parts("P0420", "Post-cat O2 sensor first", "Catalytic converter (last resort)");
        proc("P0420", "Verify the POST-cat O2 sensor is healthy before replacing the cat — it's the cheaper part. Aged sensors falsely flag cat efficiency. Smoke test the exhaust system upstream of the cat for leaks.");

        // Misfires
        parts("P0300", "Ignition coil pack ×6", "NGK or Bosch spark plugs ×6");
        parts("P0301", "Ignition coil (cyl 1)", "Spark plug (cyl 1)");
        parts("P0302", "Ignition coil (cyl 2)", "Spark plug (cyl 2)");
        parts("P0303", "Ignition coil (cyl 3)", "Spark plug (cyl 3)");
        parts("P0304", "Ignition coil (cyl 4)", "Spark plug (cyl 4)");
        parts("P0305", "Ignition coil (cyl 5)", "Spark plug (cyl 5)");
        parts("P0306", "Ignition coil (cyl 6)", "Spark plug (cyl 6)");
        proc("P0301", "Swap the coil with another cylinder. If the misfire follows the coil → new coil. If it stays on cyl 1 → swap the plug. Then injector. Coils are ~$30 each — replace as a set if any are over 60k km.");

        // Lean / vacuum
        parts("P0171", "CCV (crankcase vent) valve", "Oil separator", "Vacuum hoses", "DISA o-ring");
        parts("P1083", "CCV valve", "DISA flap gasket", "Vacuum hoses");
        proc("P0171", "Top three causes on N52: cracked CCV diaphragm, leaking oil separator, perished DISA gasket. Smoke test the intake — any swirl coming from the CCV area = found it. ~$60 fix.");

        // EVAP
        parts("P0440", "Fuel cap (try first — $20)");
        parts("P0442", "Fuel cap", "Vapor lines", "Leak Detection Pump");
        parts("P0455", "Fuel cap", "Charcoal canister", "Vapor lines");
        proc("P0440", "Tighten fuel cap 3 clicks, clear code, drive 2 cycles. If it returns, smoke-test the EVAP system from the service port.");

        // Speed sensor (the user's known issue from project memory)
        parts("P0500", "Wheel-speed sensor (per corner)", "ABS wheel hub");
        proc("P0500", "On E65 the speedometer is driven by DSC over CAN — there is no separate VSS. Scan DSC module DTCs to find which wheel sensor failed. RR is the most common.");

        // Idle / throttle
        parts("P0506", "DISA valve", "Throttle body (cleaning)");
        parts("P0507", "Throttle body cleaning", "Idle control");
        proc("P0506", "Remove throttle body, scrub the plate and bore with throttle-body cleaner. Reset adaptations with a battery disconnect (10 min). Check DISA flap for the broken-pin failure mode.");

        // ECU / serious
        parts("P0601", "DME (specialist repair)");
        parts("P0606", "DME (specialist repair)");
        proc("P0601", "Try a battery disconnect for 30 min first — sometimes clears soft corruption. If persistent, the DME needs specialist repair or replacement+coding. NOT a DIY job.");
    }

    private static void parts(String code, String... items) {
        PARTS.put(code, items);
    }
    private static void proc(String code, String text) {
        PROCEDURES.put(code, text);
    }

    /** Sentinel value displayed when we have no parts curation for a code. */
    public static final String PARTS_FALLBACK =
            "(no curated parts list — search YouTube or ask AI)";

    /** Build the help payload for a code. Always returns non-null. */
    public static Help forCode(String code, DtcDictionary.Entry dict) {
        String title = dict != null ? dict.title : "Unknown code";
        String sev = dict != null ? dict.severity : "medium";
        String cause = dict != null ? dict.cause : "";
        int diy = dict != null ? dict.diy : 3;
        String[] partsArr = PARTS.get(code);
        List<String> parts = partsArr == null
                ? Collections.singletonList(PARTS_FALLBACK)
                : Arrays.asList(partsArr);
        String procedure = PROCEDURES.getOrDefault(code, "");
        return new Help(code, title, sev, cause, diy, parts, procedure);
    }

    /** True if the help has no curated parts — search-by-keyword would return junk. */
    public static boolean hasCuratedParts(Help help) {
        return !help.parts.isEmpty() && !PARTS_FALLBACK.equals(help.parts.get(0));
    }

    /**
     * One-paragraph prompt suitable for ChatGPT / Claude / Gemini / Perplexity.
     * Includes the vehicle context + the code + any live readings the caller has.
     * Plain text — open chat tools render it correctly.
     */
    public static String aiPrompt(Help help, String vinOrModel,
                                  Map<String, Double> liveReadings) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("I have a BMW ");
        sb.append(vinOrModel == null || vinOrModel.isEmpty() ? "730li (E65)" : vinOrModel);
        sb.append(" showing OBD-II code ").append(help.code);
        if (help.title != null && !help.title.isEmpty()) {
            sb.append(" (\"").append(help.title).append("\")");
        }
        sb.append(". ");
        if (liveReadings != null && !liveReadings.isEmpty()) {
            sb.append("Live readings: ");
            int i = 0;
            for (Map.Entry<String, Double> e : liveReadings.entrySet()) {
                if (i++ > 0) sb.append(", ");
                sb.append(e.getKey()).append(" ")
                  .append(String.format(java.util.Locale.US, "%.1f", e.getValue()));
            }
            sb.append(". ");
        }
        sb.append("Walk me through diagnosis in order of likelihood and cost — cheapest checks first. ")
          .append("For each suspect, name the BMW part number if you know it, the symptom that confirms ")
          .append("it, and roughly what the part costs in the US. Note any BMW E65-specific gotchas. ")
          .append("Assume I have a basic toolkit and an OBD-II scanner.");
        return sb.toString();
    }

    // --- External URL builders. Hand-rolled URI assembly so we don't depend on a network. ---

    /** YouTube search results for "BMW E65 P0420 fix" style queries. */
    public static Uri youtubeSearch(Help help) {
        String q = "BMW E65 " + help.code + " " + help.title + " fix";
        return Uri.parse("https://www.youtube.com/results?search_query="
                + Uri.encode(q));
    }

    /** RockAuto BMW 2007 730li model page — parts are then filtered by category. */
    public static Uri rockAutoBmw() {
        return Uri.parse("https://www.rockauto.com/en/catalog/bmw,2007,730li");
    }

    /** FCP Euro keyword search on the same parts list — good prices for OEM. */
    public static Uri fcpEuroSearch(Help help) {
        String q = (help.parts.isEmpty() ? help.title : help.parts.get(0)) + " BMW E65";
        return Uri.parse("https://www.fcpeuro.com/search?keywords="
                + Uri.encode(q));
    }

    /** eBay Motors search — broadest catalog, often cheapest. */
    public static Uri ebayMotorsSearch(Help help) {
        String q = (help.parts.isEmpty() ? help.title : help.parts.get(0)) + " BMW 730li E65";
        return Uri.parse("https://www.ebay.com/sch/i.html?_nkw="
                + Uri.encode(q));
    }

    /** Pre-filled ChatGPT prompt. Opens chatgpt.com with the question in the URL. */
    public static Uri askChatGpt(String prompt) {
        // chatgpt.com supports ?q=... as the initial message on the free tier.
        return Uri.parse("https://chatgpt.com/?q=" + Uri.encode(prompt));
    }

    /** Pre-filled Claude prompt. */
    public static Uri askClaude(String prompt) {
        // claude.ai's "new chat with q param" is best-effort — falls back to the home page
        // which still lets the user paste. We add the prompt to the clipboard via the
        // launching activity for paste convenience.
        return Uri.parse("https://claude.ai/new?q=" + Uri.encode(prompt));
    }

    /** Pre-filled Google Gemini prompt. */
    public static Uri askGemini(String prompt) {
        return Uri.parse("https://gemini.google.com/app?q=" + Uri.encode(prompt));
    }

    /** Pre-filled Perplexity query — strong for sourced auto-repair questions. */
    public static Uri askPerplexity(String prompt) {
        return Uri.parse("https://www.perplexity.ai/?q=" + Uri.encode(prompt));
    }
}
