package com.example.obd;

import android.util.Base64;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Gemini 2.5 Flash vision call. Free tier covers ~15 RPM and ~1500 RPD which
 * is plenty for a manual photo-inspect feature. Uses the simple
 * generateContent REST endpoint — no SDK dependency.
 */
public final class GeminiVisionProvider implements AiVisionProvider {

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private static final String PROMPT_TEMPLATE =
            "You are a senior automotive technician in DUBAI, UAE, writing a complete "
            + "repair handout for a car owner with no mechanical experience. The car "
            + "can be any make/model. Use Dubai market pricing (AED), Dubai-area "
            + "workshop rates (indie ~120-180 AED/hr, dealer ~350-500 AED/hr), and "
            + "reference Dubizzle/Amazon.ae for parts.\n\n"
            + "Return STRICT JSON (no markdown fences, no prose outside JSON) with "
            + "this exact schema:\n"
            + "{\n"
            + "  \"identified_part\": string,\n"
            + "  \"confidence\": \"high\"|\"medium\"|\"low\",\n"
            + "  \"severity\": \"Drive immediately\"|\"Drive carefully\"|\"Stop driving\"|\"Cosmetic only\",\n"
            + "  \"difficulty\": \"DIY easy\"|\"DIY moderate\"|\"DIY hard\"|\"Workshop only\",\n"
            + "  \"time_to_fix_hours_low\": number,\n"
            + "  \"time_to_fix_hours_high\": number,\n"
            + "  \"summary\": string (3-4 sentences — what's wrong, why, consequences),\n"
            + "  \"likely_cause\": string (1-2 sentences),\n"
            + "  \"parts\": [\n"
            + "    {\n"
            + "      \"name\": string,\n"
            + "      \"oem_number\": string (manufacturer part number if known),\n"
            + "      \"price_aed_low\": int,\n"
            + "      \"price_aed_high\": int,\n"
            + "      \"image_search_query\": string,\n"
            + "      \"buy_search_query\": string\n"
            + "    }\n"
            + "  ],\n"
            + "  \"tools_needed\": [string] (e.g. \"10mm socket\", \"torque wrench 0-30Nm\", \"plastic trim pry\"),\n"
            + "  \"repair_steps\": [string] (5-10 numbered, plain-language steps a beginner could follow),\n"
            + "  \"safety_warnings\": [string] (e.g. \"Disconnect battery before working on coils\", \"Wait 1h for exhaust to cool\"),\n"
            + "  \"diagram_search_query\": string (best Google Images search for the exploded view / wiring diagram of this part on THIS car),\n"
            + "  \"manual_search_query\": string (best Google search for the official service manual section, e.g. \"BMW E65 N52 valve cover removal procedure pdf\"),\n"
            + "  \"related_inspections\": [string] (3-5 nearby parts worth checking while the cover is open — \"While you're there...\"),\n"
            + "  \"what_if_delayed\": string (1-2 sentences — what gets worse if user delays the fix 3-6 months),\n"
            + "  \"labor_hours_low\": number,\n"
            + "  \"labor_hours_high\": number,\n"
            + "  \"total_aed_indie_low\": int,\n"
            + "  \"total_aed_indie_high\": int,\n"
            + "  \"total_aed_dealer_low\": int,\n"
            + "  \"total_aed_dealer_high\": int,\n"
            + "  \"resale_impact_aed_low\": int,\n"
            + "  \"resale_impact_aed_high\": int,\n"
            + "  \"verification_steps\": [string],\n"
            + "  \"workshop_search_query\": string\n"
            + "}\n\n"
            + "Rules:\n"
            + "- If photo is unclear or wrong car, set confidence=\"low\" and explain in summary.\n"
            + "- 1-4 parts; most likely first.\n"
            + "- repair_steps written in second person (\"You'll need to...\"), each "
            + "step actionable and short.\n"
            + "- safety_warnings non-negotiable safety items — always include battery / "
            + "fluid / heat / lifting precautions where relevant.\n"
            + "- diagram_search_query optimised for image search on the SPECIFIC car "
            + "model the user named (e.g. \"BMW E65 N52 valve cover exploded view\").\n"
            + "- workshop_search_query targets Dubai areas (Al Quoz, Ras Al Khor, "
            + "Mussafah, Sharjah Industrial) and the car make.\n\n"
            + "User context: ";

    private final String apiKey;

    public GeminiVisionProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String getDisplayName() { return "Gemini 2.5 Flash"; }

