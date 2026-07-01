package com.example.obd;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Append-only event log of DTC occurrences with timestamp, last known GPS,
 * and a freeze-frame snapshot. File-based (single line per event) — Room
 * migration will come later but this gets the intermittent-fault diary live now.
 *
 * Output path: {externalFilesDir}/obd-events.log
 *
 * Line format:
 *   ISO8601  VIN  MODE  CODE  lat,lon  freezeKey1=v1; key2=v2; ...
 */
public final class EventLogger {

    private static final String TAG = "EventLogger";
    public static final String EVENTS_FILENAME = "obd-events.log";
    private static final EventLogger INSTANCE = new EventLogger();
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);

    private File eventsFile;
    private Context appContext;

    private EventLogger() {}
    public static EventLogger get() { return INSTANCE; }

    public synchronized void init(Context ctx) {
        if (eventsFile != null) return;
        this.appContext = ctx.getApplicationContext();
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        this.eventsFile = new File(dir, EVENTS_FILENAME);
    }

    public File getFile() { return eventsFile; }

    /**
     * Record one DTC event. Safe to call from a background thread. Silently no-ops
     * if {@link #init(Context)} hasn't been called.
     *
     * @param mode "STORED" | "PENDING" | "PERMANENT" | "DSC" | "EGS"
     * @param vin optional — pass null if unknown
     * @param codes list of DTC strings, written one event per code
     * @param freeze optional freeze frame snapshot
     */
    public synchronized void recordDtcs(String mode, String vin, List<String> codes,
                                        java.util.LinkedHashMap<String, String> freeze) {
        if (eventsFile == null || codes == null || codes.isEmpty()) return;
        String gps = lastGps();
        String freezeStr = formatFreeze(freeze);
        String when = TS.format(new Date());
        try (BufferedWriter w = new BufferedWriter(new FileWriter(eventsFile, true))) {
            for (String c : codes) {
                w.write(when);
                w.write('\t'); w.write(vin == null ? "-" : vin);
                w.write('\t'); w.write(mode);
                w.write('\t'); w.write(c);
                w.write('\t'); w.write(gps);
                w.write('\t'); w.write(freezeStr);
                w.newLine();
            }
        } catch (IOException e) {
            Log.w(TAG, "event write failed: " + e.getMessage());
        }
    }

    private String lastGps() {
        if (appContext == null) return "-";
        boolean fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return "-";
        try {
            LocationManager lm = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return "-";
            Location loc = null;
            try { loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (SecurityException ignored) {}
            if (loc == null) {
                try { loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (SecurityException ignored) {}
            }
            if (loc == null) return "-";
            return String.format(Locale.US, "%.5f,%.5f", loc.getLatitude(), loc.getLongitude());
        } catch (Exception e) {
            return "-";
        }
    }

    private String formatFreeze(java.util.LinkedHashMap<String, String> freeze) {
        if (freeze == null || freeze.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : freeze.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Read all recorded events back as text, for share / debug. Empty string if none. */
    public synchronized String renderAll() {
        if (eventsFile == null || !eventsFile.exists()) return "";
        try (BufferedReader r = new BufferedReader(new FileReader(eventsFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) { sb.append(line).append('\n'); }
            return sb.toString();
        } catch (IOException e) {
            return "(read failed: " + e.getMessage() + ")";
        }
    }
}
