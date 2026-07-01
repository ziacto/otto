package com.example.obd;

import android.content.Context;

/**
 * Resolves the Gemini API key for AI calls.
 *
 * <p>The key is injected at build time via {@code BuildConfig.AI_KEY} from a
 * line in {@code local.properties} — a file that's git-ignored by default in
 * every Android project. Fresh clones with no key set compile fine; AI calls
 * just return null and the UI shows "AI unavailable".
 *
 * <p>To wire up AI locally, add this line to {@code local.properties} at the
 * project root:
 *
 * <pre>AI_KEY=AIza…YOUR_KEY_HERE</pre>
 *
 * Rebuild. That's the whole setup — no source-code changes needed.
 *
 * <p>Cost note for future-me: at scale, one shared key can hit Google's
 * 1500 RPD free-tier limit. Mitigation is a backend proxy (Cloudflare Worker)
 * with multiple Gemini projects round-robined, or paid billing offset by ad
 * revenue. See {@code memory/project_ai_estimator.md}.
 */
public final class AiSettings {

    private AiSettings() {}

    /** Returns the configured Gemini key, or null if none is set. */
    public static String getEffectiveKey(Context ctx) {
        String key = BuildConfig.AI_KEY;
        return (key == null || key.isEmpty()) ? null : key;
    }
}
