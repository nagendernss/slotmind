package com.slotmind.ai;

/** Any LLM / AI-strategy failure. The ScheduleEngine catches it and falls back to rule-based. */
public class LlmException extends RuntimeException {
    public LlmException(String message) { super(message); }
    public LlmException(String message, Throwable cause) { super(message, cause); }
}
