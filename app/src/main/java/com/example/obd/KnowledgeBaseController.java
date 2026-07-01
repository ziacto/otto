package com.example.obd;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Knowledge Base screen for E65 730li reference content.
 *
 * Section model: each section is a tab in the top bar. Tap a tab → its loader rebuilds
 * the scrollable content view below. Data is loaded once from assets/knowledge/*.json
 * and cached for the lifetime of the screen.
 *
 * Sections:
 *   • Faults — top E65 N52B30 fault library (symptoms / cause / fix / parts cost)
 *   • Fuses — fuse box maps for glovebox, boot, engine bay
 *   • Maintenance — N52B30 GCC service schedule
 *   • Procedures — step-by-step diagnostic walkthroughs
 *   • Manuals — PDF files dropped into assets/manuals/ by the owner
 *   • Diagrams — image files dropped into assets/diagrams/ by the owner
 */
public class KnowledgeBaseController {

    private static final String[] SECTIONS = {
            "Faults", "Fuses", "Maintenance", "Procedures", "Systems", "Manuals", "Diagrams"
    };

    private View root;
    private LinearLayout tabRow;
    private LinearLayout content;
    private Context ctx;
    private int currentTab = 0;

    private JSONObject faultsJson;
    private JSONObject fusesJson;
    private JSONObject maintenanceJson;
    private JSONObject proceduresJson;
    private JSONObject systemsJson;

    public void attach(View view) {
        this.root = view;
        this.ctx = view.getContext();
        tabRow = view.findViewById(R.id.kbTabRow);
        content = view.findViewById(R.id.kbContent);

        DtcDictionary.get().loadIfNeeded(ctx);
        loadAllJson();
        buildTabs();
        showSection(0);
    }

    public void detach() {
        root = null;
        tabRow = null;
        content = null;
        ctx = null;
    }

    private void loadAllJson() {
        faultsJson = readAssetJson("knowledge/faults.json");
        fusesJson = readAssetJson("knowledge/fuses.json");
        maintenanceJson = readAssetJson("knowledge/maintenance.json");
        proceduresJson = readAssetJson("knowledge/procedures.json");
        systemsJson = readAssetJson("knowledge/systems.json");
    }

    private void buildTabs() {
        tabRow.removeAllViews();
        for (int i = 0; i < SECTIONS.length; i++) {
            final int idx = i;
            Button b = new Button(ctx);
            b.setText(SECTIONS[i]);
            b.setAllCaps(false);
            b.setTextSize(12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> showSection(idx));
            tabRow.addView(b);
        }
    }

