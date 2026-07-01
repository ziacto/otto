package com.example.obd;

import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight BMW-focused VIN decoder. Pure parser — no I/O, no asset loads.
 *
 * <p>A VIN is 17 characters. We extract:
 * <ul>
 *   <li>positions 1-3 (WMI) — World Manufacturer Identifier</li>
 *   <li>positions 4-7 — model code (BMW uses 4-char vehicle code, e.g. "HM21" = E65 730i)</li>
 *   <li>position 10 — model year letter</li>
 *   <li>position 11 — plant code</li>
 *   <li>positions 12-17 — sequential serial</li>
 * </ul>
 *
 * <p>BMW's 4-character vehicle code embeds chassis + variant + market + body style.
 * The first two letters are the chassis (e.g. "HM" = E65 sedan). The known
 * E60/E65/E90-era code table lives in {@link #MODELS} — add codes freely; unknown
 * codes fall back to "Unknown BMW" without crashing.</p>
 */
public final class VinDecoder {

    private VinDecoder() {}

    public static final class Result {
        public final String vin;
        public final String wmi;            // e.g. "WBA"
        public final String chassisCode;    // e.g. "E65"
        public final String modelName;      // e.g. "730li (E65)"
        public final String engineHint;     // e.g. "N52B30 inline-6"
        public final int    modelYear;      // 4-digit year, 0 if unknown
        public final String plant;          // e.g. "Dingolfing"
        public final String serial;         // last 6 chars

        Result(String vin, String wmi, String chassis, String model, String engine,
               int year, String plant, String serial) {
            this.vin = vin; this.wmi = wmi; this.chassisCode = chassis;
            this.modelName = model; this.engineHint = engine;
            this.modelYear = year; this.plant = plant; this.serial = serial;
        }
    }

    /**
     * Parse a 17-character VIN. Returns null if the input isn't a plausible VIN
     * (length, charset). Unknown BMW codes still parse — we just guess.
     */
    public static Result decode(String vin) {
        if (vin == null) return null;
        vin = vin.trim().toUpperCase();
        if (vin.length() != 17) return null;
        if (!vin.matches("[A-HJ-NPR-Z0-9]{17}")) return null; // VIN charset (no I/O/Q)

        String wmi = vin.substring(0, 3);
        String code4 = vin.substring(3, 7);  // BMW vehicle code
        char yearChar = vin.charAt(9);
        char plantChar = vin.charAt(10);
        String serial = vin.substring(11);

        String[] modelInfo = MODELS.getOrDefault(code4.substring(0, 2),
                new String[] { "Unknown BMW", "—", "—" });
        String chassis = modelInfo[0];
        String model = modelInfo[1];
        String engine = modelInfo[2];

        int year = decodeYear(yearChar);
        String plant = PLANTS.getOrDefault(plantChar, "Unknown plant (" + plantChar + ")");

        return new Result(vin, wmi, chassis, model, engine, year, plant, serial);
    }

    /** ISO 3779 year letter → 4-digit year. Cycles every 30 years; we resolve to 2001-2030
     *  which covers every plausible E65 + every modern BMW we care about. */
    private static int decodeYear(char c) {
        // Year code rotation: 1 starts 2001, then 2..9 = 2002..2009, A=2010, B=2011, ... ,
        // we skip I/O/Q/U/Z. The mapping below covers everything since 2001.
        switch (c) {
            case '1': return 2001; case '2': return 2002; case '3': return 2003;
            case '4': return 2004; case '5': return 2005; case '6': return 2006;
            case '7': return 2007; case '8': return 2008; case '9': return 2009;
            case 'A': return 2010; case 'B': return 2011; case 'C': return 2012;
            case 'D': return 2013; case 'E': return 2014; case 'F': return 2015;
            case 'G': return 2016; case 'H': return 2017; case 'J': return 2018;
            case 'K': return 2019; case 'L': return 2020; case 'M': return 2021;
            case 'N': return 2022; case 'P': return 2023; case 'R': return 2024;
            case 'S': return 2025; case 'T': return 2026; case 'V': return 2027;
            case 'W': return 2028; case 'X': return 2029; case 'Y': return 2030;
            default:  return 0;
        }
    }

    /** BMW vehicle-code first 2 letters → [chassis code, model name, engine hint]. */
    private static final Map<String, String[]> MODELS = new HashMap<>();
    static {
        // E65/E66 7-series (2002-2008) — our primary target
        MODELS.put("HL", new String[]{"E65", "735i (E65)",        "N62B36 V8"});
        MODELS.put("HM", new String[]{"E65", "730i / 730li (E65)", "M54B30 / N52B30 inline-6"});
        MODELS.put("HN", new String[]{"E65", "745i / 745Li (E65)", "N62B44 V8"});
        MODELS.put("HP", new String[]{"E66", "750i / 750Li (E66)", "N62B48 V8"});
        MODELS.put("HR", new String[]{"E66", "760i / 760Li (E66)", "N73B60 V12"});

        // E60/E61 5-series (2004-2010)
        MODELS.put("NA", new String[]{"E60", "520i / 520d (E60)", "N20 / M47"});
        MODELS.put("NB", new String[]{"E60", "523i / 525i (E60)", "N52 inline-6"});
        MODELS.put("NC", new String[]{"E60", "528i / 530i (E60)", "N52 inline-6"});
        MODELS.put("NE", new String[]{"E60", "535i (E60)",        "N54 twin-turbo I6"});
        MODELS.put("NW", new String[]{"E60", "550i (E60)",        "N62 V8"});

        // E90/E91/E92/E93 3-series (2005-2013)
        MODELS.put("VA", new String[]{"E90", "318i / 320i (E90)", "N46 / N43"});
        MODELS.put("VB", new String[]{"E90", "323i / 325i (E90)", "N52 inline-6"});
        MODELS.put("VC", new String[]{"E90", "328i (E90)",        "N52 / N51"});
        MODELS.put("VD", new String[]{"E90", "330i (E90)",        "N52 / N51"});
        MODELS.put("PH", new String[]{"E92", "335i coupe (E92)",  "N54 twin-turbo I6"});

        // F-series 5/7
        MODELS.put("FZ", new String[]{"F10", "535i (F10)",        "N55 single-turbo I6"});
        MODELS.put("KS", new String[]{"F01", "750i (F01)",        "N63 twin-turbo V8"});

        // G-series modern
        MODELS.put("JA", new String[]{"G30", "530i (G30)",        "B48 turbo I4"});
    }

    /** Plant code (position 11). Common BMW plants only. */
    private static final Map<Character, String> PLANTS = new HashMap<>();
    static {
        PLANTS.put('A', "Munich (Germany)");
        PLANTS.put('B', "Dingolfing (Germany)");
        PLANTS.put('C', "Regensburg (Germany)");
        PLANTS.put('D', "Berlin (Germany)");
        PLANTS.put('E', "Leipzig (Germany)");
        PLANTS.put('F', "Spartanburg (USA)");
        PLANTS.put('G', "Graz (Austria, Magna Steyr)");
        PLANTS.put('K', "Born (Netherlands, VDL Nedcar)");
    }
}
