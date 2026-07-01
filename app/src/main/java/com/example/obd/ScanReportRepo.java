package com.example.obd;

import android.content.Context;

import com.example.obd.db.AppDatabase;
import com.example.obd.db.ScanReport;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persists every scan as a stand-alone HTML report viewable from the
 * "Scan Reports" drawer entry. Saves run off the UI thread.
 *
 * Each saved row holds: timestamp, VIN (if known), kind tag (DTC_FULL,
 * DTC_STORED, …, AI_ESTIMATE), display title, and the rendered HTML body
 * that the WebView in {@link ScanReportsController} loads back later.
 */
public final class ScanReportRepo {

    private ScanReportRepo() {}

    private static final SimpleDateFormat WHEN_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public static void saveDtcScan(Context ctx, String kind, String title,
                                   String vin, String scanText) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                String html = ShareReport.buildHtml(app, scanText);
                ScanReport r = new ScanReport();
                r.ts = System.currentTimeMillis();
                r.vin = vin;
                r.kind = kind;
                r.title = title;
                r.html = html;
                AppDatabase.get(app).scanReports().insert(r);
            } catch (Exception e) {
                ObdLogger.get().log(ObdLogger.Level.ERROR,
                        "ScanReport save failed: " + e.getMessage());
            }
        }, "ScanReportSave").start();
    }

    public static void saveAiEstimate(Context ctx, String vin, JSONObject json) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                String title = "AI Estimate — "
                        + json.optString("identified_part", "unknown part");
                String html = buildAiHtml(json, vin);
                ScanReport r = new ScanReport();
                r.ts = System.currentTimeMillis();
                r.vin = vin;
                r.kind = "AI_ESTIMATE";
                r.title = title;
                r.html = html;
                AppDatabase.get(app).scanReports().insert(r);
            } catch (Exception e) {
                ObdLogger.get().log(ObdLogger.Level.ERROR,
                        "ScanReport AI save failed: " + e.getMessage());
            }
        }, "ScanReportSaveAi").start();
    }

    private static String buildAiHtml(JSONObject j, String vin) {
        String when = WHEN_FMT.format(new Date());
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
          .append("<title>AI Repair Estimate</title>")
          .append("<style>")
          .append("body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;")
          .append("background:#0E0E1A;color:#E0E0E0;padding:18px;line-height:1.5;margin:0;}")
          .append("h1{color:#03DAC5;font-size:20px;margin:0 0 4px;}")
          .append("h2{color:#FFB300;font-size:14px;margin:18px 0 6px;text-transform:uppercase;letter-spacing:.05em;}")
          .append(".meta{color:#888;font-size:11px;margin-bottom:14px;}")
          .append(".sev{display:inline-block;padding:4px 10px;border-radius:12px;font-weight:700;font-size:12px;margin:6px 0;}")
          .append(".sev-red{background:#F44336;color:#fff;}")
          .append(".sev-amber{background:#FFB300;color:#000;}")
          .append(".sev-grey{background:#9E9E9E;color:#000;}")
          .append(".sev-teal{background:#03DAC5;color:#000;}")
          .append("ul{padding-left:18px;margin:4px 0 8px;}")
          .append("ol{padding-left:22px;margin:4px 0 8px;}")
          .append("li{margin:3px 0;}")
          .append(".card{background:#1A1A2E;border-radius:8px;padding:12px;margin:6px 0;}")
          .append(".part-title{font-weight:700;color:#fff;}")
          .append(".part-oem{color:#909090;font-size:11px;}")
          .append(".part-price{color:#03DAC5;font-size:13px;margin-top:4px;}")
          .append(".totals{background:#152633;border-radius:8px;padding:14px;margin-top:10px;}")
          .append(".footer{margin-top:24px;color:#666;font-size:11px;border-top:1px solid #333;padding-top:10px;}")
          .append("</style></head><body>");

        String identified = j.optString("identified_part", "Unknown part");
        String confidence = j.optString("confidence", "");
        String severity = j.optString("severity", "");
        String summary = j.optString("summary", "");

        sb.append("<h1>AI Repair Estimate</h1>");
        sb.append("<div class=\"meta\">Generated ").append(esc(when));
        if (vin != null && !vin.isEmpty()) sb.append(" · VIN ").append(esc(vin));
        sb.append("</div>");

        if (!severity.isEmpty()) {
            sb.append("<div class=\"sev ").append(sevClass(severity)).append("\">")
              .append(esc(severity)).append("</div>");
        }
        sb.append("<div style=\"font-weight:700;font-size:15px;margin-top:6px;\">")
          .append(esc(identified));
        if (!confidence.isEmpty()) sb.append(" · ").append(esc(confidence)).append(" confidence");
        sb.append("</div>");

        if (!summary.isEmpty()) {
            sb.append("<p style=\"color:#D0D0D0;\">").append(esc(summary)).append("</p>");
        }

        appendList(sb, "Tools needed", j.optJSONArray("tools_needed"));
        appendList(sb, "Safety warnings", j.optJSONArray("safety_warnings"));
        appendOrdered(sb, "How to fix it", j.optJSONArray("repair_steps"));
        String delay = j.optString("what_if_delayed", "");
        if (!delay.isEmpty()) {
            sb.append("<h2>What if I delay this?</h2><p>").append(esc(delay)).append("</p>");
        }
        appendList(sb, "While you're there, check", j.optJSONArray("related_inspections"));

        JSONArray parts = j.optJSONArray("parts");
        if (parts != null && parts.length() > 0) {
            sb.append("<h2>Parts to order</h2>");
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p == null) continue;
                sb.append("<div class=\"card\">");
                sb.append("<div class=\"part-title\">").append(esc(p.optString("name", "Part"))).append("</div>");
                String oem = p.optString("oem_number", "");
                if (!oem.isEmpty()) sb.append("<div class=\"part-oem\">OEM: ").append(esc(oem)).append("</div>");
                int lo = p.optInt("price_aed_low", -1);
                int hi = p.optInt("price_aed_high", -1);
                if (lo > 0) {
                    sb.append("<div class=\"part-price\">AED ").append(lo).append(" – ").append(hi).append("</div>");
                }
                sb.append("</div>");
            }
        }

        int indieLo = j.optInt("total_aed_indie_low", -1);
        int indieHi = j.optInt("total_aed_indie_high", -1);
        int dealerLo = j.optInt("total_aed_dealer_low", -1);
        int dealerHi = j.optInt("total_aed_dealer_high", -1);
        int resaleLo = j.optInt("resale_impact_aed_low", -1);
        int resaleHi = j.optInt("resale_impact_aed_high", -1);
        if (indieLo > 0 || dealerLo > 0) {
            sb.append("<div class=\"totals\"><h2 style=\"margin-top:0;\">Total estimate</h2>");
            if (indieLo > 0) sb.append("<div>Indie garage: AED ").append(indieLo).append(" – ").append(indieHi).append("</div>");
            if (dealerLo > 0) sb.append("<div>Dealer: AED ").append(dealerLo).append(" – ").append(dealerHi).append("</div>");
            if (resaleLo > 0) sb.append("<div style=\"margin-top:6px;color:#FF8A65;\">Resale impact if NOT fixed: AED ").append(resaleLo).append(" – ").append(resaleHi).append("</div>");
            sb.append("</div>");
        }

        sb.append("<div class=\"footer\">Generated by AI vision — verify with a qualified mechanic before ordering parts.</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, String header, JSONArray arr) {
        if (arr == null || arr.length() == 0) return;
        sb.append("<h2>").append(esc(header)).append("</h2><ul>");
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (s == null || s.isEmpty()) continue;
            sb.append("<li>").append(esc(s)).append("</li>");
        }
        sb.append("</ul>");
    }

    private static void appendOrdered(StringBuilder sb, String header, JSONArray arr) {
        if (arr == null || arr.length() == 0) return;
        sb.append("<h2>").append(esc(header)).append("</h2><ol>");
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (s == null || s.isEmpty()) continue;
            sb.append("<li>").append(esc(s)).append("</li>");
        }
        sb.append("</ol>");
    }

    private static String sevClass(String severity) {
        String s = severity == null ? "" : severity.toLowerCase(Locale.US);
        if (s.contains("immediately") || s.contains("stop")) return "sev-red";
        if (s.contains("carefully")) return "sev-amber";
        if (s.contains("cosmetic")) return "sev-grey";
        return "sev-teal";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
