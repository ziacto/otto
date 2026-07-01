package com.example.obd;

import java.util.Collections;
import java.util.List;

/**
 * Abstract over the AI vision + chat provider so we can swap Gemini → Claude → OpenAI
 * without rewriting the controller. Phase 1 shipped analyze-only; Phase 2 adds
 * follow-up chat with web-grounded answers.
 */
public interface AiVisionProvider {

    /** Blocking call. Run on a background thread. */
    String analyzeDamage(byte[] jpegBytes, String userContext) throws Exception;

    /**
     * Follow-up chat turn. The provider is expected to keep the original photo
     * + estimate in scope so questions like "where can I buy this in Deira?" or
     * "is the OEM number correct for a 2007 model?" get properly grounded
     * answers. Enable web search under the hood so responses stay current on
     * parts pricing / recalls / TSBs.
     *
     * @param estimateJson  the raw JSON from {@link #analyzeDamage} — the whole
     *                      structured estimate, which the LLM uses as context.
     * @param imageJpeg     the original photo bytes, re-attached so the LLM can
     *                      still refer to the image on later turns.
     * @param history       prior chat turns, oldest first. Empty list for the
     *                      first follow-up.
     * @param question      the user's new question, plain text.
     */
    ChatReply chatFollowup(String estimateJson,
                           byte[] imageJpeg,
                           List<ChatTurn> history,
                           String question) throws Exception;

    /** Human-readable name for the result card / settings. */
    String getDisplayName();

    /** One turn in the chat history. */
    final class ChatTurn {
        public final String role; // "user" or "model"
        public final String text;
        public ChatTurn(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    /** Reply payload — main text plus any web sources the provider cited. */
    final class ChatReply {
        public final String text;
        public final List<String> sourceUrls;
        public ChatReply(String text, List<String> sourceUrls) {
            this.text = text;
            this.sourceUrls = sourceUrls == null ? Collections.emptyList() : sourceUrls;
        }
    }
}
