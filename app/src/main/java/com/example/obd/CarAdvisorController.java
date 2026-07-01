package com.example.obd;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Companion to AiEstimatorController — text-only Gemini call that recommends
 * cars to buy in the Dubai market based on user-supplied budget + use case +
 * priorities. Output is rendered as a card list per car (score, faults, price,
 * Dubizzle button).
 */
public class CarAdvisorController {

    private static final String[] BODY_TYPES = {
            "Any", "Sedan", "SUV", "Coupe", "Hatchback",
            "Wagon", "Convertible", "Pickup", "Van"
    };
    private static final String[] USE_CASES = {
            "Daily commute (city)",
            "Family / school runs",
            "Long highway drives",
            "Weekend / fun car",
            "First car / new driver",
            "Off-road / desert",
            "Track / performance",
            "Carrying loads / pickup"
    };

    private static final String PROMPT_TEMPLATE =
            "You are a senior automotive advisor in DUBAI, UAE. The user wants help "
            + "deciding which used car to buy. Use Dubizzle as the dominant secondary "
            + "market, factor Dubai climate (heat, dust) into reliability advice, "
            + "and weigh GCC-spec availability of parts.\n\n"
            + "Return STRICT JSON (no markdown fences) with this schema:\n"
            + "{\n"
            + "  \"summary\": string (1-2 sentences framing the recommendation),\n"
            + "  \"cars\": [\n"
            + "    {\n"
            + "      \"make_model\": string (e.g. \"Toyota Camry 2017-2019\"),\n"
            + "      \"score\": int (1-10, your overall rating for THIS user's needs),\n"
            + "      \"price_aed_low\": int (used Dubizzle range),\n"
            + "      \"price_aed_high\": int,\n"
            + "      \"why_good_fit\": string (1-2 sentences specific to user's stated needs),\n"
            + "      \"pros\": [string],\n"
            + "      \"cons\": [string],\n"
            + "      \"common_faults\": [string] (KEY field — what breaks on this car in Dubai heat / at high mileage),\n"
            + "      \"reliability_rating\": string (e.g. \"Excellent — Toyota legendary, simple drivetrain\"),\n"
            + "      \"smoothness_rating\": string (e.g. \"Smooth — refined ride, quiet cabin\"),\n"
            + "      \"running_cost_aed_year_low\": int (annual fuel + service + insurance estimate),\n"
            + "      \"running_cost_aed_year_high\": int,\n"
            + "      \"dubizzle_search_query\": string\n"
            + "    }\n"
            + "  ]\n"
            + "}\n\n"
            + "Rules:\n"
            + "- 3 to 5 cars, ordered best-fit first (highest score first).\n"
            + "- Each car must FIT the user's budget (or close — within 10%). If their "
            + "budget is unrealistic, say so in summary AND still include 3-5 closest options.\n"
            + "- common_faults must be SPECIFIC (\"valve cover gasket leaks past 100k km\" "
            + "not \"various engine issues\").\n"
            + "- Lean toward cars with good GCC-spec parts availability (Toyota, Nissan, "
            + "Honda, Hyundai, Mitsubishi, Lexus, Mercedes, BMW, Ford — these have strong "
            + "dealer networks in UAE).\n\n"
            + "User request:\n";

