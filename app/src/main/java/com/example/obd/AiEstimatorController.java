package com.example.obd;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns the AI Repair Estimator screen. Lifecycle mirrors the other *Controller
 * classes (attach / detach), with one wrinkle: it needs the MainActivity to
 * launch the photo picker because Activity Result API contracts must be
 * registered before onStart. MainActivity exposes pickPhoto() which routes the
 * selected Uri back to a callback we provide on attach.
 */
public class AiEstimatorController {

    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024; // 4 MB after compress
    private static final int MAX_DIM = 1280;                    // long edge target
    // The on-screen thumbnail needs far fewer pixels than the upload — keeping
    // the 1280px ARGB bitmap alive just for a small ImageView wastes ~5 MB.
    private static final int PREVIEW_DIM = 640;

    private View root;
    private Activity activity;
    private ObdManagerFast obdManager;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // Bumped on every attach AND detach. Async work snapshots the value at
    // start; a mismatch at post time means the views it captured belong to a
    // layout that was detached (and possibly replaced by a re-attach), so the
    // UI update must be dropped instead of rendered into invisible views.
    private int attachGeneration;
    private Uri selectedUri;
    private byte[] selectedJpeg;
    private String lastVinSeen;
    // Follow-up chat state — reset whenever a new photo/analysis starts.
    private String lastEstimateJson;
    private final List<AiVisionProvider.ChatTurn> chatHistory = new ArrayList<>();

