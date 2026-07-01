package com.example.obd;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.speech.tts.TextToSpeech;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bottom-sheet that explains a single DTC and exposes one-tap deeplinks to
 * YouTube (fix videos), parts retailers (RockAuto / FCP Euro / eBay), and
 * the user's preferred free AI chat tool with a pre-filled prompt.
 *
 * <p>Free AI is delivered without an API key by building a perfect prompt and
 * launching the user's choice of ChatGPT / Claude / Gemini / Perplexity in the
 * browser. The prompt is also copied to the clipboard so the user can paste it
 * if the in-URL prefill doesn't survive a redirect.</p>
 *
 * <p>TTS reuses the same Android TextToSpeech engine the {@link AlertManager}
 * uses elsewhere — a lightweight local instance is created here so we can show
 * the help screen even when AlertManager isn't initialized.</p>
 */
public final class DtcHelpController {

    private DtcHelpController() {}

    /** Show the help bottom-sheet for the given code, with optional live readings to enrich the AI prompt. */
    public static void show(Context ctx, String code, Map<String, Double> liveReadings) {
        DtcDictionary.get().loadIfNeeded(ctx);
        DtcDictionary.Entry dict = DtcDictionary.get().lookup(code);
        DtcHelp.Help help = DtcHelp.forCode(code, dict);

        View v = LayoutInflater.from(ctx).inflate(R.layout.layout_dtc_help, null);

        ((TextView) v.findViewById(R.id.dtcHelpCode)).setText(code);
        ((TextView) v.findViewById(R.id.dtcHelpTitle)).setText(help.title);

        TextView sev = v.findViewById(R.id.dtcHelpSeverityChip);
        sev.setText(help.severity.toUpperCase(Locale.US));
        sev.setBackgroundResource(severityPill(help.severity));
        sev.setTextColor(severityTextColor(help.severity));

        ((TextView) v.findViewById(R.id.dtcHelpCause)).setText(
                help.cause == null || help.cause.isEmpty()
                        ? "No curated description yet for this code. Try the AI button below — describe your symptoms and it'll walk you through it."
                        : help.cause);

        StringBuilder parts = new StringBuilder();
        for (int i = 0; i < help.parts.size(); i++) {
            parts.append("• ").append(help.parts.get(i));
            if (i < help.parts.size() - 1) parts.append('\n');
        }
        ((TextView) v.findViewById(R.id.dtcHelpParts)).setText(parts.toString());

        ((TextView) v.findViewById(R.id.dtcHelpProcedure)).setText(
                help.procedure == null || help.procedure.isEmpty()
                        ? "(No curated procedure — use the YouTube and Ask AI buttons for guidance.)"
                        : help.procedure);

        ((TextView) v.findViewById(R.id.dtcHelpDiyRating)).setText(
                "DIY difficulty: " + diyStars(help.diyRating));

        BottomSheetDialog dlg = new BottomSheetDialog(ctx);
        dlg.setContentView(v);

        // YouTube
        ((Button) v.findViewById(R.id.btnDtcYoutube)).setOnClickListener(b ->
                open(ctx, DtcHelp.youtubeSearch(help)));

        // Parts chooser
        ((Button) v.findViewById(R.id.btnDtcParts)).setOnClickListener(b ->
                showPartsChooser(ctx, help));

        // Ask AI chooser — also copies prompt to clipboard
        ((Button) v.findViewById(R.id.btnDtcAskAi)).setOnClickListener(b ->
                showAiChooser(ctx, help, liveReadings));

        // Read aloud
        ((Button) v.findViewById(R.id.btnDtcReadAloud)).setOnClickListener(b ->
                speakAloud(ctx, help));

        dlg.show();
    }

