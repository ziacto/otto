package com.example.obd;

import android.content.Context;

import com.example.obd.db.AppDatabase;
import com.example.obd.db.PidSample;
import com.example.obd.db.Trip;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Renders the current Fault Codes scan + the rolling DTC event log as a single
 * HTML file the user can hand to a mechanic. Pure local generation — no network.
 *
 * Also produces CSV exports of the local DB (trips + last-30-days PidSamples)
 * for users who want to spreadsheet their data.
 */
public final class ShareReport {

    private ShareReport() {}

    /** Trips + last 30 days of key sensor samples as a single CSV blob. */
    public static String buildCsv(Context ctx) {
        AppDatabase db = AppDatabase.get(ctx);
        StringBuilder sb = new StringBuilder(64 * 1024);

        sb.append("# BMW OBD export — ").append(new Date()).append('\n');

        sb.append("\n## trips\n");
        sb.append("id,vin,startedAt,endedAt,durationSec,peakSpeed_kmh,peakRpm,peakCoolant_c,distance_km,notes\n");
        for (Trip t : db.trips().recent(500)) {
            sb.append(t.id).append(',')
              .append(csv(t.vin)).append(',')
              .append(t.startedAt).append(',')
              .append(t.endedAt == null ? "" : t.endedAt).append(',')
              .append(t.durationSec == null ? "" : t.durationSec).append(',')
              .append(t.peakSpeed == null ? "" : t.peakSpeed).append(',')
              .append(t.peakRpm == null ? "" : t.peakRpm).append(',')
              .append(t.peakCoolant == null ? "" : t.peakCoolant).append(',')
              .append(t.distanceKm == null ? "" : t.distanceKm).append(',')
              .append(csv(t.notes)).append('\n');
        }

        long since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
        sb.append("\n## pid_samples (last 30 days, key sensors)\n");
        sb.append("name,ts_epoch_ms,value\n");
        String[] keys = {"Coolant Temp", "Oil Temp", "Battery Voltage",
                         "RPM", "Vehicle Speed", "Mass Air Flow"};
        for (String key : keys) {
            List<PidSample> rows = db.samples().samplesSince(key, since);
            for (PidSample s : rows) {
                sb.append(csv(s.name)).append(',')
                  .append(s.ts).append(',')
                  .append(s.value).append('\n');
            }
        }
        return sb.toString();
    }

    /** Escape one CSV field. */
    private static String csv(String v) {
        if (v == null) return "";
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0) return v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    public static String buildHtml(Context ctx, String scanText) {
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
          .append("<title>BMW OBD report — ").append(when).append("</title>")
          .append("<style>")
          .append("body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;")
          .append("background:#0E0E1A;color:#E0E0E0;padding:24px;line-height:1.5;}")
          .append("h1{color:#03DAC5;font-size:22px;margin:0 0 4px;}")
          .append("h2{color:#FFB300;font-size:16px;margin-top:24px;border-bottom:1px solid #444;padding-bottom:4px;}")
          .append(".meta{color:#888;font-size:12px;margin-bottom:24px;}")
          .append("pre{background:#1A1A2E;padding:16px;border-radius:8px;overflow-x:auto;")
          .append("font-family:Menlo,Consolas,monospace;font-size:12px;color:#C8E6C9;}")
          .append(".footer{margin-top:32px;color:#666;font-size:11px;}")
          .append("</style></head><body>")
          .append("<h1>BMW OBD-II Diagnostic Report</h1>")
          .append("<div class=\"meta\">Generated ").append(escape(when))
          .append(" — from a vLinker BM+ ELM327 adapter via the OBD-II port.</div>");

        sb.append("<h2>Latest scan</h2><pre>").append(escape(scanText)).append("</pre>");

        String events = EventLogger.get().renderAll();
        if (!events.isEmpty()) {
            sb.append("<h2>DTC event history</h2><pre>").append(escape(events)).append("</pre>");
        }

        sb.append("<div class=\"footer\">")
          .append("OBD-II generic + BMW-specific UDS reads (DSC/EGS). ")
          .append("KOMBI / FRM / CAS not reachable from OBD port on E65 — those need K+DCAN with INPA/ISTA.")
          .append("</div></body></html>");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