    public void attach(View view, Activity act, ObdManagerFast obd) {
        this.root = view;
        this.activity = act;
        this.obdManager = obd;
        attachGeneration++;

        Button btnPick = view.findViewById(R.id.btnPickPhoto);
        Button btnAnalyze = view.findViewById(R.id.btnAnalyze);
        ImageView preview = view.findViewById(R.id.imgPreview);
        EditText etContext = view.findViewById(R.id.etContext);
        TextView vinChip = view.findViewById(R.id.tvVinChip);
        View resultCard = view.findViewById(R.id.resultCard);
        ProgressBar progress = view.findViewById(R.id.progress);
        TextView tvResult = view.findViewById(R.id.tvResult);

        prefillFromVin(etContext, vinChip);
        // Chat card is always visible so users see the feature is there. The
        // Send handler stays wired even before an estimate exists — it just
        // shows a friendly "analyze first" bubble instead of hitting the API.
        wireChatCardIdle();

        btnPick.setOnClickListener(v -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).pickAiPhoto(uri -> {
                    if (uri == null) return;
                    selectedUri = uri;
                    loadAndCompress(uri, preview, btnAnalyze);
                });
            }
        });

        btnAnalyze.setOnClickListener(v -> {
            if (selectedJpeg == null) {
                Toast.makeText(activity, "Pick a photo first", Toast.LENGTH_SHORT).show();
                return;
            }
            String key = AiSettings.getEffectiveKey(activity);
            if (key == null || key.isEmpty()) {
                Toast.makeText(activity, "AI unavailable — please try again",
                        Toast.LENGTH_LONG).show();
                return;
            }
            String userContext = etContext.getText() == null
                    ? "" : etContext.getText().toString().trim();
            runAnalysis(key, userContext, resultCard, progress, tvResult, btnAnalyze);
        });
    }

    public void detach() {
        ui.removeCallbacksAndMessages(null);
        attachGeneration++;
        root = null;
        activity = null;
        obdManager = null;
        selectedUri = null;
        selectedJpeg = null;
        lastVinSeen = null;
        lastEstimateJson = null;
        chatHistory.clear();
    }

    /**
     * Auto-fill the AI context with the last known VIN's decoded make/model/year,
     * AND populate the prominent "Your Car" badge at the top of the screen.
     */
    private void prefillFromVin(EditText etContext, TextView vinChip) {
        if (activity == null) return;
        if (vinChip != null) vinChip.setVisibility(View.GONE);
        if (root != null) {
            View badge = root.findViewById(R.id.yourCarBadge);
            if (badge != null) badge.setVisibility(View.GONE);
        }

        // Snapshot fields into locals so the background thread doesn't read
        // null'd-out fields after a fast detach (memory leak / NPE guard).
        final Activity act = activity;
        final ObdManagerFast obd = obdManager;
        final int gen = attachGeneration;
        new Thread(() -> {
            String vin = null;
            try {
                com.example.obd.db.AppDatabase db =
                        com.example.obd.db.AppDatabase.get(act.getApplicationContext());
                java.util.List<com.example.obd.db.VinProfile> all = db.vins().all();
                if (!all.isEmpty() && all.get(0).vin != null) {
                    vin = all.get(0).vin;
                }
            } catch (Exception ignored) {}

            if (vin == null && obd != null && obd.isConnected()) {
                try { vin = obd.readVin(); } catch (Exception ignored) {}
            }

            if (vin == null || vin.length() < 11) return;
            lastVinSeen = vin;
            VinDecoder.Result r = VinDecoder.decode(vin);

            String contextLine;
            String badgeMain;
            String badgeSub;
            if (r != null) {
                // Detailed line for the AI context field
                StringBuilder sb = new StringBuilder("BMW ").append(r.modelName);
                if (r.modelYear > 0) sb.append(" (").append(r.modelYear).append(")");
                if (r.engineHint != null && !"—".equals(r.engineHint)) {
                    sb.append(", ").append(r.engineHint);
                }
                sb.append(", VIN ").append(r.vin);
                contextLine = sb.toString();

                // Compact two-line badge for the top-of-screen
                StringBuilder main = new StringBuilder("BMW ").append(r.modelName);
                if (r.modelYear > 0) main.append(" · ").append(r.modelYear);
                badgeMain = main.toString();

                StringBuilder sub = new StringBuilder();
                if (r.engineHint != null && !"—".equals(r.engineHint)) {
                    sub.append(r.engineHint).append("   ·   ");
                }
                sub.append("VIN ").append(r.vin);
                badgeSub = sub.toString();
            } else {
                contextLine = "VIN " + vin;
                badgeMain = "Detected VIN";
                badgeSub = vin;
            }
            final String finalContext = contextLine;
            final String finalMain = badgeMain;
            final String finalSub = badgeSub;

            ui.post(() -> {
                // Generation check: etContext/vinChip came from the view tree
                // that existed at attach time — stale after detach→re-attach.
                if (root == null || gen != attachGeneration) return;
                if (etContext != null && etContext.getText().toString().trim().isEmpty()) {
                    etContext.setText(finalContext);
                }
                if (vinChip != null) {
                    vinChip.setText("✓ Pre-filled from your car — edit if wrong");
                    vinChip.setVisibility(View.VISIBLE);
                }
                View badge = root.findViewById(R.id.yourCarBadge);
                TextView main = root.findViewById(R.id.tvYourCar);
                TextView sub = root.findViewById(R.id.tvYourCarSub);
                if (badge != null) badge.setVisibility(View.VISIBLE);
                if (main != null) main.setText(finalMain);
                if (sub != null) sub.setText(finalSub);
            });
        }, "AiVinPrefill").start();
    }

    public boolean isAttached() { return root != null; }

    private void loadAndCompress(Uri uri, ImageView preview, Button btnAnalyze) {
        // Snapshot the activity ref — detach() can null it while this thread runs.
        final Activity act = activity;
        if (act == null) return;
        final int gen = attachGeneration;
        new Thread(() -> {
            try {
                ContentResolver cr = act.getContentResolver();
                // Decode bounds first to compute downsample
                BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
                boundsOpts.inJustDecodeBounds = true;
                try (InputStream is = cr.openInputStream(uri)) {
                    BitmapFactory.decodeStream(is, null, boundsOpts);
                }
                // Sample to the power of two that keeps the long edge AT OR
                // ABOVE the target, then scale down exactly afterwards.
                // Sampling past the target loses real detail — a 2600px photo
                // sampled at 4 lands at 650px, half what the model should see.
                int sample = 1;
                int longEdge = Math.max(boundsOpts.outWidth, boundsOpts.outHeight);
                while (longEdge / (sample * 2) >= MAX_DIM) sample *= 2;

                // Camera photos store rotation as an EXIF tag, not in the
                // pixels — read it from a fresh stream (content streams can't
                // rewind) before decoding, or portrait shots upload sideways.
                int rotationDeg = 0;
                try (InputStream is = cr.openInputStream(uri)) {
                    if (is != null) {
                        ExifInterface exif = new ExifInterface(is);
                        int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL);
                        if (o == ExifInterface.ORIENTATION_ROTATE_90) rotationDeg = 90;
                        else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotationDeg = 180;
                        else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotationDeg = 270;
                    }
                } catch (Exception ignored) {
                    // Some providers strip EXIF or serve streams the parser
                    // rejects — treat as "no rotation" rather than failing the pick.
                }

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                Bitmap bmp;
                try (InputStream is = cr.openInputStream(uri)) {
                    bmp = BitmapFactory.decodeStream(is, null, opts);
                }
                if (bmp == null) throw new IllegalStateException("Could not decode image");

                if (rotationDeg != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(rotationDeg);
                    Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0,
                            bmp.getWidth(), bmp.getHeight(), m, true);
                    if (rotated != bmp) bmp.recycle();
                    bmp = rotated;
                }

                // Exact resize to the target — the sampling above only got us
                // to the nearest power of two at-or-above it.
                int w = bmp.getWidth();
                int h = bmp.getHeight();
                int longNow = Math.max(w, h);
                if (longNow > MAX_DIM) {
                    float scale = MAX_DIM / (float) longNow;
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                            Math.max(1, Math.round(w * scale)),
                            Math.max(1, Math.round(h * scale)), true);
                    if (scaled != bmp) bmp.recycle();
                    bmp = scaled;
                }

                // Compress to JPEG, dropping quality until under MAX_IMAGE_BYTES
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int quality = 85;
                while (true) {
                    baos.reset();
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos);
                    if (baos.size() <= MAX_IMAGE_BYTES || quality <= 40) break;
                    quality -= 10;
                }
                byte[] jpeg = baos.toByteArray();

                // The upload bytes exist now, so the full-resolution bitmap has
                // done its job — keep only a small copy for the ImageView and
                // give the rest of the heap back.
                int pLong = Math.max(bmp.getWidth(), bmp.getHeight());
                Bitmap previewBmp = bmp;
                if (pLong > PREVIEW_DIM) {
                    float pScale = PREVIEW_DIM / (float) pLong;
                    previewBmp = Bitmap.createScaledBitmap(bmp,
                            Math.max(1, Math.round(bmp.getWidth() * pScale)),
                            Math.max(1, Math.round(bmp.getHeight() * pScale)), true);
                    if (previewBmp != bmp) bmp.recycle();
                }

                final Bitmap finalPreview = previewBmp;
                ui.post(() -> {
                    if (root == null || gen != attachGeneration) {
                        // Screen detached (or replaced) since the pick — no
                        // view will ever show this bitmap, so free it now.
                        finalPreview.recycle();
                        return;
                    }
                    selectedJpeg = jpeg;
                    preview.setImageBitmap(finalPreview);
                    btnAnalyze.setEnabled(true);
                });
            } catch (Exception e) {
                ObdLogger.get().log(ObdLogger.Level.ERROR,
                        "AI photo decode failed: " + e.getMessage());
                ui.post(() -> {
                    Activity a = activity;
                    if (a == null || gen != attachGeneration) return;
                    Toast.makeText(a, "Could not load image: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "AiPhotoDecode").start();
    }

    private void runAnalysis(String apiKey, String userContext,
                             View resultCard, ProgressBar progress,
                             TextView tvResult, Button btnAnalyze) {
        resultCard.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        tvResult.setText("");
        btnAnalyze.setEnabled(false);

        // Clear any previous render
        if (root != null) {
            TextView sev = root.findViewById(R.id.tvSeverity);
            TextView ident = root.findViewById(R.id.tvIdentified);
            TextView summary = root.findViewById(R.id.tvSummary);
            TextView totals = root.findViewById(R.id.tvTotals);
            LinearLayout parts = root.findViewById(R.id.partsContainer);
            Button workshops = root.findViewById(R.id.btnFindWorkshops);
            if (sev != null) sev.setVisibility(View.GONE);
            if (ident != null) ident.setText("");
            if (summary != null) summary.setText("");
            if (totals != null) totals.setText("");
            if (parts != null) parts.removeAllViews();
            if (workshops != null) workshops.setVisibility(View.GONE);
            tvResult.setVisibility(View.GONE);
        }

        // Snapshot the JPEG bytes — detach() can null selectedJpeg mid-flight.
        final byte[] jpegSnapshot = selectedJpeg;
        if (jpegSnapshot == null) return;
        // Snapshots for the persistence path: the DB save must survive a
        // detach, so it can't read fields that detach() nulls out.
        final Activity act = activity;
        final String vinSnapshot = lastVinSeen;
        final int gen = attachGeneration;
        new Thread(() -> {
            String result;
            JSONObject parsed = null;
            boolean ok;
            try {
                AiVisionProvider provider = new GeminiVisionProvider(apiKey);
                result = provider.analyzeDamage(jpegSnapshot, userContext);
                ok = true;
                ObdLogger.get().log(ObdLogger.Level.INFO,
                        "AI estimate OK (" + result.length() + " chars)");
                try {
                    parsed = new JSONObject(result);
                } catch (Exception parseErr) {
                    // Fallback path below shows the raw text instead.
                    ObdLogger.get().log(ObdLogger.Level.ERROR,
                            "AI JSON parse failed: " + parseErr.getMessage());
                }
                // Persist the report even if the screen has since detached —
                // the API call already happened; losing the result to a
                // navigation change would waste it. saveAiEstimate uses the
                // application context internally, so the snapshot is safe.
                if (parsed != null && act != null) {
                    ScanReportRepo.saveAiEstimate(act, vinSnapshot, parsed);
                }
            } catch (Exception e) {
                result = "Analysis failed: " + e.getMessage();
                ok = false;
                ObdLogger.get().log(ObdLogger.Level.ERROR,
                        "AI estimate failed: " + e.getMessage());
            }
            final String finalResult = result;
            final JSONObject finalParsed = parsed;
            final boolean finalOk = ok;
            ui.post(() -> {
                // Generation check: resultCard/progress/tvResult belong to the
                // view tree captured at click time — stale after re-attach.
                if (root == null || gen != attachGeneration) return;
                progress.setVisibility(View.GONE);
                btnAnalyze.setEnabled(true);
                if (!finalOk) {
                    tvResult.setVisibility(View.VISIBLE);
                    tvResult.setText(finalResult);
                    Activity a = activity;
                    if (a != null) {
                        Toast.makeText(a, "Analysis failed — see log",
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                if (finalParsed != null) {
                    renderStructured(finalParsed);
                    // Chat is scoped to the current estimate. New estimate =
                    // fresh conversation, otherwise questions inherit stale
                    // context from a previous car.
                    lastEstimateJson = finalResult;
                    chatHistory.clear();
                    showChatCard();
                } else {
                    // Fallback: show raw text if JSON parse failed
                    tvResult.setVisibility(View.VISIBLE);
                    tvResult.setText(finalResult);
                }
            });
        }, "AiAnalyze").start();
    }

    /** Render Gemini's JSON response as a structured card list. */
    private void renderStructured(JSONObject json) {
        if (root == null || activity == null) return;
        TextView sev = root.findViewById(R.id.tvSeverity);
        TextView ident = root.findViewById(R.id.tvIdentified);
        TextView summary = root.findViewById(R.id.tvSummary);
        TextView totals = root.findViewById(R.id.tvTotals);
        LinearLayout partsContainer = root.findViewById(R.id.partsContainer);
        Button btnWorkshops = root.findViewById(R.id.btnFindWorkshops);

        String identified = json.optString("identified_part", "Unknown part");
        String confidence = json.optString("confidence", "");
        String severity = json.optString("severity", "");
        String summaryText = json.optString("summary", "");
        String workshopQuery = json.optString("workshop_search_query", "car repair workshop Dubai");
        String difficulty = json.optString("difficulty", "");
        double timeLo = json.optDouble("time_to_fix_hours_low", -1);
        double timeHi = json.optDouble("time_to_fix_hours_high", -1);
        String diagramQuery = json.optString("diagram_search_query", "");
        String manualQuery = json.optString("manual_search_query", "");

        if (!severity.isEmpty() && sev != null) {
            sev.setText(severity);
            sev.setBackgroundColor(severityColor(severity));
            sev.setVisibility(View.VISIBLE);
        }
        if (ident != null) {
            ident.setText(identified + (confidence.isEmpty() ? "" : " · " + confidence + " confidence"));
        }
        if (summary != null) {
            summary.setText(summaryText);
        }

        // ---- Difficulty / time / diagram / manual row (chips + buttons) ----
        if (partsContainer != null && (!difficulty.isEmpty() || timeLo > 0
                || !diagramQuery.isEmpty() || !manualQuery.isEmpty())) {
            partsContainer.addView(buildMetaRow(difficulty, timeLo, timeHi,
                    diagramQuery, manualQuery));
        }

        // ---- Tools needed ----
        appendBulletSection(partsContainer, "🧰 Tools needed",
                json.optJSONArray("tools_needed"), "#FFB300");

        // ---- Safety warnings ----
        appendBulletSection(partsContainer, "⚠ Safety warnings",
                json.optJSONArray("safety_warnings"), "#FF6E6E");

        // ---- Repair steps (the handout / manual section) ----
        appendNumberedSection(partsContainer, "📖 How to fix it",
                json.optJSONArray("repair_steps"));

        // ---- What if delayed ----
        String delay = json.optString("what_if_delayed", "");
        if (!delay.isEmpty() && partsContainer != null) {
            TextView header = new TextView(activity);
            header.setText("⏳ What if I delay this?");
            header.setTextColor(Color.parseColor("#FFB300"));
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            header.setTypeface(null, android.graphics.Typeface.BOLD);
            header.setPadding(0, dp(12), 0, dp(4));
            partsContainer.addView(header);
            TextView body = new TextView(activity);
            body.setText(delay);
            body.setTextColor(Color.parseColor("#D0D0D0"));
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            partsContainer.addView(body);
        }

        // ---- Related inspections ("While you're there...") ----
        appendBulletSection(partsContainer, "🔍 While you're there, check",
                json.optJSONArray("related_inspections"), "#03DAC5");

        // ---- Parts cards ----
        if (partsContainer != null) {
            TextView partsHeader = new TextView(activity);
            partsHeader.setText("🔩 Parts to order");
            partsHeader.setTextColor(Color.WHITE);
            partsHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            partsHeader.setTypeface(null, android.graphics.Typeface.BOLD);
            partsHeader.setPadding(0, dp(14), 0, dp(6));
            partsContainer.addView(partsHeader);
        }
        JSONArray parts = json.optJSONArray("parts");
        if (partsContainer != null && parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p == null) continue;
                partsContainer.addView(buildPartCard(p));
            }
        }

        // ---- Totals ----
        if (totals != null) {
            int indieLo = json.optInt("total_aed_indie_low", -1);
            int indieHi = json.optInt("total_aed_indie_high", -1);
            int dealerLo = json.optInt("total_aed_dealer_low", -1);
            int dealerHi = json.optInt("total_aed_dealer_high", -1);
            int resaleLo = json.optInt("resale_impact_aed_low", -1);
            int resaleHi = json.optInt("resale_impact_aed_high", -1);
            StringBuilder sb = new StringBuilder();
            sb.append("💰 TOTAL estimate\n");
            if (indieLo > 0) {
                sb.append("• Indie garage: AED ").append(indieLo).append(" – ").append(indieHi).append("\n");
            }
            if (dealerLo > 0) {
                sb.append("• Dealer: AED ").append(dealerLo).append(" – ").append(dealerHi).append("\n");
            }
            if (resaleLo > 0) {
                sb.append("📉 Resale impact if NOT fixed: AED ").append(resaleLo).append(" – ").append(resaleHi);
            }
            totals.setText(sb.toString().trim());
        }

        if (btnWorkshops != null) {
            btnWorkshops.setVisibility(View.VISIBLE);
            btnWorkshops.setOnClickListener(v -> openMaps(workshopQuery));
        }
    }

    // =========================================================================
    // Chat follow-up
    // =========================================================================

    /**
     * Initial chat-card state on attach — shown but idle. The Send button
     * refuses to hit the API until an estimate has been analysed, and prompts
     * the user to do that first. Keeps the feature discoverable without
     * confusing "why is my typed question being ignored?" behaviour.
     */
    private void wireChatCardIdle() {
        if (root == null) return;
        LinearLayout messages = root.findViewById(R.id.chatMessages);
        final EditText input = root.findViewById(R.id.etChatInput);
        final Button send = root.findViewById(R.id.btnChatSend);
        if (messages == null || input == null || send == null) return;
        messages.removeAllViews();
        appendAssistantBubble(messages,
                "Analyze a damage photo above first — then I can answer questions "
                        + "about parts, prices, DIY steps, or Dubai workshops.",
                null);
        View.OnClickListener onSend = v -> onSendChat(input, messages, send);
        send.setOnClickListener(onSend);
        input.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                onSend.onClick(tv);
                return true;
            }
            return false;
        });
    }

    /**
     * Called after a successful analysis. Clears the idle placeholder,
     * shows a "ready to chat" greeting, and leaves the Send handler in place.
     */
    private void showChatCard() {
        if (root == null) return;
        LinearLayout messages = root.findViewById(R.id.chatMessages);
        if (messages == null) return;
        messages.removeAllViews();
        appendAssistantBubble(messages,
                "Ready — ask about the parts, prices, DIY steps, or where to buy in Dubai.",
                null);
    }

    /**
     * Send handler: append the user bubble immediately, show a placeholder
     * "Thinking…" reply, then swap it for the real answer once Gemini returns.
     * Placeholder swap keeps the UI honest — the user knows the app didn't
     * just eat their tap.
     */
    private void onSendChat(EditText input, LinearLayout messages, Button send) {
        if (activity == null) return;
        final CharSequence raw = input.getText();
        final String question = raw == null ? "" : raw.toString().trim();
        if (question.isEmpty()) {
            Toast.makeText(activity, "Type a question first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (lastEstimateJson == null) {
            appendAssistantBubble(messages,
                    "Please analyze a damage photo first — I need the estimate as context "
                            + "before I can answer follow-ups.",
                    null);
            return;
        }
        String key = AiSettings.getEffectiveKey(activity);
        if (key == null || key.isEmpty()) {
            Toast.makeText(activity, "AI unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        input.setText("");
        send.setEnabled(false);

        appendUserBubble(messages, question);
        chatHistory.add(new AiVisionProvider.ChatTurn("user", question));

        // Placeholder while the network call is in-flight. Reference is held
        // via ArrayList so the async callback can find + replace it.
        final TextView pending = appendAssistantBubble(messages, "Thinking…", null);

        // Snapshot state the background thread will read.
        final String apiKey = key;
        final String estimateJson = lastEstimateJson;
        final byte[] image = selectedJpeg;
        final int gen = attachGeneration;
        final List<AiVisionProvider.ChatTurn> historySnapshot = new ArrayList<>(chatHistory);
        // Drop the just-appended user turn from history — the provider adds it
        // as the final "question" argument, so keeping it in history would
        // double-send.
        if (!historySnapshot.isEmpty()
                && "user".equals(historySnapshot.get(historySnapshot.size() - 1).role)) {
            historySnapshot.remove(historySnapshot.size() - 1);
        }

        new Thread(() -> {
            AiVisionProvider.ChatReply reply;
            try {
                AiVisionProvider provider = new GeminiVisionProvider(apiKey);
                reply = provider.chatFollowup(estimateJson, image, historySnapshot, question);
                ObdLogger.get().log(ObdLogger.Level.INFO,
                        "AI chat OK (" + reply.text.length() + " chars, "
                                + reply.sourceUrls.size() + " sources)");
            } catch (Exception e) {
                reply = new AiVisionProvider.ChatReply(
                        "Sorry — that didn't go through. " + e.getMessage(), null);
                ObdLogger.get().log(ObdLogger.Level.ERROR,
                        "AI chat failed: " + e.getMessage());
            }
            final AiVisionProvider.ChatReply finalReply = reply;
            ui.post(() -> {
                // Generation check: `pending` lives in the old view tree after
                // a detach→re-attach, and chatHistory was cleared on detach —
                // appending the reply would corrupt the fresh conversation.
                if (root == null || gen != attachGeneration) return;
                // Swap the "Thinking…" bubble with the real answer + source links.
                pending.setText(finalReply.text);
                LinearLayout container = (LinearLayout) pending.getParent();
                if (container != null && !finalReply.sourceUrls.isEmpty()) {
                    appendSourceLinks(container, finalReply.sourceUrls);
                }
                chatHistory.add(new AiVisionProvider.ChatTurn("model", finalReply.text));
                send.setEnabled(true);
            });
        }, "AiChat").start();
    }

    /** Right-aligned teal bubble for the customer's message. */
    private void appendUserBubble(LinearLayout parent, String text) {
        if (activity == null) return;
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#001F1F"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setBackgroundColor(Color.parseColor("#03DAC5"));
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(40), dp(6), 0, dp(2));
        lp.gravity = android.view.Gravity.END;
        tv.setLayoutParams(lp);
        tv.setTextIsSelectable(true);
        parent.addView(tv);
    }

    /**
     * Left-aligned dark bubble for Otto's reply. Returns the TextView so the
     * placeholder path can swap the text in place once the real answer lands.
     */
    private TextView appendAssistantBubble(LinearLayout parent, String text, List<String> sources) {
        if (activity == null) return null;
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(Color.parseColor("#1E1E1E"));
        int pad = dp(10);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(6), dp(40), dp(2));
        row.setLayoutParams(rlp);

        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#EAEAEA"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextIsSelectable(true);
        row.addView(tv);

        if (sources != null && !sources.isEmpty()) {
            appendSourceLinks(row, sources);
        }
        parent.addView(row);
        return tv;
    }

    /** Compact list of tappable source URLs beneath a bubble. Domain-only text. */
    private void appendSourceLinks(LinearLayout parent, List<String> urls) {
        if (activity == null || urls == null || urls.isEmpty()) return;
        TextView header = new TextView(activity);
        header.setText("Sources");
        header.setTextColor(Color.parseColor("#909090"));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        header.setLetterSpacing(0.15f);
        header.setPadding(0, dp(6), 0, dp(2));
        parent.addView(header);
        int shown = 0;
        for (final String u : urls) {
            if (shown++ >= 6) break; // cap so the bubble doesn't sprawl
            TextView link = new TextView(activity);
            String domain = domainOf(u);
            link.setText("↗ " + domain);
            link.setTextColor(Color.parseColor("#03DAC5"));
            link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            link.setPadding(0, dp(2), 0, dp(2));
            link.setOnClickListener(v -> openUrl(u));
            parent.addView(link);
        }
    }

    private static String domainOf(String url) {
        if (url == null) return "";
        try {
            java.net.URI u = new java.net.URI(url);
            String host = u.getHost();
            if (host == null || host.isEmpty()) return url;
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return url.length() > 40 ? url.substring(0, 40) + "…" : url;
        }
    }

    /** Difficulty / time chips + "Diagram" + "Service manual" search buttons. */
    private View buildMetaRow(String difficulty, double timeLo, double timeHi,
                              String diagramQuery, String manualQuery) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#152633"));
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(clp);

        // Chips row
        LinearLayout chips = new LinearLayout(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        if (!difficulty.isEmpty()) {
            TextView c = new TextView(activity);
            c.setText("🛠 " + difficulty);
            c.setTextColor(Color.parseColor("#03DAC5"));
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            c.setTypeface(null, android.graphics.Typeface.BOLD);
            c.setPadding(dp(8), dp(4), dp(8), dp(4));
            c.setBackgroundColor(Color.parseColor("#082E33"));
            chips.addView(c);
            chips.addView(chipGap());
        }
        if (timeLo > 0) {
            TextView c = new TextView(activity);
            String range = (timeLo == timeHi)
                    ? String.format(Locale.US, "%.1f h", timeLo)
                    : String.format(Locale.US, "%.1f–%.1f h", timeLo, timeHi);
            c.setText("⏱ " + range);
            c.setTextColor(Color.parseColor("#FFD600"));
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            c.setTypeface(null, android.graphics.Typeface.BOLD);
            c.setPadding(dp(8), dp(4), dp(8), dp(4));
            c.setBackgroundColor(Color.parseColor("#3A2F00"));
            chips.addView(c);
        }
        if (chips.getChildCount() > 0) {
            card.addView(chips);
        }

        // Diagram + Manual buttons
        if (!diagramQuery.isEmpty() || !manualQuery.isEmpty()) {
            LinearLayout btnRow = new LinearLayout(activity);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            brlp.setMargins(0, dp(10), 0, 0);
            btnRow.setLayoutParams(brlp);

            if (!diagramQuery.isEmpty()) {
                btnRow.addView(makeMiniButton("📐 Diagram", v -> openUrl(
                        "https://www.google.com/search?tbm=isch&q=" + Uri.encode(diagramQuery))));
            }
            if (!diagramQuery.isEmpty() && !manualQuery.isEmpty()) btnRow.addView(spacer());
            if (!manualQuery.isEmpty()) {
                btnRow.addView(makeMiniButton("📘 Manual", v -> openUrl(
                        "https://www.google.com/search?q=" + Uri.encode(manualQuery))));
            }
            card.addView(btnRow);
        }
        return card;
    }

    private View chipGap() {
        View v = new View(activity);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1));
        return v;
    }

    private void appendBulletSection(LinearLayout parent, String headerText,
                                     JSONArray items, String headerColor) {
        if (parent == null || items == null || items.length() == 0) return;
        TextView h = new TextView(activity);
        h.setText(headerText);
        h.setTextColor(Color.parseColor(headerColor));
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        h.setPadding(0, dp(12), 0, dp(4));
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

    private void appendNumberedSection(LinearLayout parent, String headerText, JSONArray items) {
        if (parent == null || items == null || items.length() == 0) return;
        TextView h = new TextView(activity);
        h.setText(headerText);
        h.setTextColor(Color.parseColor("#03DAC5"));
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        h.setPadding(0, dp(14), 0, dp(6));
        parent.addView(h);
        for (int i = 0; i < items.length(); i++) {
            String s = items.optString(i, null);
            if (s == null || s.isEmpty()) continue;
            TextView item = new TextView(activity);
            item.setText("  " + (i + 1) + ".  " + s);
            item.setTextColor(Color.parseColor("#E0E0E0"));
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            item.setPadding(0, dp(3), 0, dp(3));
            parent.addView(item);
        }
    }

    /** Programmatic card for one suggested part — name, OEM, price, 3 action buttons. */
    private View buildPartCard(JSONObject p) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#1a1a1a"));
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);

        String name = p.optString("name", "Part");
        String oem = p.optString("oem_number", "");
        int priceLo = p.optInt("price_aed_low", -1);
        int priceHi = p.optInt("price_aed_high", -1);
        String imgQ = p.optString("image_search_query", name);
        String buyQ = p.optString("buy_search_query", name);

        TextView title = new TextView(activity);
        title.setText(name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);

        if (!oem.isEmpty()) {
            TextView oemTv = new TextView(activity);
            oemTv.setText("OEM: " + oem);
            oemTv.setTextColor(Color.parseColor("#909090"));
            oemTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            card.addView(oemTv);
        }

        if (priceLo > 0) {
            TextView priceTv = new TextView(activity);
            priceTv.setText("Parts price: AED " + priceLo + " – " + priceHi);
            priceTv.setTextColor(Color.parseColor("#03DAC5"));
            priceTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            priceTv.setPadding(0, dp(4), 0, dp(8));
            card.addView(priceTv);
        }

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(makeMiniButton("🖼 Image", v -> openUrl(
                "https://www.google.com/search?tbm=isch&q=" + Uri.encode(imgQ))));
        row.addView(spacer());
        row.addView(makeMiniButton("🛒 Dubizzle", v -> openUrl(
                "https://uae.dubizzle.com/motors/used-cars/?keywords=" + Uri.encode(buyQ))));
        row.addView(spacer());
        row.addView(makeMiniButton("🛒 Amazon AE", v -> openUrl(
                "https://www.amazon.ae/s?k=" + Uri.encode(buyQ))));
        card.addView(row);

        return card;
    }

    private View spacer() {
        View s = new View(activity);
        s.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1));
        return s;
    }

    private Button makeMiniButton(String label, View.OnClickListener click) {
        Button b = new Button(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(blp);
        b.setMinimumHeight(0);
        b.setMinHeight(0);
        b.setOnClickListener(click);
        return b;
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density);
    }

    private int severityColor(String severity) {
        if (severity == null) return Color.parseColor("#FFB300");
        String s = severity.toLowerCase();
        if (s.contains("immediately") || s.contains("stop")) return Color.parseColor("#F44336");
        if (s.contains("carefully")) return Color.parseColor("#FFB300");
        if (s.contains("cosmetic")) return Color.parseColor("#9E9E9E");
        return Color.parseColor("#03DAC5");
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

    private void openMaps(String query) {
        if (activity == null) return;
        // Google Maps geo: intent — opens Maps with a search query. If Maps isn't
        // installed, fall back to a regular https search URL.
        try {
            Uri gmm = Uri.parse("geo:0,0?q=" + Uri.encode(query));
            Intent i = new Intent(Intent.ACTION_VIEW, gmm);
            i.setPackage("com.google.android.apps.maps");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (ActivityNotFoundException e) {
            openUrl("https://www.google.com/maps/search/" + Uri.encode(query));
        } catch (Exception e) {
            openUrl("https://www.google.com/maps/search/" + Uri.encode(query));
        }
    }
}
