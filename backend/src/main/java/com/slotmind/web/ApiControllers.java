package com.slotmind.web;

import com.slotmind.domain.PreferredWindow;
import com.slotmind.domain.ProviderStatus;
import com.slotmind.domain.SlotStatus;
import com.slotmind.domain.SuggestionStatus;
import com.slotmind.scheduling.StrategyRegistry;
import com.slotmind.service.*;
import com.slotmind.service.WaitlistService.WaitlistRequest;
import com.slotmind.view.Views.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Thin REST controllers: delegate to services, return view records only. */
public final class ApiControllers {
    private ApiControllers() {}

    public record StatusRequest<T>(T status) {}
    public record SuggestionPatch(SuggestionStatus status, String actor) {}
    public record ProviderStatusRequest(ProviderStatus status) {}
    public record SlotStatusRequest(SlotStatus status) {}
    public record StrategyRequest(String strategy) {}
    public record PreferenceRequest(PreferredWindow preferredWindow) {}

    @RestController
    @RequestMapping("/suggestions")
    public static class SuggestionController {
        private final SuggestionService service;
        public SuggestionController(SuggestionService service) { this.service = service; }

        @GetMapping
        public List<SuggestionView> list(@RequestParam(name = "status", required = false) SuggestionStatus status) {
            return service.list(status);
        }

        @PatchMapping("/{id}")
        public SuggestionView patch(@PathVariable("id") String id, @RequestBody SuggestionPatch body) {
            if (body == null || body.status() == null) throw new IllegalArgumentException("status is required (ACCEPTED|REJECTED)");
            return service.resolve(id, body.status(), body.actor());
        }
    }

    @RestController
    @RequestMapping("/providers")
    public static class ProviderController {
        private final ProviderService service;
        public ProviderController(ProviderService service) { this.service = service; }

        @GetMapping
        public List<ProviderView> list() { return service.list(); }

        @PatchMapping("/{id}/status")
        public ProviderView status(@PathVariable("id") String id, @RequestBody ProviderStatusRequest body) {
            return service.changeStatus(id, body == null ? null : body.status());
        }
    }

    @RestController
    @RequestMapping("/slots")
    public static class SlotController {
        private final SlotService service;
        public SlotController(SlotService service) { this.service = service; }

        @GetMapping
        public List<SlotView> list(@RequestParam(name = "providerId", required = false) String providerId,
                                   @RequestParam(name = "status", required = false) SlotStatus status) {
            return service.list(providerId == null || providerId.isBlank() ? null : providerId, status);
        }

        @PatchMapping("/{id}/status")
        public SlotView status(@PathVariable("id") String id, @RequestBody SlotStatusRequest body) {
            return service.changeStatus(id, body == null ? null : body.status());
        }
    }

    @RestController
    public static class MiscController {
        private final QueryService query;
        private final ActivityService activity;
        private final NotificationService notifications;
        private final PreferenceService preferences;
        private final StrategyRegistry registry;

        public MiscController(QueryService query, ActivityService activity, NotificationService notifications,
                              PreferenceService preferences, StrategyRegistry registry) {
            this.query = query; this.activity = activity; this.notifications = notifications;
            this.preferences = preferences; this.registry = registry;
        }

        @GetMapping("/schedule")
        public ScheduleView schedule() { return query.schedule(); }

        @GetMapping("/rooms")
        public List<RoomView> rooms() { return query.rooms(); }

        @GetMapping("/clinics")
        public List<ClinicView> clinics() { return query.clinics(); }

        @GetMapping("/activity")
        public List<ActivityView> activity() { return activity.recent(); }

        @GetMapping("/notifications")
        public List<NotificationView> notifications() { return notifications.list(); }

        @GetMapping("/config/strategy")
        public StrategyConfig strategy() { return new StrategyConfig(registry.activeKey(), registry.available()); }

        @PutMapping("/config/strategy")
        public StrategyConfig setStrategy(@RequestBody StrategyRequest body) {
            if (body == null || body.strategy() == null) throw new IllegalArgumentException("strategy is required");
            String before = registry.activeKey();
            registry.setActive(body.strategy());
            activity.log(com.slotmind.domain.ActivityKind.INFO, "PUT /config/strategy → " + registry.activeKey() + " (was " + before + ")");
            return strategy();
        }

        @GetMapping("/preferences/{patientName}")
        public PreferenceView preference(@PathVariable("patientName") String name) { return preferences.resolve(name); }

        @PutMapping("/preferences/{patientName}")
        public PreferenceView setPreference(@PathVariable("patientName") String name, @RequestBody PreferenceRequest body) {
            return preferences.setExplicit(name, body == null ? null : body.preferredWindow());
        }
    }

    @RestController
    @RequestMapping("/waitlist")
    public static class WaitlistController {
        private final WaitlistService service;
        public WaitlistController(WaitlistService service) { this.service = service; }

        @GetMapping
        public List<WaitlistView> list() { return service.list(); }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public WaitlistView add(@RequestBody WaitlistRequest req) { return service.add(req); }

        @PostMapping("/{id}/accept")
        public WaitlistView accept(@PathVariable("id") String id) { return service.accept(id); }

        @PostMapping("/{id}/decline")
        public WaitlistView decline(@PathVariable("id") String id) { return service.decline(id); }
    }

    @RestController
    @RequestMapping("/portal/appointments")
    public static class PortalController {
        private final QueryService query;
        private final RescheduleOrchestrator orchestrator;
        public PortalController(QueryService query, RescheduleOrchestrator orchestrator) {
            this.query = query; this.orchestrator = orchestrator;
        }

        @GetMapping("/{id}")
        public PortalView view(@PathVariable("id") String id) { return query.portal(id); }

        @PostMapping("/{id}/reschedule")
        public SuggestionView reschedule(@PathVariable("id") String id) { return orchestrator.requestNew(id, null, "PATIENT"); }
    }
}
