package com.slotmind.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bean selection + property binding, with a minimal context (no web/JPA). */
class AiConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfig.class)
            .withBean(ObjectMapper.class);

    @Test
    void noPropertiesAtAllMeansAiUnavailableNotMock() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(LlmGateway.class);
            assertThat(ctx.getBean(LlmGateway.class)).isInstanceOf(UnavailableLlmGateway.class);
            AiProperties p = ctx.getBean(AiProperties.class);
            assertThat(p.provider()).isEqualTo("litellm");
            assertThat(p.baseUrl()).isEqualTo("https://litellm-qc.zycus.net/v1");
            assertThat(p.model()).isEqualTo("qwen-cursor");
            assertThat(p.headers()).isEmpty();
            assertThat(p.timeoutSeconds()).isEqualTo(15);
        });
    }

    @Test
    void providerMockWinsEvenWithKey() {
        runner.withPropertyValues("slotmind.ai.provider=MOCK", "slotmind.ai.api-key=sk-real")
                .run(ctx -> assertThat(ctx.getBean(LlmGateway.class)).isInstanceOf(MockLlmGateway.class));
    }

    @Test
    void realProviderWithBlankKeyIsUnavailableNotMock() {
        runner.withPropertyValues("slotmind.ai.provider=litellm", "slotmind.ai.api-key=")
                .run(ctx -> assertThat(ctx.getBean(LlmGateway.class)).isInstanceOf(UnavailableLlmGateway.class));
        runner.withPropertyValues("slotmind.ai.provider=groq", "slotmind.ai.api-key=   ")
                .run(ctx -> assertThat(ctx.getBean(LlmGateway.class)).isInstanceOf(UnavailableLlmGateway.class));
        runner.withPropertyValues("slotmind.ai.provider=litellm")
                .run(ctx -> assertThat(ctx.getBean(LlmGateway.class)).isInstanceOf(UnavailableLlmGateway.class));
    }

    @Test
    void unavailableGatewayAlwaysThrowsSoTheEngineFallsBack() {
        UnavailableLlmGateway g = new UnavailableLlmGateway("no API key configured");
        assertThatThrownBy(() -> g.callLLM("s", "u")).isInstanceOf(LlmException.class)
                .hasMessage("AI unavailable: no API key configured");
        assertThat(g.reason()).isEqualTo("no API key configured");
    }

    @Test
    void realProviderWithKeyUsesHttpGatewayAndBindsHeaders() {
        runner.withPropertyValues("slotmind.ai.provider=litellm", "slotmind.ai.api-key=sk-real",
                        "slotmind.ai.base-url=http://localhost:1/v1", "slotmind.ai.model=m1",
                        "slotmind.ai.headers.product=PC1", "slotmind.ai.timeout-seconds=7")
                .run(ctx -> {
                    assertThat(ctx.getBean(LlmGateway.class)).isInstanceOf(OpenAiCompatibleLlmGateway.class);
                    AiProperties p = ctx.getBean(AiProperties.class);
                    assertThat(p.headers()).containsEntry("product", "PC1");
                    assertThat(p.baseUrl()).isEqualTo("http://localhost:1/v1");
                    assertThat(p.model()).isEqualTo("m1");
                    assertThat(p.timeoutSeconds()).isEqualTo(7);
                });
    }

    @Test
    void directFactoryCall() {
        AiConfig cfg = new AiConfig();
        ObjectMapper m = new ObjectMapper();
        assertThat(cfg.llmGateway(new AiProperties("mock", null, null, "k", null, null), m)).isInstanceOf(MockLlmGateway.class);
        assertThat(cfg.llmGateway(new AiProperties("litellm", null, null, null, null, null), m)).isInstanceOf(UnavailableLlmGateway.class);
        assertThat(cfg.llmGateway(new AiProperties("litellm", null, null, "k", null, null), m)).isInstanceOf(OpenAiCompatibleLlmGateway.class);
    }

    @Test
    void propertiesDefaultsAndPredicates() {
        AiProperties blank = new AiProperties(" ", "", "", null, null, 0);
        assertThat(blank.provider()).isEqualTo("litellm");
        assertThat(blank.isMock()).isFalse();
        assertThat(blank.baseUrl()).isEqualTo("https://litellm-qc.zycus.net/v1");
        assertThat(blank.model()).isEqualTo("qwen-cursor");
        assertThat(blank.headers()).isEmpty();
        assertThat(blank.timeoutSeconds()).isEqualTo(15);
        assertThat(blank.hasKey()).isFalse();
        assertThat(new AiProperties("x", null, null, null, null, -3).timeoutSeconds()).isEqualTo(15);

        AiProperties real = new AiProperties(" Mock ", "u", "m", "k", Map.of("a", "b"), 3);
        assertThat(real.isMock()).isTrue();
        assertThat(real.hasKey()).isTrue();
        assertThat(real.timeoutSeconds()).isEqualTo(3);
        assertThat(new AiProperties("litellm", null, null, " ", null, null).hasKey()).isFalse();
        assertThat(new AiProperties("litellm", null, null, "k", null, null).isMock()).isFalse();
    }
}
