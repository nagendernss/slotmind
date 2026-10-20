package com.slotmind.scheduling;

import com.slotmind.domain.*;
import com.slotmind.repository.AppointmentTypeRuleRepository;
import com.slotmind.repository.ProviderRepository;
import com.slotmind.repository.SlotRepository;
import com.slotmind.repository.SuggestionRepository;
import com.slotmind.service.ActivityService;
import com.slotmind.service.LoadService;
import com.slotmind.service.PreferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Domain service (decision.md §5, §8): stage 1 builds the legal candidate set through CandidateFilters,
 * stage 2 lets the active strategy rank it, then validates the answer and falls back to rule-based on any problem.
 * Shared by the HTTP endpoints and the agentic loop.
 */
@Component
public class ScheduleEngine {
    private static final Logger log = LoggerFactory.getLogger(ScheduleEngine.class);
    /** Every internal id family (decision.md §15 #8): staff read reasoning aloud, so none may appear in it. */
    static final Pattern INTERNAL_ID = Pattern.compile("\\b(SLT|PRV|APT|RM|CLN|SUG|WL)-\\d+",Pattern.CASE_INSENSITIVE);

    public record EngineResult(Slot slot, String alternateProviderId, double confidence, String reasoning,
                               String strategy, boolean fallbackUsed, boolean crossClinic) {}

    private final List<CandidateFilter> filters;
    private final StrategyRegistry registry;
    private final SlotRepository slots;
    private final ProviderRepository providers;
    private final SuggestionRepository suggestions;
    private final AppointmentTypeRuleRepository typeRules;
    private final LoadService loadService;
    private final PreferenceService preferences;
    private final ActivityService activity;

    public ScheduleEngine(List<CandidateFilter> filters, StrategyRegistry registry, SlotRepository slots,
                          ProviderRepository providers, SuggestionRepository suggestions,
                          AppointmentTypeRuleRepository typeRules, LoadService loadService,
                          PreferenceService preferences, ActivityService activity) {
        this.filters = filters; this.registry = registry; this.slots = slots; this.providers = providers;
        this.suggestions = suggestions; this.typeRules = typeRules; this.loadService = loadService;
        this.preferences = preferences; this.activity = activity;
    }

    @Transactional
    public EngineResult recommend(Appointment appt, TriggerReason trigger, Set<String> excludeSlotIds, String strategyOverride) {
        LocalDateTime now = LocalDateTime.now();
        String requestedKey = strategyOverride != null && !strategyOverride.isBlank()
                ? StrategyRegistry.normalize(strategyOverride) : registry.activeKey();
        if (strategyOverride != null && !strategyOverride.isBlank() && registry.find(requestedKey).isEmpty()) {
            throw new IllegalArgumentException("Unknown strategy '" + strategyOverride + "'. Available: " + registry.available());
        }

        Map<String, Integer> load = loadService.bookedToday();
        Set<String> exclude = new HashSet<>(excludeSlotIds == null ? Set.of() : excludeSlotIds);
        exclude.add(appt.getSlot().getId());
        List<Slot> base = slots.findOpenAfter(now).stream().filter(s -> !exclude.contains(s.getId())).toList();
        Set<String> held = new HashSet<>(suggestions.findSlotIdsHeldByOthers(appt.getId()));

        String home = appt.getProvider().getClinic() == null ? null : appt.getProvider().getClinic().getId();
        boolean crossClinic = false;
        List<Slot> candidates = candidates(appt, base, held, home, load, now);
        if (candidates.isEmpty() && home != null) {
            candidates = candidates(appt, base, held, null, load, now);
            crossClinic = !candidates.isEmpty();
        }

        if (candidates.isEmpty()) {
            String reasoning = Texts.triggerOpening(trigger, appt.getProvider().getName())
                    + " Unfortunately there is no open time today that fits this kind of visit. "
                    + "We can put you on the waitlist and contact you as soon as a suitable time opens up.";
            return new EngineResult(null, null, 0.0, reasoning, requestedKey, false, false);
        }

        SchedulingContext ctx = new SchedulingContext(appt, trigger, candidates, providers.findAllWithClinic(),
                preferences.windowFor(appt.getPatientName()), load, crossClinic, now);

        Optional<SchedulingStrategy> strategy = registry.find(requestedKey);
        if (strategy.isPresent()) {
            try {
                List<SlotRecommendation> recs = strategy.get().recommend(ctx);
                if (recs == null || recs.isEmpty()) throw new IllegalStateException("strategy returned no recommendation");
                SlotRecommendation top = recs.get(0);
                Slot slot = validate(top, candidates);
                return new EngineResult(slot, slot.getProvider().getId().equals(appt.getProvider().getId()) ? null : slot.getProvider().getId(),
                        clamp(top.confidence()), withClinicNote(top.reasoning().trim(), slot, crossClinic),
                        requestedKey, false, crossClinic);
            } catch (Exception e) {
                if (StrategyRegistry.RULE_BASED.equals(requestedKey)) throw e;
                log.warn("Strategy {} failed for {}: {}", requestedKey, appt.getId(), e.toString());
                activity.log(ActivityKind.WARN, requestedKey + " strategy failed for " + appt.getPatientName() + " (" + appt.getId()
                        + "): " + shorten(e.getMessage()) + " → fell back to rule-based");
            }
        } else {
            activity.log(ActivityKind.WARN, "Strategy " + requestedKey + " is not registered → fell back to rule-based for "
                    + appt.getPatientName() + " (" + appt.getId() + ")");
        }

        SlotRecommendation top = registry.ruleBased().recommend(ctx).get(0);
        Slot slot = validate(top, candidates);
        return new EngineResult(slot, top.alternateProviderId(), clamp(top.confidence()), top.reasoning(),
                StrategyRegistry.RULE_BASED, true, crossClinic);
    }

