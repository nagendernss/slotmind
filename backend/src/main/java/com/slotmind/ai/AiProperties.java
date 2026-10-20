package com.slotmind.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/** Binds {@code slotmind.ai.*}. The api key comes from env SLOTMIND_AI_API_KEY or the git-ignored local profile. */
@ConfigurationProperties(prefix = "slotmind.ai")
public record AiProperties(String provider, String baseUrl, String model, String apiKey,
                           Map<String, String> headers, Integer timeoutSeconds) {
    public AiProperties {
        if (provider == null || provider.isBlank()) provider = "mock";
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://litellm-qc.zycus.net/v1";
        if (model == null || model.isBlank()) model = "qwen-cursor";
        if (headers == null) headers = Map.of();
        if (timeoutSeconds == null || timeoutSeconds <= 0) timeoutSeconds = 15;
    }

    public boolean isMock() { return "mock".equalsIgnoreCase(provider.trim()); }

    public boolean hasKey() { return apiKey != null && !apiKey.isBlank(); }
}