    private static void showPartsChooser(Context ctx, DtcHelp.Help help) {
        // Without curated parts, keyword-search URLs return junk. Fall back to the
        // RockAuto BMW catalog where the user can browse by system instead.
        if (!DtcHelp.hasCuratedParts(help)) {
            new AlertDialog.Builder(ctx)
                    .setTitle("No specific parts mapped")
                    .setMessage("This code doesn't have a curated parts list yet. "
                            + "Opening the RockAuto BMW catalog so you can browse by system.")
                    .setPositiveButton("Open RockAuto", (d, w) -> open(ctx, DtcHelp.rockAutoBmw()))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        String[] labels = {
                "RockAuto — BMW 2007 730li catalog",
                "FCP Euro — keyword search (lifetime warranty)",
                "eBay Motors — broadest catalog"
        };
        new AlertDialog.Builder(ctx)
                .setTitle("Open parts search in browser")
                .setItems(labels, (d, which) -> {
                    Uri u;
                    switch (which) {
                        case 0: u = DtcHelp.rockAutoBmw(); break;
                        case 1: u = DtcHelp.fcpEuroSearch(help); break;
                        case 2: u = DtcHelp.ebayMotorsSearch(help); break;
                        default: return;
                    }
                    open(ctx, u);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void showAiChooser(Context ctx, DtcHelp.Help help, Map<String, Double> live) {
        // Try to pick up the latest VIN/model. Lazy lookup so we don't block opening the sheet.
        String vinOrModel = "730li (E65)";
        try {
            com.example.obd.db.AppDatabase db = com.example.obd.db.AppDatabase.get(ctx);
            java.util.List<com.example.obd.db.VinProfile> vins = db.vins().all();
            if (!vins.isEmpty() && vins.get(0).vin != null) {
                vinOrModel = vins.get(0).vin + " (730li E65 assumed)";
            }
        } catch (Exception ignored) { /* fall back to default */ }

        final String prompt = DtcHelp.aiPrompt(help, vinOrModel,
                live == null ? new HashMap<>() : live);

        // Copy to clipboard preemptively — if the URL prefill is stripped on redirect, the user
        // can paste with a single tap on the chat page.
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("DTC AI prompt", prompt));
        Toast.makeText(ctx, "Prompt copied to clipboard", Toast.LENGTH_SHORT).show();

        String[] labels = { "ChatGPT (chatgpt.com)", "Claude (claude.ai)",
                            "Gemini (gemini.google.com)", "Perplexity (perplexity.ai)" };
        new AlertDialog.Builder(ctx)
                .setTitle("Ask which AI? (all free)")
                .setItems(labels, (d, which) -> {
                    Uri u;
                    switch (which) {
                        case 0: u = DtcHelp.askChatGpt(prompt); break;
                        case 1: u = DtcHelp.askClaude(prompt); break;
                        case 2: u = DtcHelp.askGemini(prompt); break;
                        case 3: u = DtcHelp.askPerplexity(prompt); break;
                        default: return;
                    }
                    open(ctx, u);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void open(Context ctx, Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(ctx, "Can't open browser: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static void speakAloud(Context ctx, DtcHelp.Help help) {
        StringBuilder sb = new StringBuilder();
        sb.append("Code ").append(spellOut(help.code)).append(". ");
        sb.append(help.title).append(". ");
        if (help.cause != null && !help.cause.isEmpty()) sb.append(help.cause).append(" ");
        if (help.procedure != null && !help.procedure.isEmpty()) {
            sb.append("Procedure: ").append(help.procedure);
        }
        final String text = sb.toString();
        final TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(ctx.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                Toast.makeText(ctx, "Text-to-speech unavailable", Toast.LENGTH_SHORT).show();
                if (holder[0] != null) { try { holder[0].shutdown(); } catch (Exception ignored) {} }
                return;
            }
            holder[0].setLanguage(Locale.US);
            // Tear down the engine once playback finishes so we don't leak one TTS
            // instance per Read-aloud tap. Done listener fires on completion, error,
            // and start (we only act on done/error so start doesn't kill mid-speech).
            holder[0].setOnUtteranceProgressListener(
                    new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { shutdown(); }
                @Override public void onError(String id) { shutdown(); }
                private void shutdown() {
                    if (holder[0] == null) return;
                    try { holder[0].shutdown(); } catch (Exception ignored) {}
                    holder[0] = null;
                }
            });
            holder[0].speak(text, TextToSpeech.QUEUE_FLUSH, null, "dtc-help");
        });
    }

    /** "P0420" → "P zero four two zero" so TTS reads the code clearly. */
    private static String spellOut(String code) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (i > 0) sb.append(' ');
            switch (c) {
                case '0': sb.append("zero"); break;
                case '1': sb.append("one"); break;
                case '2': sb.append("two"); break;
                case '3': sb.append("three"); break;
                case '4': sb.append("four"); break;
                case '5': sb.append("five"); break;
                case '6': sb.append("six"); break;
                case '7': sb.append("seven"); break;
                case '8': sb.append("eight"); break;
                case '9': sb.append("nine"); break;
                default:  sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int severityPill(String s) {
        if (s == null) return R.drawable.status_chip_neutral;
        switch (s) {
            case "critical":
            case "high":   return R.drawable.status_pill_err;
            case "medium": return R.drawable.status_pill_warn;
            case "low":    return R.drawable.status_pill_ok;
            default:       return R.drawable.status_chip_neutral;
        }
    }

    private static int severityTextColor(String s) {
        if ("medium".equals(s) || "low".equals(s)) return 0xFF000000;
        return 0xFFFFFFFF;
    }

    private static String diyStars(int diy) {
        String full = "★★★★★";
        String empty = "☆☆☆☆☆";
        int n = Math.max(0, Math.min(5, diy));
        return full.substring(0, n) + empty.substring(n);
    }
}
