package com.slotmind.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotmind.domain.TriggerReason;
import com.slotmind.scheduling.SlotRecommendation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockLlmGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockLlmGateway mock = new MockLlmGateway(mapper);

    @Test
    void picksFirstCandidateAndKeepsSameDoctor() throws Exception {
        String prompt = "blah\nORIGINAL_PROVIDER_ID: PRV-001\nSLOT_PROVIDER: SLT-005=PRV-001\nSLOT_PROVIDER: SLT-007=PRV-002\n"
                + "CANDIDATE_IDS: SLT-005, SLT-007\n";
        JsonNode out = mapper.readTree(mock.callLLM("sys", prompt));
        assertThat(out.path("slotId").asText()).isEqualTo("SLT-005");
        assertThat(out.path("alternateProviderId").isNull()).isTrue();
        assertThat(out.path("confidence").asDouble()).isEqualTo(0.8);
        assertThat(out.path("reasoning").asText()).contains("same doctor").doesNotContain("SLT-").doesNotContain("PRV-");
    }

    @Test
    void namesAlternateWhenFirstCandidateIsAnotherDoctor() throws Exception {
        String prompt = "ORIGINAL_PROVIDER_ID: PRV-001\nSLOT_PROVIDER: SLT-005=PRV-001\nSLOT_PROVIDER: SLT-007=PRV-002\n"
                + "CANDIDATE_IDS: SLT-007,SLT-005\n";
        JsonNode out = mapper.readTree(mock.callLLM("sys", prompt));
        assertThat(out.path("slotId").asText()).isEqualTo("SLT-007");
        assertThat(out.path("alternateProviderId").asText()).isEqualTo("PRV-002");
        assertThat(out.path("reasoning").asText()).contains("another doctor");
    }

    @Test
    void unknownProvidersMeanNoAlternate() throws Exception {
        JsonNode noOriginal = mapper.readTree(mock.callLLM("s", "SLOT_PROVIDER: SLT-7=PRV-2\nCANDIDATE_IDS: SLT-7\n"));
        assertThat(noOriginal.path("alternateProviderId").isNull()).isTrue();
        JsonNode noSlotProvider = mapper.readTree(mock.callLLM("s", "ORIGINAL_PROVIDER_ID: PRV-1\nCANDIDATE_IDS: SLT-7\n"));
        assertThat(noSlotProvider.path("alternateProviderId").isNull()).isTrue();
    }

    @Test
    void failsWithoutCandidateLine() {
        assertThatThrownBy(() -> mock.callLLM("s", "no ids here")).isInstanceOf(LlmException.class).hasMessageContaining("CANDIDATE_IDS");
        assertThatThrownBy(() -> mock.callLLM("s", "CANDIDATE_IDS:   \n")).isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> mock.callLLM("s", "x CANDIDATE_IDS: SLT-1")).as("must start a line").isInstanceOf(LlmException.class);
    }

    @Test
    void isDeterministic() {
        String prompt = "CANDIDATE_IDS: SLT-1,SLT-2\n";
        assertThat(mock.callLLM("a", prompt)).isEqualTo(mock.callLLM("b", prompt));
    }

    @Test
    void outputParsesAgainstRealPromptForEveryTrigger() {
        AiTestFixtures f = new AiTestFixtures();
        PromptFactory prompts = new PromptFactory();
        AiResponseParser parser = new AiResponseParser(mapper);
        for (TriggerReason t : TriggerReason.values()) {
            String user = prompts.build(f.ctx(t, List.of(f.chenSoon, f.patelLater))).user();
            SlotRecommendation r = parser.parse(mock.callLLM(PromptFactory.SYSTEM_PROMPT, user));
            // cancelled/initial: same doctor first; offline: both GENERAL, afternoon preference → Patel 2:30 PM first too
            assertThat(r.slotId()).as(t.name()).isEqualTo("SLT-005");
            assertThat(r.reasoning()).isNotBlank();
        }
    }
}
