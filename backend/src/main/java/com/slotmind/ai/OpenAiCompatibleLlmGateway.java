package com.slotmind.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** POST {base-url}/chat/completions against any OpenAI-compatible endpoint (LiteLLM, Groq, Gemini, Ollama). */
public class OpenAiCompatibleLlmGateway implements LlmGateway {
    private final AiProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiCompatibleLlmGateway(AiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.timeoutSeconds()))
                .build();
    }

    @Override
    public String callLLM(String systemPrompt, String userPrompt) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.model());
            body.put("temperature", 0.2);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            String base = props.baseUrl().endsWith("/")
                    ? props.baseUrl().substring(0, props.baseUrl().length() - 1) : props.baseUrl();
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            for (Map.Entry<String, String> h : props.headers().entrySet()) {
                req.header(h.getKey(), h.getValue());
            }

            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                String b = resp.body() == null ? "" : resp.body();
                throw new LlmException("LLM HTTP " + resp.statusCode() + ": " + b.substring(0, Math.min(300, b.length())));
            }
            JsonNode content = mapper.readTree(resp.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new LlmException("LLM response has no choices[0].message.content");
            }
            return content.asText();
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM call interrupted", e);
        } catch (Exception e) {
            throw new LlmException("LLM call failed: " + e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }
    }
}
