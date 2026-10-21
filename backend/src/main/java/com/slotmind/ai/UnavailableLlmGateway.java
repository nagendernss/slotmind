package com.slotmind.ai;

/**
 * Used when a real provider is configured but no API key is set. Every call fails, so the engine applies the
 * brief's rule-based fallback and the UI labels the suggestion "Rule-based fallback · AI unavailable"
 * instead of presenting canned text as AI output.
 */
public class UnavailableLlmGateway implements LlmGateway {
    private final String reason;

    public UnavailableLlmGateway(String reason) { this.reason = reason; }

    @Override
    public String callLLM(String systemPrompt, String userPrompt) {
        throw new LlmException("AI unavailable: " + reason);
    }

    public String reason() { return reason; }
}
