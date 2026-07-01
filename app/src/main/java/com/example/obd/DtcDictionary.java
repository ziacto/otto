package com.example.obd;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Lazy-loaded singleton wrapper around assets/dtc_dictionary.json.
 *
 * Loads on first access on a background-friendly call (the caller passes Context).
 * Once parsed, lookups are O(1) JSON object reads.
 */
public final class DtcDictionary {

    private static final String TAG = "DtcDictionary";
    private static final DtcDictionary INSTANCE = new DtcDictionary();

    private volatile JSONObject codes;

    private DtcDictionary() {}

    public static DtcDictionary get() { return INSTANCE; }

    public synchronized void loadIfNeeded(Context ctx) {
        if (codes != null) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("dtc_dictionary.json"), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());
            codes = root.optJSONObject("codes");
        } catch (Exception e) {
            Log.w(TAG, "Failed to load dictionary: " + e);
            codes = new JSONObject();
        }
    }

    public static final class Entry {
        public final String title;
        public final String severity; // low | medium | high | critical
        public final String cause;
        public final int diy;         // 1 easy .. 5 specialist
        Entry(String t, String s, String c, int d) {
            this.title = t; this.severity = s; this.cause = c; this.diy = d;
        }
    }

    /** Null when the code is unknown. */
    public Entry lookup(String code) {
        JSONObject c = codes;
        if (c == null) return null;
        JSONObject o = c.optJSONObject(code);
        if (o == null) return null;
        return new Entry(
                o.optString("title", code),
                o.optString("severity", "medium"),
                o.optString("cause", ""),
                o.optInt("diy", 3));
    }

    /** Severity → color hex for UI. */
    public static String colorFor(String severity) {
        if (severity == null) return "#E0E0E0";
        switch (severity) {
            case "critical": return "#D32F2F";
            case "high":     return "#F44336";
            case "medium":   return "#FFB300";
            case "low":      return "#4CAF50";
            default:         return "#E0E0E0";
        }
    }
}
