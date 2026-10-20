package com.slotmind.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates exactly ONE {@link LlmGateway}: real proxy client, or the deterministic mock (provider=mock or no key). */
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
            log.warn("SlotMind AI: provider '{}' configured but slotmind.ai.api-key is blank -> falling back to MockLlmGateway",
                    props.provider());
            return new MockLlmGateway(mapper);
        }
        log.info("SlotMind AI: using OpenAI-compatible gateway provider={} model={} baseUrl={}",
                props.provider(), props.model(), props.baseUrl());
        return new OpenAiCompatibleLlmGateway(props, mapper);
    }
}
