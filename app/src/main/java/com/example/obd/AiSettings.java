package com.example.obd;

import android.content.Context;

/**
 * Resolves the Gemini API key for AI calls.
 *
 * <p>The key is bundled in {@link EmbeddedAiKey} so every user gets unlimited
 * AI out-of-the-box. There is NO user-pasted key path and NO per-device quota
 * — Otto is positioned as a Pro app where free use is funded by ads and
 * "remove ads" is the IAP. The user-key entry UI was removed when monetisation
 * shifted from BYO-key to ad-supported.
 *
 * <p>Cost note for future-me: at scale, the bundled key can hit Google's
 * 1500 RPD free-tier limit per project. Mitigation path is a backend proxy
 * (Cloudflare Worker) with multiple Gemini projects round-robined, or moving
 * to paid Gemini billing offset by ad revenue. See project_ai_estimator.md.
 */
public final class AiSettings {

    private AiSettings() {}

    /** Returns the bundled Gemini key, or null if somehow missing. */
    public static String getEffectiveKey(Context ctx) {
        String embedded = EmbeddedAiKey.get();
        return (embedded == null || embedded.isEmpty()) ? null : embedded;
    }
}
