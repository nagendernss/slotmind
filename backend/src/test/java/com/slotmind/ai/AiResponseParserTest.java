package com.slotmind.ai;

import com.slotmind.scheduling.SlotRecommendation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiResponseParserTest {
    private final AiResponseParser parser = new AiResponseParser();

    @Test
    void parsesPlainJson() {
        SlotRecommendation r = parser.parse("{\"slotId\":\"SLT-005\",\"alternateProviderId\":null,\"confidence\":0.9,\"reasoning\":\"Your visit moves.\"}");
        assertThat(r.slotId()).isEqualTo("SLT-005");
        assertThat(r.alternateProviderId()).isNull();
        assertThat(r.confidence()).isEqualTo(0.9);
    }

    @Test
    void parsesFencedJsonWithChatter() {
        String raw = "Sure! Here is my pick:\n```json\n{\"slotId\":\"SLT-007\",\"alternateProviderId\":\"PRV-002\",\"confidence\":1.4,\"reasoning\":\"Your appointment {moved}.\"}\n```\nHope that helps";
        SlotRecommendation r = parser.parse(raw);
        assertThat(r.slotId()).isEqualTo("SLT-007");
        assertThat(r.alternateProviderId()).isEqualTo("PRV-002");
        assertThat(r.confidence()).isEqualTo(1.0);
        assertThat(r.reasoning()).isEqualTo("Your appointment {moved}.");
    }

    @Test
    void stripsThinkBlocksAndStringNull() {
        SlotRecommendation r = parser.parse("<think>{hmm}</think>{\"slotId\":\"SLT-1\",\"alternateProviderId\":\"null\",\"confidence\":\"0.6\",\"reasoning\":\"ok\"}");
        assertThat(r.alternateProviderId()).isNull();
        assertThat(r.confidence()).isEqualTo(0.6);
    }

    @Test
    void throwsOnGarbage() {
        assertThatThrownBy(() -> parser.parse("I cannot help with that")).isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> parser.parse("{\"slotId\": ")).isInstanceOf(LlmException.class);
    }

    @Test
    void throwsOnMissingFields() {
        assertThatThrownBy(() -> parser.parse("{\"reasoning\":\"x\"}")).isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> parser.parse("{\"slotId\":\"SLT-1\"}")).isInstanceOf(LlmException.class);
    }
}
