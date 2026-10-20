package com.slotmind.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Talks to a local JDK HttpServer stub — never to a real LLM. */
class OpenAiCompatibleLlmGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService pool;
    private final CountDownLatch release = new CountDownLatch(1);

    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<Headers> headers = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    /** What the stub replies with. */
    private volatile int status = 200;
    private volatile String reply = completion("{\"slotId\":\"SLT-005\"}");
    private volatile long delayMs = 0;

    static String completion(String content) {
        return "{\"id\":\"x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                + new ObjectMapper().valueToTree(content) + "}}]}";
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        method.set(ex.getRequestMethod());
        path.set(ex.getRequestURI().getPath());
        headers.set(ex.getRequestHeaders());
        body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (delayMs > 0) {
            try { release.await(delayMs, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        } catch (IOException ignored) {
            // client already gave up (timeout test)
        } finally {
            ex.close();
        }
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

    private OpenAiCompatibleLlmGateway gateway(String baseUrl, Map<String, String> extraHeaders, int timeoutSeconds) {
        return new OpenAiCompatibleLlmGateway(
                new AiProperties("litellm", baseUrl, "qwen-cursor", "sk-test-123", extraHeaders, timeoutSeconds), mapper);
    }

    private OpenAiCompatibleLlmGateway gateway() { return gateway(baseUrl(), Map.of("product", "PC1"), 5); }

    @Test
    void postsChatCompletionWithAuthProductHeaderAndMessages() throws Exception {
        String out = gateway().callLLM("SYSTEM PROMPT", "USER PROMPT");

        assertThat(out).isEqualTo("{\"slotId\":\"SLT-005\"}");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/v1/chat/completions");
        Headers h = headers.get();
        assertThat(h.getFirst("Authorization")).isEqualTo("Bearer sk-test-123");
        assertThat(h.getFirst("product")).isEqualTo("PC1");
        assertThat(h.getFirst("Content-Type")).isEqualTo("application/json");

        JsonNode sent = mapper.readTree(body.get());
        assertThat(sent.path("model").asText()).isEqualTo("qwen-cursor");
        assertThat(sent.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(sent.path("messages")).hasSize(2);
        assertThat(sent.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(sent.path("messages").path(0).path("content").asText()).isEqualTo("SYSTEM PROMPT");
        assertThat(sent.path("messages").path(1).path("role").asText()).isEqualTo("user");
        assertThat(sent.path("messages").path(1).path("content").asText()).isEqualTo("USER PROMPT");
    }

    @Test
    void sendsEveryConfiguredExtraHeader() {
        gateway(baseUrl(), Map.of("product", "PC1", "X-Team", "slotmind"), 5).callLLM("s", "u");
        assertThat(headers.get().getFirst("X-Team")).isEqualTo("slotmind");
        assertThat(headers.get().getFirst("product")).isEqualTo("PC1");
    }

    @Test
    void noExtraHeadersWhenNoneConfigured() {
        gateway(baseUrl(), null, 5).callLLM("s", "u");
        assertThat(headers.get().containsKey("product")).isFalse();
        assertThat(headers.get().getFirst("Authorization")).isEqualTo("Bearer sk-test-123");
    }

    @Test
    void trailingSlashInBaseUrlIsTolerated() {
        gateway(baseUrl() + "/", Map.of(), 5).callLLM("s", "u");
        assertThat(path.get()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void unicodeRoundTrips() throws Exception {
        reply = completion("Dr. Müller – ¿qué tal? ✓");
        assertThat(gateway().callLLM("s", "Café ✓")).isEqualTo("Dr. Müller – ¿qué tal? ✓");
        assertThat(mapper.readTree(body.get()).path("messages").path(1).path("content").asText()).isEqualTo("Café ✓");
    }

    @Test
    void non2xxBecomesLlmExceptionWithTruncatedBody() {
        status = 500;
        reply = "E".repeat(1000);
        assertThatThrownBy(() -> gateway().callLLM("s", "u")).isInstanceOf(LlmException.class)
                .hasMessage("LLM HTTP 500: " + "E".repeat(300));
    }

    @Test
    void unauthorizedWithShortBody() {
        status = 401;
        reply = "{\"error\":\"bad key\"}";
        assertThatThrownBy(() -> gateway().callLLM("s", "u")).isInstanceOf(LlmException.class)
                .hasMessage("LLM HTTP 401: {\"error\":\"bad key\"}");
    }

    @Test
    void non2xxWithEmptyBody() {
        status = 503;
        reply = "";
        assertThatThrownBy(() -> gateway().callLLM("s", "u")).isInstanceOf(LlmException.class).hasMessage("LLM HTTP 503: ");
    }

    @Test
    void blankContentIsAnError() {
        reply = completion("   ");
        assertThatThrownBy(() -> gateway().callLLM("s", "u")).isInstanceOf(LlmException.class)
                .hasMessageContaining("no choices[0].message.content");
    }

    @Test
    void missingChoicesOrNonTextContentIsAnError() {
        reply = "{\"choices\":[]}";
        assertThatThrownBy(() -> gateway().callLLM("s", "u")).isInstanceOf(LlmException.class)
                .hasMessageContaining("no choices[0].message.content");
        reply = "{\"choices\":[{\"message\":{\"content\":null}}]}";
        assertThatThrownBy(() -> gateway().callLLM("s", "u")).isInstanceOf(LlmException.class);
        reply = "{\"choices\":[{\"message\":{\"content\":42}}]}";
        assertThatThrownBy(() -> gateway().callLLM("s", "u")).isInstanceOf(LlmException.class);
    }

    @Test
    void nonJsonSuccessBodyIsWrapped() {
        reply = "<html>gateway</html>";
        assertThatThrownBy(() -> gateway().callLLM("s", "u")).isInstanceOf(LlmException.class)
                .hasMessageStartingWith("LLM call failed:").hasCauseInstanceOf(Exception.class);
    }

    @Test
    void slowServerTimesOut() {
        delayMs = 10_000;
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> gateway(baseUrl(), Map.of(), 1).callLLM("s", "u"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("HttpTimeoutException")
                .hasCauseInstanceOf(HttpTimeoutException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(8_000);
    }

    @Test
    void connectionRefusedIsWrapped() throws IOException {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) { freePort = s.getLocalPort(); }
        assertThatThrownBy(() -> gateway("http://127.0.0.1:" + freePort + "/v1", Map.of(), 2).callLLM("s", "u"))
                .isInstanceOf(LlmException.class).hasMessageStartingWith("LLM call failed:");
    }

    @Test
    void interruptedCallRestoresInterruptFlag() {
        delayMs = 5_000;
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> gateway().callLLM("s", "u")).isInstanceOf(LlmException.class)
                    .hasMessage("LLM call interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear for the next test
        }
    }
}