    private void highlightTabs() {
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            Button b = (Button) tabRow.getChildAt(i);
            boolean active = (i == currentTab);
            b.setBackgroundTintList(ColorStateList.valueOf(
                    Color.parseColor(active ? "#FFB300" : "#03DAC5")));
            b.setTextColor(Color.parseColor("#000000"));
        }
    }

    private void showSection(int index) {
        currentTab = index;
        highlightTabs();
        content.removeAllViews();
        switch (index) {
            case 0: buildFaults(); break;
            case 1: buildFuses(); break;
            case 2: buildMaintenance(); break;
            case 3: buildProcedures(); break;
            case 4: buildSystems(); break;
            case 5: buildManuals(); break;
            case 6: buildDiagrams(); break;
        }
    }

    // ---- Section builders ----

    private void buildFaults() {
        if (faultsJson == null) { addEmpty("Faults data not available"); return; }
        JSONArray arr = faultsJson.optJSONArray("faults");
        if (arr == null) { addEmpty("No faults found"); return; }

        addHeading("BMW E65 730li — Common Faults",
                "Top issues seen on N52B30 cars. Tap a card for full details.");

        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.optJSONObject(i);
            if (f == null) continue;
            addFaultCard(f);
        }
    }

    private void addFaultCard(JSONObject f) {
        String title = f.optString("title", "Fault");
        String category = f.optString("category", "");
        String symptoms = f.optString("symptoms", "");
        String cause = f.optString("cause", "");
        String fix = f.optString("fix", "");
        int partsEur = f.optInt("parts_eur", 0);
        double labourHours = f.optDouble("labour_hours", 0);
        int diy = f.optInt("diy", 3);

        LinearLayout card = card();
        card.addView(boldText(title));
        if (!category.isEmpty()) card.addView(smallText("Category: " + category));
        card.addView(spacing(8));
        card.addView(sectionText("SYMPTOMS", symptoms));
        card.addView(sectionText("CAUSE", cause));
        card.addView(sectionText("FIX", fix));
        String meta = "Parts ~€" + partsEur
                + "   |   Labour ~" + labourHours + "h"
                + "   |   DIY " + diyDots(diy);
        card.addView(smallText(meta));

        JSONArray related = f.optJSONArray("related_dtcs");
        if (related != null && related.length() > 0) {
            StringBuilder sb = new StringBuilder("Related DTCs: ");
            for (int i = 0; i < related.length(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(related.optString(i));
            }
            card.addView(smallText(sb.toString()));
        }
        content.addView(card);
    }

    private void buildFuses() {
        if (fusesJson == null) { addEmpty("Fuse data not available"); return; }
        addHeading("E65 Fuse Maps",
                "Glovebox, boot, and engine bay. Always cross-check with the printed lid diagram.");

        JSONArray boxes = fusesJson.optJSONArray("boxes");
        if (boxes != null) {
            for (int i = 0; i < boxes.length(); i++) {
                JSONObject box = boxes.optJSONObject(i);
                if (box != null) addFuseBoxCard(box);
            }
        }

        // Quick lookup by symptom
        JSONObject byCircuit = fusesJson.optJSONObject("common_faults_by_circuit");
        if (byCircuit != null && byCircuit.length() > 0) {
            content.addView(spacing(12));
            content.addView(boldText("QUICK LOOKUP BY SYMPTOM"));
            content.addView(spacing(6));
            LinearLayout card = card();
            java.util.Iterator<String> keys = byCircuit.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = byCircuit.optString(key, "");
                card.addView(smallText("• " + humanize(key) + " — " + value));
                card.addView(spacing(4));
            }
            content.addView(card);
        }
    }

    private void addFuseBoxCard(JSONObject box) {
        String title = box.optString("title", "Fuse box");
        String location = box.optString("location", "");
        String access = box.optString("access", "");

        LinearLayout card = card();
        card.addView(boldText(title));
        if (!location.isEmpty()) card.addView(smallText("Location: " + location));
        if (!access.isEmpty()) card.addView(smallText("Access: " + access));
        card.addView(spacing(8));

        JSONArray fuses = box.optJSONArray("fuses");
        if (fuses != null) {
            for (int i = 0; i < fuses.length(); i++) {
                JSONObject f = fuses.optJSONObject(i);
                if (f == null) continue;
                String no = f.optString("no", "");
                int amp = f.optInt("amp", 0);
                String circuit = f.optString("circuit", "");
                TextView row = new TextView(ctx);
                row.setText(String.format("%s  %dA  %s", no, amp, circuit));
                row.setTextColor(Color.parseColor("#E0E0E0"));
                row.setTextSize(11);
                row.setTypeface(Typeface.MONOSPACE);
                row.setPadding(0, dp(2), 0, dp(2));
                card.addView(row);
            }
        }
        content.addView(card);
    }

    private void buildMaintenance() {
        if (maintenanceJson == null) { addEmpty("Maintenance data not available"); return; }
        addHeading("N52B30 GCC Service Schedule",
                "Intervals adjusted for Gulf heat. Items marked [GCC] reflect shorter spec vs Europe.");

        JSONArray arr = maintenanceJson.optJSONArray("schedule");
        if (arr == null) { addEmpty("No items"); return; }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            addMaintenanceCard(item);
        }
    }

    private void addMaintenanceCard(JSONObject m) {
        String name = m.optString("item", "Service item");
        int intKm = m.optInt("interval_km", 0);
        int intMo = m.optInt("interval_months", 0);
        int gccKm = m.optInt("gcc_interval_km", 0);
        String parts = m.optString("parts", "");
        int partsEur = m.optInt("parts_eur", 0);
        double labour = m.optDouble("labour_hours", 0);
        int diy = m.optInt("diy", 3);
        String notes = m.optString("notes", "");

        LinearLayout card = card();
        card.addView(boldText(name));

        StringBuilder interval = new StringBuilder("Interval: ");
        if (intKm > 0) interval.append(intKm).append(" km");
        if (intMo > 0) {
            if (intKm > 0) interval.append(" / ");
            interval.append(intMo).append(" months");
        }
        if (gccKm > 0 && gccKm != intKm) {
            interval.append("   |   [GCC] ").append(gccKm).append(" km");
        }
        card.addView(smallText(interval.toString()));

        if (!parts.isEmpty()) card.addView(smallText("Parts: " + parts));
        card.addView(smallText("Cost ~€" + partsEur + "   |   ~" + labour + "h labour   |   DIY " + diyDots(diy)));
        if (!notes.isEmpty()) {
            card.addView(spacing(6));
            card.addView(smallText(notes));
        }
        content.addView(card);
    }

    private void buildProcedures() {
        if (proceduresJson == null) { addEmpty("Procedures data not available"); return; }
        addHeading("Diagnostic Procedures",
                "Step-by-step walkthroughs for common problems.");

        JSONArray arr = proceduresJson.optJSONArray("procedures");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            addProcedureCard(p);
        }
    }

    private void addProcedureCard(JSONObject p) {
        String title = p.optString("title", "Procedure");
        String category = p.optString("category", "");

        LinearLayout card = card();
        card.addView(boldText(title));
        if (!category.isEmpty()) card.addView(smallText("Category: " + category));
        card.addView(spacing(8));

        JSONArray steps = p.optJSONArray("steps");
        if (steps != null) {
            for (int i = 0; i < steps.length(); i++) {
                JSONObject s = steps.optJSONObject(i);
                if (s == null) continue;
                int n = s.optInt("step", i + 1);
                String action = s.optString("action", "");
                String ifFail = s.optString("if_fail", "");
                TextView stepView = new TextView(ctx);
                stepView.setText(String.format("Step %d: %s", n, action));
                stepView.setTextColor(Color.parseColor("#E0E0E0"));
                stepView.setTextSize(12);
                stepView.setPadding(0, dp(4), 0, dp(2));
                card.addView(stepView);
                if (!ifFail.isEmpty()) {
                    TextView ifv = new TextView(ctx);
                    ifv.setText("  ↳ If fails: " + ifFail);
                    ifv.setTextColor(Color.parseColor("#B0B0B0"));
                    ifv.setTextSize(11);
                    ifv.setPadding(dp(12), 0, 0, dp(4));
                    card.addView(ifv);
                }
            }
        }
        content.addView(card);
    }

    private void buildSystems() {
        if (systemsJson == null) { addEmpty("Systems data not available"); return; }
        String src = systemsJson.optString("source", "");
        addHeading("E65 Systems Reference",
                "Architectural reference extracted from BMW Service Training manuals. Includes engine codes, bus topology, module glossary, audio system, GCC-spec differences.");
        if (!src.isEmpty()) {
            LinearLayout srcCard = card();
            srcCard.addView(boldText("Source"));
            srcCard.addView(smallText(src));
            content.addView(srcCard);
        }
        JSONArray topics = systemsJson.optJSONArray("topics");
        if (topics == null) return;
        for (int i = 0; i < topics.length(); i++) {
            JSONObject t = topics.optJSONObject(i);
            if (t == null) continue;
            addSystemsTopic(t);
        }
    }

    private void addSystemsTopic(JSONObject t) {
        String title = t.optString("title", "Topic");
        String summary = t.optString("summary", "");
        JSONArray details = t.optJSONArray("details");

        LinearLayout card = card();
        card.addView(boldText(title));
        if (!summary.isEmpty()) {
            card.addView(spacing(4));
            card.addView(smallText(summary));
        }
        if (details != null && details.length() > 0) {
            card.addView(spacing(6));
            for (int i = 0; i < details.length(); i++) {
                String line = details.optString(i, "");
                if (line.isEmpty()) continue;
                TextView b = new TextView(ctx);
                b.setText("• " + line);
                b.setTextSize(11);
                b.setTextColor(Color.parseColor("#C8C8C8"));
                b.setPadding(0, dp(3), 0, dp(3));
                b.setLineSpacing(dp(2), 1.0f);
                card.addView(b);
            }
        }
        content.addView(card);
    }

    private void buildManuals() {
        addHeading("Manuals (PDF)",
                "Drop your own PDF files into app/src/main/assets/manuals/ and they appear here.");

        List<String> files = listAssetFiles("manuals", ".pdf");
        if (files.isEmpty()) {
            LinearLayout card = card();
            card.addView(boldText("No PDFs found"));
            card.addView(spacing(6));
            card.addView(smallText("How to add manuals:"));
            card.addView(smallText("1. Place .pdf files in app/src/main/assets/manuals/"));
            card.addView(smallText("2. Rebuild the app (./gradlew assembleDebug)"));
            card.addView(smallText("3. They will appear here as cards. Tap to open."));
            card.addView(spacing(8));
            card.addView(smallText("Suggested files for your E65 730li:"));
            card.addView(smallText("• Bentley E65/E66 Service Manual"));
            card.addView(smallText("• BMW WDS Wiring Diagrams"));
            card.addView(smallText("• BMW TIS Repair Procedures"));
            card.addView(smallText("• Owner's manual GCC spec"));
            content.addView(card);
            return;
        }
        for (String name : files) {
            LinearLayout card = card();
            card.setClickable(true);
            card.setFocusable(true);
            card.addView(boldText("📄  " + prettyName(name)));
            card.addView(smallText("Tap to open"));
            card.setOnClickListener(v -> openPdf("manuals/" + name));
            content.addView(card);
        }
    }

    private void buildDiagrams() {
        addHeading("Diagrams",
                "Drop your own diagram images into app/src/main/assets/diagrams/ and they appear here.");

        List<String> files = listAssetFiles("diagrams", ".png", ".jpg", ".jpeg", ".webp");
        if (files.isEmpty()) {
            LinearLayout card = card();
            card.addView(boldText("No diagrams found"));
            card.addView(spacing(6));
            card.addView(smallText("How to add diagrams:"));
            card.addView(smallText("1. Place .png / .jpg files in app/src/main/assets/diagrams/"));
            card.addView(smallText("2. Rebuild the app (./gradlew assembleDebug)"));
            card.addView(smallText("3. Tap any diagram here to open with zoom/pan."));
            card.addView(spacing(8));
            card.addView(smallText("Suggested diagrams to add:"));
            card.addView(smallText("• Engine bay layout for N52B30"));
            card.addView(smallText("• Fuse box overlay photos with labels"));
            card.addView(smallText("• Wiring diagrams (sections of WDS)"));
            card.addView(smallText("• Vacuum hose routing"));
            content.addView(card);
            return;
        }
        for (String name : files) {
            LinearLayout card = card();
            card.setClickable(true);
            card.setFocusable(true);
            card.addView(boldText("🖼  " + prettyName(name)));
            card.addView(smallText("Tap to view (zoom/pan)"));
            card.setOnClickListener(v -> openImage("diagrams/" + name));
            content.addView(card);
        }
    }

    private void openPdf(String assetPath) {
        Intent i = new Intent(ctx, PdfViewerActivity.class);
        i.putExtra(PdfViewerActivity.EXTRA_ASSET, assetPath);
        ctx.startActivity(i);
    }

    private void openImage(String assetPath) {
        Intent i = new Intent(ctx, ImageViewerActivity.class);
        i.putExtra(ImageViewerActivity.EXTRA_ASSET, assetPath);
        ctx.startActivity(i);
    }

    // ---- UI helpers ----

    private void addHeading(String title, String subtitle) {
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(18);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(Color.parseColor("#FFFFFF"));
        t.setPadding(0, 0, 0, dp(4));
        content.addView(t);
        TextView s = new TextView(ctx);
        s.setText(subtitle);
        s.setTextSize(12);
        s.setTextColor(Color.parseColor("#B0B0B0"));
        s.setPadding(0, 0, 0, dp(10));
        content.addView(s);
    }

    private void addEmpty(String msg) {
        TextView t = new TextView(ctx);
        t.setText(msg);
        t.setTextColor(Color.parseColor("#B0B0B0"));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(20), dp(40), dp(20), dp(40));
        content.addView(t);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card_bg);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView boldText(String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(14);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(Color.parseColor("#FFFFFF"));
        return t;
    }

    private TextView smallText(String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(Color.parseColor("#C8C8C8"));
        t.setLineSpacing(dp(2), 1.0f);
        return t;
    }

    private TextView sectionText(String label, String value) {
        TextView t = new TextView(ctx);
        t.setText(label + "\n" + value + "\n");
        t.setTextSize(11);
        t.setTextColor(Color.parseColor("#C8C8C8"));
        t.setLineSpacing(dp(2), 1.0f);
        return t;
    }

    private View spacing(int dpHeight) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(dpHeight)));
        return v;
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    private String diyDots(int diy) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i < diy ? "●" : "○");
        return sb.toString();
    }

    private String humanize(String key) {
        return key.replace('_', ' ');
    }

    private String prettyName(String filename) {
        int dot = filename.lastIndexOf('.');
        String n = (dot > 0) ? filename.substring(0, dot) : filename;
        return n.replace('_', ' ').replace('-', ' ');
    }

    private List<String> listAssetFiles(String dir, String... exts) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try {
            String[] all = ctx.getAssets().list(dir);
            if (all == null) return out;
            for (String f : all) {
                String lc = f.toLowerCase();
                for (String e : exts) {
                    if (lc.endsWith(e)) { out.add(f); break; }
                }
            }
        } catch (IOException ignored) {}
        java.util.Collections.sort(out);
        return out;
    }

    private JSONObject readAssetJson(String path) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open(path), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
