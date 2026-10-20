package com.slotmind.web;

import com.slotmind.domain.AppointmentStatus;
import com.slotmind.service.AppointmentService;
import com.slotmind.service.AppointmentService.BookRequest;
import com.slotmind.service.RescheduleOrchestrator;
import com.slotmind.view.Views.AppointmentView;
import com.slotmind.view.Views.SuggestionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/appointments")
public class AppointmentController {
    private static final Logger log = LoggerFactory.getLogger(AppointmentController.class);
    private final AppointmentService appointments;
    private final RescheduleOrchestrator orchestrator;
    private final ExecutorService sseExecutor;

    public AppointmentController(AppointmentService appointments, RescheduleOrchestrator orchestrator, ExecutorService sseExecutor) {
        this.appointments = appointments; this.orchestrator = orchestrator; this.sseExecutor = sseExecutor;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentView book(@RequestBody BookRequest req) { return appointments.book(req); }

    @GetMapping
    public List<AppointmentView> list(@RequestParam(name = "status", required = false) AppointmentStatus status) {
        return appointments.list(status);
    }

    @GetMapping("/{id}")
    public AppointmentView get(@PathVariable("id") String id) { return appointments.get(id); }

    @PatchMapping("/{id}/complete")
    public AppointmentView complete(@PathVariable("id") String id) { return appointments.complete(id); }

    /** On-demand, synchronous, triggerReason=INITIAL. */
    @PostMapping("/{id}/suggest")
    public SuggestionView suggest(@PathVariable("id") String id, @RequestParam(name = "strategy", required = false) String strategy) {
        return orchestrator.requestNew(id, strategy, "STAFF");
    }

    /** Bonus SSE: status -> token... -> suggestion (pseudo-stream of the already validated reasoning). */
    @PostMapping(value = "/{id}/suggest/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter suggestStream(@PathVariable("id") String id, @RequestParam(name = "strategy", required = false) String strategy) {
        SseEmitter emitter = new SseEmitter(120_000L);
        sseExecutor.submit(() -> {
            try {
                send(emitter, "status", "Collecting legal open slots for " + id + "...");
                send(emitter, "status", "Ranking candidates" + (strategy == null ? "" : " with " + strategy) + "...");
                SuggestionView view = orchestrator.requestNew(id, strategy, "STAFF");
                send(emitter, "status", view.fallbackUsed() ? "AI unavailable or invalid — using rule-based suggestion"
                        : "Suggestion validated (" + view.strategy() + ")");
                String reasoning = view.reasoning() == null ? "" : view.reasoning();
                String[] words = reasoning.split(" ");
                for (int i = 0; i < words.length; i++) {
                    send(emitter, "token", i < words.length - 1 ? words[i] + " " : words[i]);
                    Thread.sleep(35);
                }
                emitter.send(SseEmitter.event().name("suggestion").data(view, MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception e) {
                log.warn("SSE suggest failed for {}: {}", id, e.toString());
                try {
                    emitter.send(SseEmitter.event().name("error").data(ApiExceptionHandler.toMap(e), MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String name, String data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }
}
