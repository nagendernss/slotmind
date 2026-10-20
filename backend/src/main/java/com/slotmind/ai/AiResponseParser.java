package com.slotmind.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotmind.scheduling.SlotRecommendation;
import org.springframework.stereotype.Component;

/** Turns raw model text into a {@link SlotRecommendation}. Tolerates code fences / chatter; throws on anything unusable. */
@Component
public class AiResponseParser {
    private final ObjectMapper mapper;

    public AiResponseParser() { this(new ObjectMapper()); }

    public AiResponseParser(ObjectMapper mapper) { this.mapper = mapper; }

    public SlotRecommendation parse(String raw) {
        if (raw == null || raw.isBlank()) throw new LlmException("Empty LLM response");
        String json = extractJson(raw);
        JsonNode n;
        try {
            n = mapper.readTree(json);
        } catch (Exception e) {
            throw new LlmException("LLM response is not valid JSON", e);
        }
        if (n == null || !n.isObject()) throw new LlmException("LLM response is not a JSON object");
        String slotId = text(n, "slotId");
        String reasoning = text(n, "reasoning");
        if (slotId == null) throw new LlmException("LLM response missing slotId");
        if (reasoning == null) throw new LlmException("LLM response missing reasoning");
        String alt = text(n, "alternateProviderId");
        if (alt != null && (alt.equalsIgnoreCase("null") || alt.equalsIgnoreCase("none"))) alt = null;
        double confidence = 0.7;
        JsonNode c = n.get("confidence");
        if (c != null && c.isNumber()) confidence = c.asDouble();
        else if (c != null && c.isTextual()) {
            try { confidence = Double.parseDouble(c.asText().trim()); } catch (NumberFormatException ignored) { }
        }
        if (Double.isNaN(confidence)) confidence = 0.7;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        return new SlotRecommendation(slotId.trim(), alt == null ? null : alt.trim(), confidence, reasoning.trim());
    }

    static String extractJson(String raw) {
        String s = raw.trim();
        // drop <think>...</think> blocks some reasoning models emit
        s = s.replaceAll("(?s)<think>.*?</think>", "").trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int bodyStart = s.indexOf('\n', fence);
            int end = bodyStart < 0 ? -1 : s.indexOf("```", bodyStart);
            // Only trust the fenced body if it actually holds JSON: a model may answer with bare JSON and then add a
            // fenced note after it, which must not hide the real answer.
            if (bodyStart >= 0 && end > bodyStart) {
                String body = s.substring(bodyStart + 1, end).trim();
                if (body.indexOf('{') >= 0) s = body;
            }
        }
        int start = s.indexOf('{');
        if (start < 0) throw new LlmException("No JSON object in LLM response");
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
            } else if (ch == '"') inStr = true;
            else if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return s.substring(start, i + 1);
        }
        throw new LlmException("Unterminated JSON object in LLM response");
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String t = v.asText();
        return t == null || t.isBlank() ? null : t;
    }
}
