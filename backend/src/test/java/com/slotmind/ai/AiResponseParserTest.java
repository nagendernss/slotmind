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

    // ---------------------------------------------------------------- edge cases

    @Test
    void confidenceDefaultsAndClamping() {
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\"}").confidence()).as("missing").isEqualTo(0.7);
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\",\"confidence\":null}").confidence()).isEqualTo(0.7);
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\",\"confidence\":\"high\"}").confidence()).isEqualTo(0.7);
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\",\"confidence\":\"NaN\"}").confidence()).isEqualTo(0.7);
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\",\"confidence\":true}").confidence()).isEqualTo(0.7);
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\",\"confidence\":-2}").confidence()).isEqualTo(0.0);
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\",\"confidence\":\" 0.35 \"}").confidence()).isEqualTo(0.35);
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\",\"confidence\":1}").confidence()).isEqualTo(1.0);
    }

    @Test
    void alternateProviderNormalisation() {
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\",\"alternateProviderId\":\"None\"}").alternateProviderId()).isNull();
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\",\"alternateProviderId\":\"NULL\"}").alternateProviderId()).isNull();
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\",\"alternateProviderId\":\"  \"}").alternateProviderId()).isNull();
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\"}").alternateProviderId()).isNull();
        assertThat(parser.parse("{\"slotId\":\"S\",\"reasoning\":\"r\",\"alternateProviderId\":\" PRV-002 \"}").alternateProviderId()).isEqualTo("PRV-002");
    }

    @Test
    void valuesAreTrimmedAndCoercedToText() {
        SlotRecommendation r = parser.parse("{\"slotId\":\"  SLT-005 \",\"reasoning\":\"  Hi there.  \"}");
        assertThat(r.slotId()).isEqualTo("SLT-005");
        assertThat(r.reasoning()).isEqualTo("Hi there.");
        assertThat(parser.parse("{\"slotId\":5,\"reasoning\":\"r\"}").slotId()).isEqualTo("5");
    }

    @Test
    void blankOrNonTextRequiredFieldsAreMissing() {
        assertThatThrownBy(() -> parser.parse("{\"slotId\":\" \",\"reasoning\":\"r\"}")).isInstanceOf(LlmException.class).hasMessageContaining("slotId");
        assertThatThrownBy(() -> parser.parse("{\"slotId\":null,\"reasoning\":\"r\"}")).isInstanceOf(LlmException.class).hasMessageContaining("slotId");
        assertThatThrownBy(() -> parser.parse("{\"slotId\":\"S\",\"reasoning\":\"\"}")).isInstanceOf(LlmException.class).hasMessageContaining("reasoning");
        assertThatThrownBy(() -> parser.parse("{\"slotId\":\"S\",\"reasoning\":{\"a\":1}}")).isInstanceOf(LlmException.class).hasMessageContaining("reasoning");
    }

    @Test
    void bracesAndEscapedQuotesInsideStrings() {
        String raw = "{\"slotId\":\"S\",\"reasoning\":\"Say \\\"}{\\\" and \\\\ ok\"} trailing } junk";
        SlotRecommendation r = parser.parse(raw);
        assertThat(r.reasoning()).isEqualTo("Say \"}{\" and \\ ok");
    }

    @Test
    void firstObjectWinsAndNestedObjectsAreKept() {
        SlotRecommendation r = parser.parse("pick: {\"slotId\":\"A\",\"meta\":{\"x\":{}},\"reasoning\":\"r\"} or {\"slotId\":\"B\",\"reasoning\":\"r\"}");
        assertThat(r.slotId()).isEqualTo("A");
    }

    @Test
    void objectInsideArrayIsExtracted() {
        assertThat(parser.parse("[{\"slotId\":\"A\",\"reasoning\":\"r\"}]").slotId()).isEqualTo("A");
    }

    @Test
    void fenceWithoutNewlineFallsBackToBraceScan() {
        assertThat(parser.parse("```{\"slotId\":\"A\",\"reasoning\":\"r\"}```").slotId()).isEqualTo("A");
    }

    @Test
    void unclosedFenceStillParses() {
        assertThat(parser.parse("```json\n{\"slotId\":\"A\",\"reasoning\":\"r\"}").slotId()).isEqualTo("A");
    }

    /** Regression: bare JSON followed by a fenced note used to throw "No JSON object" because only the fence body was scanned. */
    @Test
    void jsonBeforeANonJsonFenceIsFound() {
        String raw = "{\"slotId\":\"A\",\"reasoning\":\"r\"}\nNote:\n```\nfirst open afternoon slot\n```";
        assertThat(parser.parse(raw).slotId()).isEqualTo("A");
    }

    @Test
    void multilineThinkBlockIsDropped() {
        String raw = "<think>\nmaybe {\"slotId\":\"WRONG\",\"reasoning\":\"x\"}\n</think>\n{\"slotId\":\"RIGHT\",\"reasoning\":\"r\"}";
        assertThat(parser.parse(raw).slotId()).isEqualTo("RIGHT");
    }

    @Test
    void structuralFailures() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(LlmException.class).hasMessage("Empty LLM response");
        assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(LlmException.class).hasMessage("Empty LLM response");
        assertThatThrownBy(() -> parser.parse("\"just a string\"")).isInstanceOf(LlmException.class).hasMessageContaining("No JSON object");
        assertThatThrownBy(() -> parser.parse("{\"slotId\":\"A\"")).isInstanceOf(LlmException.class).hasMessageContaining("Unterminated");
        assertThatThrownBy(() -> parser.parse("{slotId: A, reasoning: r}")).isInstanceOf(LlmException.class)
                .hasMessageContaining("not valid JSON").hasCauseInstanceOf(Exception.class);
    }

    @Test
    void extractJsonDirectly() {
        assertThat(AiResponseParser.extractJson("  x {\"a\":{\"b\":1}} y ")).isEqualTo("{\"a\":{\"b\":1}}");
        assertThatThrownBy(() -> AiResponseParser.extractJson("{")).isInstanceOf(LlmException.class);
    }

    @Test
    void customMapperConstructor() {
        AiResponseParser p = new AiResponseParser(new com.fasterxml.jackson.databind.ObjectMapper());
        assertThat(p.parse("{\"slotId\":\"A\",\"reasoning\":\"r\"}").slotId()).isEqualTo("A");
    }

    @Test
    void throwsOnMissingFields() {
        assertThatThrownBy(() -> parser.parse("{\"reasoning\":\"x\"}")).isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> parser.parse("{\"slotId\":\"SLT-1\"}")).isInstanceOf(LlmException.class);
    }
}