    /**
     * Follow-up chat with Google Search grounding enabled. Gemini uses the
     * search tool automatically whenever the answer needs fresh info (current
     * part prices in AED, recall notices, torque specs from TSB PDFs, etc.).
     * Groundig chunks come back in the response payload — we surface the URLs
     * so the user can verify anything they're about to spend money on.
     */
    @Override
    public ChatReply chatFollowup(String estimateJson,
                                  byte[] imageJpeg,
                                  List<ChatTurn> history,
                                  String question) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("API key not set");
        }
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty question");
        }

        JSONObject body = buildChatRequest(estimateJson, imageJpeg,
                history == null ? Collections.<ChatTurn>emptyList() : history,
                question);

        URL url = new URL(ENDPOINT + "?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String raw = readAll(is);
            if (code < 200 || code >= 300) {
                throw new IOException("Gemini HTTP " + code + ": " + extractError(raw));
            }
            return parseChatReply(raw);
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public String analyzeDamage(byte[] jpegBytes, String userContext) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("API key not set");
        }
        if (jpegBytes == null || jpegBytes.length == 0) {
            throw new IllegalArgumentException("Image bytes empty");
        }

        String base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
        String contextLine = (userContext == null || userContext.isEmpty())
                ? "(none provided)" : userContext;
        String prompt = PROMPT_TEMPLATE + contextLine;

        JSONObject body = buildRequest(prompt, base64);

        URL url = new URL(ENDPOINT + "?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String raw = readAll(is);

            if (code < 200 || code >= 300) {
                throw new IOException("Gemini HTTP " + code + ": " + extractError(raw));
            }
            return parseText(raw);
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject buildRequest(String prompt, String base64Image) throws Exception {
        JSONObject body = new JSONObject();

        JSONArray contents = new JSONArray();
        JSONObject content = new JSONObject();
        JSONArray parts = new JSONArray();

        JSONObject textPart = new JSONObject();
        textPart.put("text", prompt);
        parts.put(textPart);

        JSONObject imagePart = new JSONObject();
        JSONObject inlineData = new JSONObject();
        inlineData.put("mime_type", "image/jpeg");
        inlineData.put("data", base64Image);
        imagePart.put("inline_data", inlineData);
        parts.put(imagePart);

        content.put("parts", parts);
        contents.put(content);
        body.put("contents", contents);

        JSONObject generationConfig = new JSONObject();
        generationConfig.put("temperature", 0.2);
        // Gemini 2.5 Flash has thinking enabled by default and will burn most of
        // the output budget on internal reasoning. For a structured-template task
        // like this, we don't need it — disable so all tokens go to the answer.
        JSONObject thinkingConfig = new JSONObject();
        thinkingConfig.put("thinkingBudget", 0);
        generationConfig.put("thinkingConfig", thinkingConfig);
        generationConfig.put("maxOutputTokens", 4000);
        // Force JSON output so we can render structured cards (parts list, buttons).
        generationConfig.put("responseMimeType", "application/json");
        body.put("generationConfig", generationConfig);

        return body;
    }

    private static String parseText(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IOException("No candidates in Gemini response");
        }
        JSONObject first = candidates.getJSONObject(0);
        JSONObject content = first.optJSONObject("content");
        if (content == null) throw new IOException("Empty content in Gemini response");
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null || parts.length() == 0) {
            throw new IOException("Empty parts in Gemini response");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            String t = part.optString("text", null);
            if (t != null) sb.append(t);
        }
        String out = sb.toString().trim();
        if (out.isEmpty()) throw new IOException("Gemini returned no text");
        return out;
    }

    private static final String CHAT_SYSTEM_PROMPT =
            "You are the same senior Dubai automotive technician who just wrote the "
            + "repair estimate below. The customer is now asking follow-up questions. "
            + "Answer in plain English, 2-6 sentences, second person, practical. "
            + "Use AED for money, name specific Dubai suppliers/workshops when useful "
            + "(Al Quoz, Ras Al Khor, Deira, Sharjah Industrial, Mussafah). If the "
            + "answer depends on current pricing, availability, TSBs, recalls or "
            + "part numbers, USE web search — never guess a stale price or OEM code. "
            + "Do not repeat the whole estimate — answer only what was asked.";

    /**
     * Build a Gemini {@code generateContent} request that:
     *   1. Seeds the model with the original photo + a "system"-role user turn
     *      containing the JSON estimate + the customer instructions.
     *   2. Replays prior chat turns in Gemini's user/model interleaved format.
     *   3. Appends the new user question.
     *   4. Attaches the {@code google_search} tool so the model can fetch live
     *      info (part prices, recall bulletins) when the answer needs it.
     */
    private static JSONObject buildChatRequest(String estimateJson,
                                               byte[] imageJpeg,
                                               List<ChatTurn> history,
                                               String question) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();

        // Turn 1: user role packages the estimate + the photo + the persona brief.
        // Gemini requires "contents" to alternate user/model, and does not have a
        // separate system role in v1beta — folding this into the first user turn
        // is the documented pattern.
        JSONObject seed = new JSONObject();
        seed.put("role", "user");
        JSONArray seedParts = new JSONArray();
        JSONObject seedText = new JSONObject();
        seedText.put("text", CHAT_SYSTEM_PROMPT + "\n\nESTIMATE:\n"
                + (estimateJson == null ? "(no prior estimate)" : estimateJson));
        seedParts.put(seedText);
        if (imageJpeg != null && imageJpeg.length > 0) {
            JSONObject imgPart = new JSONObject();
            JSONObject inline = new JSONObject();
            inline.put("mime_type", "image/jpeg");
            inline.put("data", Base64.encodeToString(imageJpeg, Base64.NO_WRAP));
            imgPart.put("inline_data", inline);
            seedParts.put(imgPart);
        }
        seed.put("parts", seedParts);
        contents.put(seed);

        // Turn 2: prime the assistant so history alternation is valid.
        JSONObject prime = new JSONObject();
        prime.put("role", "model");
        JSONArray primeParts = new JSONArray();
        JSONObject primeText = new JSONObject();
        primeText.put("text", "Understood. Ask me anything about this repair.");
        primeParts.put(primeText);
        prime.put("parts", primeParts);
        contents.put(prime);

        // Replay history — role must be "user" or "model" (Gemini rejects
        // "assistant"). Our controller stores the raw strings using those tags.
        if (history != null) {
            for (ChatTurn t : history) {
                if (t == null || t.text == null || t.text.isEmpty()) continue;
                String role = "user".equals(t.role) ? "user" : "model";
                JSONObject c = new JSONObject();
                c.put("role", role);
                JSONArray ps = new JSONArray();
                JSONObject tx = new JSONObject();
                tx.put("text", t.text);
                ps.put(tx);
                c.put("parts", ps);
                contents.put(c);
            }
        }

        // Final: the user's new question.
        JSONObject qTurn = new JSONObject();
        qTurn.put("role", "user");
        JSONArray qParts = new JSONArray();
        JSONObject qText = new JSONObject();
        qText.put("text", question.trim());
        qParts.put(qText);
        qTurn.put("parts", qParts);
        contents.put(qTurn);

        body.put("contents", contents);

        // Enable Google Search grounding. On Gemini 2.5 the tool name is
        // "google_search"; older 1.5 endpoints used "google_search_retrieval".
        JSONArray tools = new JSONArray();
        JSONObject tool = new JSONObject();
        tool.put("google_search", new JSONObject());
        tools.put(tool);
        body.put("tools", tools);

        JSONObject gen = new JSONObject();
        gen.put("temperature", 0.3);
        gen.put("maxOutputTokens", 1200);
        // Keep thinking disabled — we want snappy chat replies, not a research
        // paper for each follow-up.
        JSONObject thinking = new JSONObject();
        thinking.put("thinkingBudget", 0);
        gen.put("thinkingConfig", thinking);
        // Do NOT force JSON output here — chat answers are natural language.
        body.put("generationConfig", gen);
        return body;
    }

    /** Extract answer text + any grounding source URIs from a chat response. */
    private static ChatReply parseChatReply(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IOException("No candidates in Gemini chat response");
        }
        JSONObject first = candidates.getJSONObject(0);
        JSONObject content = first.optJSONObject("content");
        if (content == null) throw new IOException("Empty content in Gemini chat response");
        JSONArray parts = content.optJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;
                String t = part.optString("text", null);
                if (t != null) sb.append(t);
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            // Safety fallback — some Gemini reasons for empty text (blocked by
            // safety filters, tool-call-only responses). Surface something so
            // the UI doesn't render a phantom empty bubble.
            text = "(Gemini returned no text — try rephrasing the question)";
        }

        // Grounding sources live in candidates[0].groundingMetadata.groundingChunks[i].web.uri.
        // Deduplicate by URI while preserving order.
        Set<String> urls = new LinkedHashSet<>();
        JSONObject gm = first.optJSONObject("groundingMetadata");
        if (gm != null) {
            JSONArray chunks = gm.optJSONArray("groundingChunks");
            if (chunks != null) {
                for (int i = 0; i < chunks.length(); i++) {
                    JSONObject ch = chunks.optJSONObject(i);
                    if (ch == null) continue;
                    JSONObject web = ch.optJSONObject("web");
                    if (web == null) continue;
                    String uri = web.optString("uri", null);
                    if (uri != null && !uri.isEmpty()) urls.add(uri);
                }
            }
        }
        return new ChatReply(text, new ArrayList<>(urls));
    }

    private static String extractError(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject err = root.optJSONObject("error");
            if (err != null) {
                String msg = err.optString("message", null);
                if (msg != null) return msg;
            }
        } catch (Exception ignored) {}
        return raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
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
