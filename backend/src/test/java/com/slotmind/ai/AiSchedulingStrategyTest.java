package com.slotmind.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotmind.domain.TriggerReason;
import com.slotmind.scheduling.SlotRecommendation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSchedulingStrategyTest {
    private final AiTestFixtures f = new AiTestFixtures();
    private final PromptFactory prompts = new PromptFactory();
    private final AiResponseParser parser = new AiResponseParser();

    private AiSchedulingStrategy with(LlmGateway g) { return new AiSchedulingStrategy(g, prompts, parser); }

    @Test
    void emptyCandidatesReturnsEmptyWithoutCallingLlm() {
        LlmGateway boom = (s, u) -> { throw new AssertionError("should not be called"); };
        assertThat(with(boom).recommend(f.ctx(TriggerReason.SLOT_CANCELLED, List.of()))).isEmpty();
    }

    @Test
    void returnsParsedRecommendationAndDerivesAlternateProvider() {
        LlmGateway fake = (s, u) -> "```json\n{\"slotId\":\"SLT-007\",\"alternateProviderId\":null,\"confidence\":0.8,\"reasoning\":\"Your appointment is now with Dr. Chen at 11:00 AM.\"}\n```";
        List<SlotRecommendation> out = with(fake).recommend(f.ctx(TriggerReason.PROVIDER_OFFLINE, List.of(f.patelLater, f.chenSoon)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).slotId()).isEqualTo("SLT-007");
        assertThat(out.get(0).alternateProviderId()).isEqualTo("PRV-002");
    }

    @Test
    void throwsWhenModelPicksUnknownSlot() {
        LlmGateway fake = (s, u) -> "{\"slotId\":\"SLT-999\",\"confidence\":0.8,\"reasoning\":\"Your appointment moved.\"}";
        assertThatThrownBy(() -> with(fake).recommend(f.ctx(TriggerReason.SLOT_CANCELLED, List.of(f.patelLater))))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void throwsWhenReasoningLeaksIds() {
        LlmGateway fake = (s, u) -> "{\"slotId\":\"SLT-005\",\"confidence\":0.8,\"reasoning\":\"Your appointment is SLT-005.\"}";
        assertThatThrownBy(() -> with(fake).recommend(f.ctx(TriggerReason.SLOT_CANCELLED, List.of(f.patelLater))))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void propagatesGatewayFailure() {
        LlmGateway fake = (s, u) -> { throw new LlmException("timeout"); };
        assertThatThrownBy(() -> with(fake).recommend(f.ctx(TriggerReason.SLOT_CANCELLED, List.of(f.patelLater))))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void mockGatewayIsDeterministicAndValid() {
        AiSchedulingStrategy s = with(new MockLlmGateway(new ObjectMapper()));
        // cancelled: same doctor first
        List<SlotRecommendation> c = s.recommend(f.ctx(TriggerReason.SLOT_CANCELLED, List.of(f.chenSoon, f.patelLater)));
        assertThat(c.get(0).slotId()).isEqualTo("SLT-005");
        assertThat(c.get(0).alternateProviderId()).isNull();
        // offline: Patel's own slots would have been filtered by core; only Chen remains
        List<SlotRecommendation> o = s.recommend(f.ctx(TriggerReason.PROVIDER_OFFLINE, List.of(f.chenSoon)));
        assertThat(o.get(0).slotId()).isEqualTo("SLT-007");
        assertThat(o.get(0).alternateProviderId()).isEqualTo("PRV-002");
        assertThat(o.get(0).reasoning()).doesNotContain("SLT-").doesNotContain("PRV-");
    }
}
