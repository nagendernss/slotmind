package com.slotmind.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates exactly ONE {@link LlmGateway}: the real OpenAI-compatible client; the deterministic mock only when
 * explicitly configured ({@code slotmind.ai.provider=mock}, used by tests); otherwise, with no API key, an
 * "unavailable" gateway so suggestions honestly fall back to rule-based.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {
    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public LlmGateway llmGateway(AiProperties props, ObjectMapper mapper) {
        if (props.isMock()) {
            log.info("SlotMind AI: using MockLlmGateway (slotmind.ai.provider=mock)");
            return new MockLlmGateway(mapper);
        }
        if (!props.hasKey()) {
            log.warn("SlotMind AI: provider '{}' configured but no API key (SLOTMIND_AI_API_KEY / slotmind.ai.api-key) "
                    + "-> AI unavailable, suggestions use the rule-based fallback", props.provider());
            return new UnavailableLlmGateway("no API key configured");
        }
        log.info("SlotMind AI: using OpenAI-compatible gateway provider={} model={} baseUrl={}",
                props.provider(), props.model(), props.baseUrl());
        return new OpenAiCompatibleLlmGateway(props, mapper);
    }
}
