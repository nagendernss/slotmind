package com.slotmind.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic offline gateway: picks the first id on the {@code CANDIDATE_IDS:} line of the user prompt and
 * answers with valid JSON, so the demo never depends on wifi or an API key.
 */
public class MockLlmGateway implements LlmGateway {
    private static final Pattern IDS = Pattern.compile("(?m)^CANDIDATE_IDS:[ \\t]*(.*)$");
    private static final Pattern ORIGINAL_PROVIDER = Pattern.compile("(?m)^ORIGINAL_PROVIDER_ID:[ \\t]*(\\S+)");
    private static final Pattern SLOT_PROVIDER = Pattern.compile("(?m)^SLOT_PROVIDER:[ \\t]*(\\S+)=(\\S+)");

    private final ObjectMapper mapper;

    public MockLlmGateway(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public String callLLM(String systemPrompt, String userPrompt) {
        Matcher m = IDS.matcher(userPrompt);
        if (!m.find() || m.group(1).isBlank()) throw new LlmException("Mock LLM: no CANDIDATE_IDS line in prompt");
        String slotId = m.group(1).split(",")[0].trim();

        Matcher om = ORIGINAL_PROVIDER.matcher(userPrompt);
        String original = om.find() ? om.group(1) : null;
        String slotProvider = null;
        Matcher pm = SLOT_PROVIDER.matcher(userPrompt);
        while (pm.find()) {
            if (pm.group(1).equals(slotId)) { slotProvider = pm.group(2); break; }
        }
        boolean switched = slotProvider != null && original != null && !slotProvider.equals(original);

        ObjectNode out = mapper.createObjectNode();
        out.put("slotId", slotId);
        if (switched) out.put("alternateProviderId", slotProvider); else out.putNull("alternateProviderId");
        out.put("confidence", 0.8);
        out.put("reasoning", switched
                ? "Your usual doctor is not available, so we found you the earliest opening with another doctor of the same kind. It is the soonest time we have that fits your visit."
                : "We found the earliest opening with your same doctor, so you keep seeing someone who knows you. It is the soonest time that fits your visit.");
        return out.toString();
    }
}