    private View root;
    private Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public void attach(View view, Activity act) {
        this.root = view;
        this.activity = act;

        Spinner spBody = view.findViewById(R.id.spBodyType);
        Spinner spUse = view.findViewById(R.id.spUseCase);
        EditText etBudgetLow = view.findViewById(R.id.etBudgetLow);
        EditText etBudgetHigh = view.findViewById(R.id.etBudgetHigh);
        EditText etPriorities = view.findViewById(R.id.etPriorities);
        EditText etNotes = view.findViewById(R.id.etNotes);
        Button btnRecommend = view.findViewById(R.id.btnRecommend);
        TextView tvKeyHint = view.findViewById(R.id.tvKeyHint);

        spBody.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, BODY_TYPES));
        spUse.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, USE_CASES));

        // tvKeyHint is no longer used — Otto ships with the AI key bundled, no
        // user-side config needed. Keep the view-id reference so older layouts
        // still inflate cleanly, but never show it.
        if (tvKeyHint != null) tvKeyHint.setVisibility(View.GONE);

        btnRecommend.setOnClickListener(v -> {
            String key = AiSettings.getEffectiveKey(activity);
            if (key == null || key.isEmpty()) {
                Toast.makeText(activity,
                        "AI unavailable — please try again",
                        Toast.LENGTH_LONG).show();
                return;
            }
            String body = (String) spBody.getSelectedItem();
            String use = (String) spUse.getSelectedItem();
            String budgetLo = etBudgetLow.getText() == null ? "" : etBudgetLow.getText().toString().trim();
            String budgetHi = etBudgetHigh.getText() == null ? "" : etBudgetHigh.getText().toString().trim();
            String pri = etPriorities.getText() == null ? "" : etPriorities.getText().toString().trim();
            String notes = etNotes.getText() == null ? "" : etNotes.getText().toString().trim();

            StringBuilder userMsg = new StringBuilder();
            userMsg.append("Budget: AED ");
            userMsg.append(budgetLo.isEmpty() ? "(open)" : budgetLo);
            userMsg.append(" – ");
            userMsg.append(budgetHi.isEmpty() ? "(open)" : budgetHi);
            userMsg.append("\nBody type: ").append(body);
            userMsg.append("\nMain use: ").append(use);
            if (!pri.isEmpty()) userMsg.append("\nPriorities: ").append(pri);
            if (!notes.isEmpty()) userMsg.append("\nNotes: ").append(notes);

            runAdvisor(key, userMsg.toString(), btnRecommend);
        });
    }

    public void detach() {
        ui.removeCallbacksAndMessages(null);
        root = null;
        activity = null;
    }

    public boolean isAttached() { return root != null; }

    private void runAdvisor(String apiKey, String userMsg, Button btn) {
        View card = root.findViewById(R.id.advisorResultCard);
        ProgressBar progress = root.findViewById(R.id.advisorProgress);
        TextView tvRaw = root.findViewById(R.id.advisorRawResult);
        LinearLayout cars = root.findViewById(R.id.advisorCarsContainer);

        card.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        tvRaw.setVisibility(View.GONE);
        cars.removeAllViews();
        btn.setEnabled(false);

        new Thread(() -> {
            String result;
            boolean ok;
            try {
                result = callGemini(apiKey, PROMPT_TEMPLATE + userMsg);
                ok = true;
                ObdLogger.get().log(ObdLogger.Level.INFO,
                        "Car advisor OK (" + result.length() + " chars)");
            } catch (Exception e) {
                result = "Recommendation failed: " + e.getMessage();
                ok = false;
                ObdLogger.get().log(ObdLogger.Level.ERROR,
                        "Car advisor failed: " + e.getMessage());
            }
            final String finalResult = result;
            final boolean finalOk = ok;
            ui.post(() -> {
                if (root == null) return;
                progress.setVisibility(View.GONE);
                btn.setEnabled(true);
                if (!finalOk) {
                    tvRaw.setVisibility(View.VISIBLE);
                    tvRaw.setText(finalResult);
                    return;
                }
                try {
                    JSONObject json = new JSONObject(finalResult);
                    renderCars(json);
                } catch (Exception e) {
                    tvRaw.setVisibility(View.VISIBLE);
                    tvRaw.setText(finalResult);
                    ObdLogger.get().log(ObdLogger.Level.ERROR,
                            "Car advisor JSON parse failed: " + e.getMessage());
                }
            });
        }, "CarAdvisor").start();
    }

    private void renderCars(JSONObject json) {
        if (root == null || activity == null) return;
        LinearLayout cars = root.findViewById(R.id.advisorCarsContainer);
        TextView tvRaw = root.findViewById(R.id.advisorRawResult);

        String summary = json.optString("summary", "");
        if (!summary.isEmpty()) {
            TextView sumTv = new TextView(activity);
            sumTv.setText(summary);
            sumTv.setTextColor(Color.parseColor("#C0C0C0"));
            sumTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            sumTv.setPadding(0, 0, 0, dp(12));
            cars.addView(sumTv);
        }

        JSONArray arr = json.optJSONArray("cars");
        if (arr == null || arr.length() == 0) {
            tvRaw.setVisibility(View.VISIBLE);
            tvRaw.setText("No recommendations returned.");
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c != null) cars.addView(buildCarCard(c, i + 1));
        }
    }

    private View buildCarCard(JSONObject c, int rank) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#1a1a1a"));
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);

        String name = c.optString("make_model", "Car");
        int score = c.optInt("score", 0);
        int priceLo = c.optInt("price_aed_low", -1);
        int priceHi = c.optInt("price_aed_high", -1);
        String why = c.optString("why_good_fit", "");
        String reliability = c.optString("reliability_rating", "");
        String smoothness = c.optString("smoothness_rating", "");
        int runLo = c.optInt("running_cost_aed_year_low", -1);
        int runHi = c.optInt("running_cost_aed_year_high", -1);
        String dubizzleQ = c.optString("dubizzle_search_query", name);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = new TextView(activity);
        title.setText("#" + rank + " · " + name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        header.addView(title);

        if (score > 0) {
            TextView scoreTv = new TextView(activity);
            scoreTv.setText(score + "/10");
            scoreTv.setTextColor(scoreColor(score));
            scoreTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            scoreTv.setTypeface(null, Typeface.BOLD);
            header.addView(scoreTv);
        }
        card.addView(header);

        if (priceLo > 0) {
            TextView priceTv = new TextView(activity);
            priceTv.setText("Dubizzle price: AED " + priceLo + " – " + priceHi);
            priceTv.setTextColor(Color.parseColor("#03DAC5"));
            priceTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            priceTv.setPadding(0, dp(2), 0, dp(6));
            card.addView(priceTv);
        }

        if (!why.isEmpty()) {
            TextView whyTv = new TextView(activity);
            whyTv.setText("👍 " + why);
            whyTv.setTextColor(Color.parseColor("#E0E0E0"));
            whyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            whyTv.setPadding(0, dp(4), 0, dp(4));
            card.addView(whyTv);
        }

        addBulletList(card, "✅ Pros", c.optJSONArray("pros"), "#9CCC65");
        addBulletList(card, "⚠ Cons", c.optJSONArray("cons"), "#FFB300");
        addBulletList(card, "🔧 Common faults", c.optJSONArray("common_faults"), "#FF7043");

        if (!reliability.isEmpty()) {
            TextView r = new TextView(activity);
            r.setText("🛡 Reliability: " + reliability);
            r.setTextColor(Color.parseColor("#B0B0B0"));
            r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            r.setPadding(0, dp(6), 0, 0);
            card.addView(r);
        }
        if (!smoothness.isEmpty()) {
            TextView s = new TextView(activity);
            s.setText("🛣 Smoothness: " + smoothness);
            s.setTextColor(Color.parseColor("#B0B0B0"));
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            s.setPadding(0, dp(2), 0, 0);
            card.addView(s);
        }
        if (runLo > 0) {
            TextView rc = new TextView(activity);
            rc.setText("💸 Yearly running cost: AED " + runLo + " – " + runHi);
            rc.setTextColor(Color.parseColor("#B0B0B0"));
            rc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            rc.setPadding(0, dp(2), 0, dp(8));
            card.addView(rc);
        }

        Button btnDub = new Button(activity);
        btnDub.setText("🔍 See listings on Dubizzle");
        btnDub.setAllCaps(false);
        btnDub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btnDub.setOnClickListener(v ->
                openUrl("https://uae.dubizzle.com/motors/used-cars/?keywords="
                        + Uri.encode(dubizzleQ)));
        card.addView(btnDub);

        return card;
    }

    private void addBulletList(LinearLayout parent, String header,
                               JSONArray items, String headerColor) {
        if (items == null || items.length() == 0) return;
        TextView h = new TextView(activity);
        h.setText(header);
        h.setTextColor(Color.parseColor(headerColor));
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(0, dp(8), 0, dp(2));
        parent.addView(h);
        for (int i = 0; i < items.length(); i++) {
            String s = items.optString(i, null);
            if (s == null || s.isEmpty()) continue;
            TextView item = new TextView(activity);
            item.setText("  • " + s);
            item.setTextColor(Color.parseColor("#D0D0D0"));
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            parent.addView(item);
        }
    }

    private int scoreColor(int score) {
        if (score >= 8) return Color.parseColor("#03DAC5");
        if (score >= 6) return Color.parseColor("#FFB300");
        return Color.parseColor("#FF6E6E");
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density);
    }

    private void openUrl(String url) {
        if (activity == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No browser available", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Local text-only Gemini call. Could refactor to share with
     * GeminiVisionProvider once we have a 3rd use case, but for now a tiny
     * inline duplicate is cheaper than the abstraction.
     */
    private String callGemini(String apiKey, String prompt) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("API key not set");
        }
        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject content = new JSONObject();
        JSONArray parts = new JSONArray();
        JSONObject textPart = new JSONObject();
        textPart.put("text", prompt);
        parts.put(textPart);
        content.put("parts", parts);
        contents.put(content);
        body.put("contents", contents);

        JSONObject gc = new JSONObject();
        gc.put("temperature", 0.3);
        gc.put("maxOutputTokens", 3000);
        JSONObject thinking = new JSONObject();
        thinking.put("thinkingBudget", 0);
        gc.put("thinkingConfig", thinking);
        gc.put("responseMimeType", "application/json");
        body.put("generationConfig", gc);

        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String raw = readAll(is);
            if (code < 200 || code >= 300) {
                throw new IOException("Gemini HTTP " + code + ": " + raw);
            }
            JSONObject resp = new JSONObject(raw);
            JSONArray candidates = resp.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                throw new IOException("No candidates");
            }
            JSONObject first = candidates.getJSONObject(0);
            JSONObject c = first.optJSONObject("content");
            if (c == null) throw new IOException("Empty content");
            JSONArray cParts = c.optJSONArray("parts");
            if (cParts == null || cParts.length() == 0) {
                throw new IOException("Empty parts");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cParts.length(); i++) {
                sb.append(cParts.getJSONObject(i).optString("text", ""));
            }
            return sb.toString().trim();
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