    /** Re-check every hard constraint for one slot (used inside the accept transaction). Returns the failing filter or null. */
    @Transactional(readOnly = true)
    public String violatedConstraint(Appointment appt, Slot slot) {
        AppointmentTypeRule rule = typeRules.findById(appt.getType()).orElse(null);
        FilterContext fc = new FilterContext(appt, rule == null ? 0 : rule.getDurationMinutes(),
                rule == null ? Set.of() : rule.getRequiredEquipment(), loadService.bookedToday(), null, LocalDateTime.now());
        return filters.stream().filter(f -> !f.test(slot, fc)).map(CandidateFilter::name).findFirst().orElse(null);
    }

    private List<Slot> candidates(Appointment appt, List<Slot> base, Set<String> held, String home,
                                  Map<String, Integer> load, LocalDateTime now) {
        AppointmentTypeRule rule = typeRules.findById(appt.getType()).orElse(null);
        FilterContext fc = new FilterContext(appt, rule == null ? 0 : rule.getDurationMinutes(),
                rule == null ? Set.of() : rule.getRequiredEquipment(), load, home, now);
        List<Slot> legal = base.stream().filter(s -> filters.stream().allMatch(f -> f.test(s, fc))).toList();
        // Prefer slots not already proposed to another patient; use them only if nothing else is legal.
        List<Slot> free = legal.stream().filter(s -> !held.contains(s.getId())).toList();
        return free.isEmpty() ? legal : free;
    }

    private Slot validate(SlotRecommendation rec, List<Slot> candidates) {
        if (rec.slotId() == null) throw new IllegalStateException("no slotId in recommendation");
        Map<String, Slot> byId = candidates.stream().collect(Collectors.toMap(Slot::getId, s -> s));
        Slot slot = byId.get(rec.slotId().trim());
        if (slot == null) throw new IllegalStateException("recommended slot " + rec.slotId() + " is not a legal open candidate");
        if (rec.alternateProviderId() != null && !rec.alternateProviderId().isBlank()
                && !rec.alternateProviderId().trim().equals(slot.getProvider().getId())) {
            throw new IllegalStateException("alternateProviderId " + rec.alternateProviderId() + " does not match the slot's provider");
        }
        if (Double.isNaN(rec.confidence())) throw new IllegalStateException("confidence is not a number");
        String r = rec.reasoning();
        if (r == null || r.isBlank()) throw new IllegalStateException("reasoning is empty");
        if (INTERNAL_ID.matcher(r).find()) throw new IllegalStateException("reasoning contains internal ids");
        return slot;
    }

    private static String withClinicNote(String reasoning, Slot slot, boolean crossClinic) {
        if (!crossClinic || slot.getProvider().getClinic() == null) return reasoning;
        String clinic = slot.getProvider().getClinic().getName();
        return reasoning.contains(clinic) ? reasoning : reasoning + " This visit would be at our " + clinic + " clinic.";
    }

    private static double clamp(double c) { return Math.max(0.0, Math.min(1.0, c)); }

    private static String shorten(String s) {
        if (s == null) return "unknown error";
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }
}
