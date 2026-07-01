package com.example.obd;

/**
 * BMW module addresses for E60/E65/E90-generation cars.
 *
 * BUS column tells you which physical bus the module sits on:
 *   D_CAN   — accessible from OBD-II pins 6/14 with a generic ELM327. The current app.
 *   K_CAN   — accessible only with a K+DCAN cable (OBD pin 7). Documented here so that
 *             when a K-DCAN cable is plugged in later we already know the addresses.
 *
 * Addressing recipe for D-CAN: ATSH 6F1 (tester header), ATCRA &lt;responseId&gt; (filter
 * for the module's reply), then UDS service requests prefixed with the target byte
 * (the second byte of headerHint, e.g. "12" for DME).
 *
 * Community-documented response IDs (cross-checked against INPA, BMHat, and protocol
 * traces from BimmerLink's sim files which contain ISO 14229 standard commands).
 */
public enum BmwModule {

    // ------------------------- D-CAN (reachable now) -------------------------
    DME("DME (Engine)",             "612", "6F1 12 F1", Bus.D_CAN),
    EGS("EGS (Transmission)",       "618", "6F1 18 F1", Bus.D_CAN),
    DSC("DSC (Stability / ABS)",    "629", "6F1 29 F1", Bus.D_CAN),
    ICM("ICM (Integrated Chassis)", "640", "6F1 40 F1", Bus.D_CAN),  // E65MU only
    EHC("EHC2 (Air Suspension)",    "660", "6F1 60 F1", Bus.D_CAN),
    ZGM("ZGM (Gateway)",            "661", "6F1 61 F1", Bus.D_CAN),
    SZL("SZL (Steering Column)",    "66F", "6F1 6F F1", Bus.D_CAN),
    EMF("EMF (Park Brake)",         "673", "6F1 73 F1", Bus.D_CAN),

    // ------------------------- K-CAN (requires K-DCAN cable) -----------------
    // Documented for future K-DCAN integration. NOT scanned by default — the
    // existing scan code filters to BUS == D_CAN.
    CAS("CAS (Car Access System)",  "600", "6F1 00 F1", Bus.K_CAN),
    KOMBI("KOMBI (Cluster)",        "65F", "6F1 60 F1", Bus.K_CAN),
    FRM("FRM (Footwell / Lights)",  "65E", "6F1 5E F1", Bus.K_CAN),
    IHKA("IHKA (HVAC)",             "65D", "6F1 5D F1", Bus.K_CAN),
    JBE("JBE (Junction Box)",       "63D", "6F1 3D F1", Bus.K_CAN),
    SMFA("SMFA (Driver Seat)",      "672", "6F1 72 F1", Bus.K_CAN),
    SMBF("SMBF (Passenger Seat)",   "673", "6F1 73 F1", Bus.K_CAN),
    RDC("RDC (Tire Pressure)",      "65A", "6F1 5A F1", Bus.K_CAN),
    CCC("CCC (Head Unit)",          "663", "6F1 63 F1", Bus.K_CAN);

    public enum Bus { D_CAN, K_CAN }

    public final String label;
    /** ATCRA filter — response CAN ID from this module. */
    public final String responseId;
    /** Hint for diagnostic logs — actual ATSH the connection layer uses is "6F1". */
    public final String headerHint;
    public final Bus bus;

    BmwModule(String label, String responseId, String headerHint, Bus bus) {
        this.label = label;
        this.responseId = responseId;
        this.headerHint = headerHint;
        this.bus = bus;
    }

    /** Modules reachable via the current D-CAN ELM327 setup. */
    public static BmwModule[] dCanModules() {
        java.util.ArrayList<BmwModule> out = new java.util.ArrayList<>();
        for (BmwModule m : values()) if (m.bus == Bus.D_CAN) out.add(m);
        return out.toArray(new BmwModule[0]);
    }
}
