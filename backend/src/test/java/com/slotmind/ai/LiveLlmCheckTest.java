package com.slotmind.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotmind.domain.TriggerReason;
import com.slotmind.scheduling.SlotRecommendation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real proxy round-trip. Disabled by default; run with {@code mvn test -Dtest=LiveLlmCheckTest -Dlive=true}.
 * Key comes from env SLOTMIND_AI_API_KEY or the git-ignored application-local.properties. Never printed.
 */
@EnabledIfSystemProperty(named = "live", matches = "true")
class LiveLlmCheckTest {

    @Test
    void realProxyReturnsParseableRecommendation() throws Exception {
        Properties local = new Properties();
        Path p = Path.of("src/main/resources/application-local.properties");
        if (Files.exists(p)) try (InputStream in = Files.newInputStream(p)) { local.load(in); }

        String key = System.getenv("SLOTMIND_AI_API_KEY");
        if (key == null || key.isBlank()) key = local.getProperty("slotmind.ai.api-key");
        assertThat(key).as("api key present (env or local properties)").isNotBlank();

        Map<String, String> headers = new HashMap<>();
        local.stringPropertyNames().stream().filter(k -> k.startsWith("slotmind.ai.headers."))
                .forEach(k -> headers.put(k.substring("slotmind.ai.headers.".length()), local.getProperty(k)));
        headers.putIfAbsent("product", "PC1");

        AiProperties props = new AiProperties("litellm", local.getProperty("slotmind.ai.base-url"),
                local.getProperty("slotmind.ai.model"), key, headers, 30);
        ObjectMapper mapper = new ObjectMapper();
        AiTestFixtures f = new AiTestFixtures();
        AiSchedulingStrategy strategy = new AiSchedulingStrategy(new OpenAiCompatibleLlmGateway(props, mapper),
                new PromptFactory(), new AiResponseParser(mapper));

        for (TriggerReason t : List.of(TriggerReason.PROVIDER_OFFLINE, TriggerReason.SLOT_CANCELLED)) {
            var cands = t == TriggerReason.PROVIDER_OFFLINE ? List.of(f.chenSoon) : List.of(f.chenSoon, f.patelLater);
            long t0 = System.currentTimeMillis();
            List<SlotRecommendation> out = strategy.recommend(f.ctx(t, cands));
            long ms = System.currentTimeMillis() - t0;
            System.out.println("LIVE " + t + " " + ms + " ms -> " + out.get(0));
            assertThat(out).hasSize(1);
        }
    }
}
