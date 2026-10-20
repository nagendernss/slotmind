package com.slotmind.ai;

/** Minimal LLM abstraction (Addendum B naming). Implementations throw {@link LlmException} on any failure. */
public interface LlmGateway {
    String callLLM(String systemPrompt, String userPrompt);
}
