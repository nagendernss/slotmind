package com.slotmind.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.slotmind.domain.SuggestionStatus;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Bonus SSE endpoint over a real HTTP connection (random port): status -> token... -> suggestion. */
class SuggestStreamSseTest extends IntegrationTestBase {

    record Event(String name, String data) {}

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> stream(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Minimal SSE parser: "event:" / "data:" lines, blank line ends an event; multi-line data joined with \n. */
    static List<Event> parse(String body) {
        List<Event> out = new ArrayList<>();
        String name = null;
        List<String> data = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            line = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (line.isEmpty()) {
                if (name != null || !data.isEmpty()) out.add(new Event(name, String.join("\n", data)));
                name = null;
                data = new ArrayList<>();
            } else if (line.startsWith("event:")) {
                name = line.substring("event:".length());
            } else if (line.startsWith("data:")) {
                data.add(line.substring("data:".length()));
            }
        }
        if (name != null || !data.isEmpty()) out.add(new Event(name, String.join("\n", data)));
        return out;
    }

    @Test
    void streamsStatusThenTokensThenTheSavedSuggestion() throws Exception {
        World w = fx.world();
        HttpResponse<String> res = stream("/appointments/" + w.appt().getId() + "/suggest/stream?strategy=RULE_BASED");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");

        List<Event> events = parse(res.body());
        List<String> names = events.stream().map(Event::name).toList();
        assertThat(names.get(0)).isEqualTo("status");
        assertThat(names.get(names.size() - 1)).isEqualTo("suggestion");
        assertThat(names).doesNotContain("error");
        int firstToken = names.indexOf("token");
        int lastStatus = names.lastIndexOf("status");
        assertThat(firstToken).isPositive();
        assertThat(lastStatus).isLessThan(firstToken); // all status events come before the tokens
        assertThat(names.subList(firstToken, names.size() - 1)).allMatch("token"::equals);
        assertThat(names.stream().filter("status"::equals).count()).isGreaterThanOrEqualTo(3);

        JsonNode suggestion = json.readTree(events.get(events.size() - 1).data());
        assertThat(suggestion.get("appointmentId").asText()).isEqualTo(w.appt().getId());
        assertThat(suggestion.get("triggerReason").asText()).isEqualTo("INITIAL");
        assertThat(suggestion.get("strategy").asText()).isEqualTo("RULE_BASED");
        assertThat(suggestion.get("status").asText()).isEqualTo("PENDING");
        String tokens = events.stream().filter(e -> "token".equals(e.name())).map(Event::data).collect(Collectors.joining());
        assertThat(tokens).isEqualTo(suggestion.get("reasoning").asText());

        // the streamed suggestion was really saved
        assertThat(fx.suggestionStatus(suggestion.get("id").asText())).isEqualTo(SuggestionStatus.PENDING);
    }

    @Test
    void unknownAppointmentStreamsAnErrorEvent() throws Exception {
        HttpResponse<String> res = stream("/appointments/APT-NOPE/suggest/stream");
        assertThat(res.statusCode()).isEqualTo(200); // the stream was already open when the error happened
        List<Event> events = parse(res.body());
        Event last = events.get(events.size() - 1);
        assertThat(last.name()).isEqualTo("error");
        JsonNode err = json.readTree(last.data());
        assertThat(err.get("error").asText()).isEqualTo("NOT_FOUND");
        assertThat(err.get("message").asText()).contains("APT-NOPE");
        assertThat(events).extracting(Event::name).doesNotContain("suggestion", "token");
    }

    @Test
    void unknownStrategyStreamsAValidationError() throws Exception {
        World w = fx.world();
        List<Event> events = parse(stream("/appointments/" + w.appt().getId() + "/suggest/stream?strategy=NOPE").body());
        Event last = events.get(events.size() - 1);
        assertThat(last.name()).isEqualTo("error");
        assertThat(json.readTree(last.data()).get("error").asText()).isEqualTo("VALIDATION");
    }
}
