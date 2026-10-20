package com.slotmind.scheduling;

import com.slotmind.domain.*;
import com.slotmind.repository.AppointmentTypeRuleRepository;
import com.slotmind.repository.ProviderRepository;
import com.slotmind.repository.SlotRepository;
import com.slotmind.repository.SuggestionRepository;
import com.slotmind.service.ActivityService;
import com.slotmind.service.LoadService;
import com.slotmind.service.PreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit test of the two-stage pipeline with real filters and strategies; repositories/services are Mockito mocks.
 * Fx slots live in 2099, so the engine's real clock always sees them as future.
 * Original appointment: APT-001, Dr. Patel (GENERAL, Downtown), ROUTINE, 10:00 in Exam Room 1.
 */
class ScheduleEngineTest {

    /** Programmable stand-in for the AI strategy. */
    static final class FakeStrategy implements SchedulingStrategy {
        final String key;
        Function<SchedulingContext, List<SlotRecommendation>> answer;
        SchedulingContext seen;
        int calls;

        FakeStrategy(String key, Function<SchedulingContext, List<SlotRecommendation>> answer) { this.key = key; this.answer = answer; }
        public String key() { return key; }
        public List<SlotRecommendation> recommend(SchedulingContext ctx) { calls++; seen = ctx; return answer.apply(ctx); }
    }

    private final Fx f = new Fx();
    private final SlotRepository slots = mock(SlotRepository.class);
    private final ProviderRepository providers = mock(ProviderRepository.class);
    private final SuggestionRepository suggestions = mock(SuggestionRepository.class);
    private final AppointmentTypeRuleRepository typeRules = mock(AppointmentTypeRuleRepository.class);
    private final LoadService load = mock(LoadService.class);
    private final PreferenceService prefs = mock(PreferenceService.class);
    private final ActivityService activity = mock(ActivityService.class);

    private final List<CandidateFilter> filters = List.of(new Filters.OpenFutureSlotFilter(), new Filters.ProviderOnlineFilter(),
            new Filters.SpecialtyMatchFilter(), new Filters.DurationFitFilter(), new Filters.RoomEquipmentFilter(),
            new Filters.ProviderDailyCapFilter(), new Filters.ClinicScopeFilter());

    private final FakeStrategy ai = new FakeStrategy("AI", ctx -> List.of());
    private StrategyRegistry registry;
    private ScheduleEngine engine;

    // candidates (all OPEN, 30 min)
    private final Slot chen11 = Fx.open("SLT-011", f.chen, f.exam2, 11, 0);
    private final Slot patel15 = Fx.open("SLT-015", f.patel, f.exam1, 15, 0);
    private final Slot kim11 = Fx.open("SLT-K11", f.kim, f.exam2, 11, 0);            // wrong specialty
    private final Slot east12 = Fx.open("SLT-E12", f.east, f.eastRoom, 12, 0);       // other clinic

    @BeforeEach
    void setUp() {
        registry = new StrategyRegistry(List.of(ai, new RuleBasedStrategy(), new RoomConstraintStrategy()), "AI");
        engine = new ScheduleEngine(filters, registry, slots, providers, suggestions, typeRules, load, prefs, activity);
        when(slots.findOpenAfter(any())).thenReturn(List.of(chen11, patel15, kim11, east12));
        when(suggestions.findSlotIdsHeldByOthers(anyString())).thenReturn(List.of());
        when(typeRules.findById(AppointmentType.ROUTINE)).thenReturn(Optional.of(new AppointmentTypeRule(AppointmentType.ROUTINE, 30, Set.of())));
        when(typeRules.findById(AppointmentType.URGENT)).thenReturn(Optional.of(new AppointmentTypeRule(AppointmentType.URGENT, 45, Set.of("TRIAGE"))));
        when(load.bookedToday()).thenReturn(Map.of("PRV-001", 2, "PRV-002", 3));
        when(prefs.windowFor(anyString())).thenReturn(PreferredWindow.AFTERNOON);
        when(providers.findAllWithClinic()).thenReturn(List.of(f.patel, f.chen, f.kim, f.east));
    }

    private ScheduleEngine.EngineResult run() { return engine.recommend(f.appt, TriggerReason.PROVIDER_OFFLINE, Set.of(), null); }

    private void aiAnswers(SlotRecommendation rec) { ai.answer = ctx -> List.of(rec); }

    private void assertFellBack(ScheduleEngine.EngineResult r) {
        assertThat(r.strategy()).isEqualTo("RULE_BASED");
        assertThat(r.fallbackUsed()).isTrue();
        assertThat(r.slot()).isNotNull();
        // rule-based picks same provider first
        assertThat(r.slot().getId()).isEqualTo("SLT-015");
        assertThat(r.alternateProviderId()).isNull();
        verify(activity).log(eq(ActivityKind.WARN), contains("fell back to rule-based"));
    }

    private static String contains(String s) { return org.mockito.ArgumentMatchers.contains(s); }

    // ---------------------------------------------------------------- candidate building

    @Test
    void strategySeesOnlyLegalHomeClinicCandidatesAndFullContext() {
        aiAnswers(new SlotRecommendation("SLT-011", null, 0.8, "We can offer you a new time."));
        run();
        SchedulingContext ctx = ai.seen;
        assertThat(ctx.candidates()).extracting(Slot::getId).containsExactly("SLT-011", "SLT-015");
        assertThat(ctx.appointment()).isSameAs(f.appt);
        assertThat(ctx.trigger()).isEqualTo(TriggerReason.PROVIDER_OFFLINE);
        assertThat(ctx.preferredWindow()).isEqualTo(PreferredWindow.AFTERNOON);
        assertThat(ctx.providerLoad()).containsEntry("PRV-002", 3);
        assertThat(ctx.providers()).hasSize(4);
        assertThat(ctx.crossClinic()).isFalse();
        assertThat(ctx.now()).isNotNull();
        verify(prefs).windowFor("John Martinez");
    }

    @Test
    void offlineProvidersAreFilteredOut() {
        f.chen.changeStatus(ProviderStatus.OFFLINE);
        aiAnswers(new SlotRecommendation("SLT-015", null, 0.8, "We can offer you a new time."));
        run();
        assertThat(ai.seen.candidates()).extracting(Slot::getId).containsExactly("SLT-015");
    }

    @Test
    void typeRuleDurationAndEquipmentApply() {
        Appointment urgent = new Appointment("APT-002", "Ann Lee", null, AppointmentType.URGENT, f.patel, f.original,
                AppointmentStatus.RESCHEDULE_PENDING);
        Slot long45Triage = new Slot("SLT-045", f.chen, f.exam1, Fx.NOW.withHour(13), 45, SlotStatus.OPEN);
        Slot long45Bare = new Slot("SLT-046", f.chen, f.exam2, Fx.NOW.withHour(13), 45, SlotStatus.OPEN);
        when(slots.findOpenAfter(any())).thenReturn(List.of(chen11, patel15, long45Triage, long45Bare));
        aiAnswers(new SlotRecommendation("SLT-045", null, 0.8, "We can offer you a new time."));
        ScheduleEngine.EngineResult r = engine.recommend(urgent, TriggerReason.SLOT_CANCELLED, null, null);
        assertThat(ai.seen.candidates()).extracting(Slot::getId).containsExactly("SLT-045");
        assertThat(r.slot()).isSameAs(long45Triage);
    }

    @Test
    void missingTypeRuleMeansNoDurationOrEquipmentConstraint() {
        when(typeRules.findById(AppointmentType.ROUTINE)).thenReturn(Optional.empty());
        Slot tiny = new Slot("SLT-005", f.chen, null, Fx.NOW.withHour(13), 5, SlotStatus.OPEN);
        when(slots.findOpenAfter(any())).thenReturn(List.of(tiny));
        aiAnswers(new SlotRecommendation("SLT-005", null, 0.8, "We can offer you a new time."));
        assertThat(run().slot()).isSameAs(tiny);
    }

    @Test
    void excludeSetAndOwnSlotAreNeverCandidates() {
        Slot ownSlotReportedOpen = Fx.open("SLT-001", f.patel, f.exam1, 12, 0); // same id as the appointment's slot
        when(slots.findOpenAfter(any())).thenReturn(List.of(ownSlotReportedOpen, chen11, patel15));
        aiAnswers(new SlotRecommendation("SLT-011", null, 0.8, "We can offer you a new time."));
        engine.recommend(f.appt, TriggerReason.SLOT_CANCELLED, Set.of("SLT-015"), null);
        assertThat(ai.seen.candidates()).extracting(Slot::getId).containsExactly("SLT-011");
    }

    @Test
    void excludeSetIsNotMutated() {
        Set<String> exclude = Set.of("SLT-015"); // immutable: engine must copy before adding its own slot
        aiAnswers(new SlotRecommendation("SLT-011", null, 0.8, "We can offer you a new time."));
        engine.recommend(f.appt, TriggerReason.SLOT_CANCELLED, exclude, null);
        assertThat(exclude).containsExactly("SLT-015");
    }

    @Test
    void slotsHeldByOtherSuggestionsAreAvoidedWhenAlternativesExist() {
        when(suggestions.findSlotIdsHeldByOthers("APT-001")).thenReturn(List.of("SLT-015"));
        aiAnswers(new SlotRecommendation("SLT-011", null, 0.8, "We can offer you a new time."));
        run();
        assertThat(ai.seen.candidates()).extracting(Slot::getId).containsExactly("SLT-011");
    }

    @Test
    void heldSlotsAreUsedWhenNothingElseIsLegal() {
        when(suggestions.findSlotIdsHeldByOthers("APT-001")).thenReturn(List.of("SLT-011", "SLT-015"));
        aiAnswers(new SlotRecommendation("SLT-015", null, 0.8, "We can offer you a new time."));
        run();
        assertThat(ai.seen.candidates()).extracting(Slot::getId).containsExactly("SLT-011", "SLT-015");
    }

    @Test
    void widensToOtherClinicsWhenHomeHasNothing() {
        when(slots.findOpenAfter(any())).thenReturn(List.of(kim11, east12));
        aiAnswers(new SlotRecommendation("SLT-E12", null, 0.8, "We can offer you a new time."));
        ScheduleEngine.EngineResult r = run();
        assertThat(ai.seen.crossClinic()).isTrue();
        assertThat(ai.seen.candidates()).extracting(Slot::getId).containsExactly("SLT-E12");
        assertThat(r.crossClinic()).isTrue();
        assertThat(r.slot()).isSameAs(east12);
        assertThat(r.alternateProviderId()).isEqualTo("PRV-004");
        assertThat(r.reasoning()).isEqualTo("We can offer you a new time. This visit would be at our Eastside clinic.");
    }

    @Test
    void crossClinicNoteIsNotDuplicated() {
        when(slots.findOpenAfter(any())).thenReturn(List.of(east12));
        aiAnswers(new SlotRecommendation("SLT-E12", null, 0.8, "We can see you at our Eastside clinic at noon."));
        assertThat(run().reasoning()).isEqualTo("We can see you at our Eastside clinic at noon.");
    }

    @Test
    void crossClinicFallbackReasoningNamesClinicOnce() {
        when(slots.findOpenAfter(any())).thenReturn(List.of(east12));
        ai.answer = ctx -> { throw new RuntimeException("down"); };
        ScheduleEngine.EngineResult r = run();
        assertThat(r.crossClinic()).isTrue();
        assertThat(r.fallbackUsed()).isTrue();
        assertThat(r.reasoning().split("Eastside", -1)).hasSize(2);
    }

    @Test
    void appointmentWithoutClinicDoesNotWiden() {
        Provider homeless = new Provider("PRV-050", "Dr. Home", Specialty.GENERAL, ProviderStatus.AVAILABLE, null, 8);
        Slot orig = new Slot("SLT-500", homeless, null, Fx.NOW.withHour(10), 30, SlotStatus.BOOKED);
        Appointment a = new Appointment("APT-500", "Zed", null, AppointmentType.ROUTINE, homeless, orig, AppointmentStatus.RESCHEDULE_PENDING);
        when(slots.findOpenAfter(any())).thenReturn(List.of(east12));
        aiAnswers(new SlotRecommendation("SLT-E12", null, 0.8, "We can offer you a new time."));
        ScheduleEngine.EngineResult r = engine.recommend(a, TriggerReason.SLOT_CANCELLED, Set.of(), null);
        assertThat(r.crossClinic()).isFalse();
        assertThat(r.slot()).isSameAs(east12);
        assertThat(r.reasoning()).isEqualTo("We can offer you a new time.");
    }

    @Test
    void noCandidatesAnywhereGivesNullSlotWithoutCallingStrategy() {
        when(slots.findOpenAfter(any())).thenReturn(List.of(kim11));
        ScheduleEngine.EngineResult r = run();
        assertThat(r.slot()).isNull();
        assertThat(r.alternateProviderId()).isNull();
        assertThat(r.confidence()).isZero();
        assertThat(r.strategy()).isEqualTo("AI");
        assertThat(r.fallbackUsed()).isFalse();
        assertThat(r.crossClinic()).isFalse();
        assertThat(r.reasoning()).startsWith("Dr. Patel is unavailable today").contains("waitlist");
        assertThat(ai.calls).isZero();
        verifyNoInteractions(activity);
    }

    @Test
    void noOpenSlotsAtAll() {
        when(slots.findOpenAfter(any())).thenReturn(List.of());
        assertThat(engine.recommend(f.appt, TriggerReason.SLOT_CANCELLED, Set.of(), "ROOM_CONSTRAINT").slot()).isNull();
    }

    // ---------------------------------------------------------------- happy path + alt provider derivation

    @Test
    void validStrategyAnswerIsUsedAsIs() {
        aiAnswers(new SlotRecommendation(" SLT-011 ", "PRV-002", 0.83, "  We can offer you 11:00 AM with Dr. Chen.  "));
        ScheduleEngine.EngineResult r = run();
        assertThat(r.slot()).isSameAs(chen11);
        assertThat(r.alternateProviderId()).isEqualTo("PRV-002");
        assertThat(r.confidence()).isCloseTo(0.83, within(1e-9));
        assertThat(r.reasoning()).isEqualTo("We can offer you 11:00 AM with Dr. Chen.");
        assertThat(r.strategy()).isEqualTo("AI");
        assertThat(r.fallbackUsed()).isFalse();
        assertThat(r.crossClinic()).isFalse();
        verifyNoInteractions(activity);
    }

    @Test
    void alternateProviderIsDerivedFromSlot() {
        aiAnswers(new SlotRecommendation("SLT-011", null, 0.8, "We can offer you a new time."));
        assertThat(run().alternateProviderId()).as("model omitted it").isEqualTo("PRV-002");
        aiAnswers(new SlotRecommendation("SLT-015", "  ", 0.8, "We can offer you a new time."));
        assertThat(run().alternateProviderId()).as("same doctor, blank alt").isNull();
        aiAnswers(new SlotRecommendation("SLT-015", "PRV-001", 0.8, "We can offer you a new time."));
        assertThat(run().alternateProviderId()).as("same doctor named explicitly").isNull();
    }

    @Test
    void onlyTopRecommendationCounts() {
        ai.answer = ctx -> List.of(new SlotRecommendation("SLT-011", null, 0.6, "We can offer you a new time."),
                new SlotRecommendation("BOGUS", null, 0.9, ""));
        assertThat(run().slot()).isSameAs(chen11);
    }

    @Test
    void confidenceIsClamped() {
        aiAnswers(new SlotRecommendation("SLT-011", null, 1.7, "We can offer you a new time."));
        assertThat(run().confidence()).isEqualTo(1.0);
        aiAnswers(new SlotRecommendation("SLT-011", null, -0.3, "We can offer you a new time."));
        assertThat(run().confidence()).isEqualTo(0.0);
        aiAnswers(new SlotRecommendation("SLT-011", null, Double.POSITIVE_INFINITY, "We can offer you a new time."));
        assertThat(run().confidence()).isEqualTo(1.0);
        verifyNoInteractions(activity);
    }

    // ---------------------------------------------------------------- validation → fallback

    @Test
    void unknownSlotFallsBack() {
        aiAnswers(new SlotRecommendation("SLT-999", null, 0.9, "We can offer you a new time."));
        assertFellBack(run());
    }

    @Test
    void filteredOutSlotFallsBack() {
        aiAnswers(new SlotRecommendation("SLT-K11", null, 0.9, "We can offer you a new time.")); // exists but illegal
        assertFellBack(run());
    }

    @Test
    void nullSlotIdFallsBack() {
        aiAnswers(new SlotRecommendation(null, null, 0.9, "We can offer you a new time."));
        assertFellBack(run());
    }

    @Test
    void wrongAlternateProviderFallsBack() {
        aiAnswers(new SlotRecommendation("SLT-011", "PRV-003", 0.9, "We can offer you a new time."));
        assertFellBack(run());
    }

    @Test
    void nanConfidenceFallsBack() {
        aiAnswers(new SlotRecommendation("SLT-011", null, Double.NaN, "We can offer you a new time."));
        assertFellBack(run());
    }

    @Test
    void blankOrNullReasoningFallsBack() {
        aiAnswers(new SlotRecommendation("SLT-011", null, 0.9, "   "));
        assertFellBack(run());
        reset(activity);
        aiAnswers(new SlotRecommendation("SLT-011", null, 0.9, null));
        assertFellBack(run());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SLT-011", "PRV-002", "APT-001", "RM-002", "CLN-001", "SUG-004", "WL-001", "slt-011"})
    void reasoningLeakingInternalIdsFallsBack(String id) {
        aiAnswers(new SlotRecommendation("SLT-011", null, 0.9, "We can offer you a new time (" + id + ")."));
        assertFellBack(run());
    }

    @Test
    void reasoningWithHarmlessHyphenatedWordsIsAccepted() {
        aiAnswers(new SlotRecommendation("SLT-011", null, 0.9, "We can offer a follow-up at 11:00 AM in Exam Room 2, COVID-19 safe."));
        assertThat(run().fallbackUsed()).isFalse();
    }

    @Test
    void strategyExceptionFallsBackAndLogsShortenedMessage() {
        String longMsg = "x".repeat(400);
        ai.answer = ctx -> { throw new IllegalStateException(longMsg); };
        ScheduleEngine.EngineResult r = run();
        assertFellBack(r);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(activity).log(eq(ActivityKind.WARN), msg.capture());
        assertThat(msg.getValue()).startsWith("AI strategy failed for John Martinez (APT-001): ")
                .contains("x".repeat(157) + "...").doesNotContain("x".repeat(158));
    }

    @Test
    void strategyExceptionWithoutMessage() {
        ai.answer = ctx -> { throw new RuntimeException(); };
        assertFellBack(run());
        verify(activity).log(eq(ActivityKind.WARN), contains("unknown error"));
    }

    @Test
    void emptyOrNullStrategyAnswerFallsBack() {
        ai.answer = ctx -> List.of();
        assertFellBack(run());
        reset(activity);
        ai.answer = ctx -> null;
        assertFellBack(run());
    }

    @Test
    void fallbackCarriesRuleBasedAlternateAndClampedConfidence() {
        f.patel.changeStatus(ProviderStatus.OFFLINE);
        ai.answer = ctx -> { throw new RuntimeException("timeout"); };
        ScheduleEngine.EngineResult r = run();
        assertThat(r.slot()).isSameAs(chen11);
        assertThat(r.alternateProviderId()).isEqualTo("PRV-002");
        assertThat(r.confidence()).isBetween(0.0, 1.0);
        assertThat(r.reasoning()).startsWith("Dr. Patel is unavailable today");
        assertThat(r.fallbackUsed()).isTrue();
    }

    // ---------------------------------------------------------------- strategy selection

    @Test
    void overrideSelectsStrategyCaseInsensitively() {
        ScheduleEngine.EngineResult r = engine.recommend(f.appt, TriggerReason.SLOT_CANCELLED, Set.of(), " room_constraint ");
        assertThat(r.strategy()).isEqualTo("ROOM_CONSTRAINT");
        assertThat(r.fallbackUsed()).isFalse();
        assertThat(r.slot()).isSameAs(patel15); // same provider + same room
        assertThat(ai.calls).isZero();
    }

    @Test
    void blankOverrideUsesActiveKey() {
        registry.setActive("RULE_BASED");
        ScheduleEngine.EngineResult r = engine.recommend(f.appt, TriggerReason.SLOT_CANCELLED, Set.of(), "  ");
        assertThat(r.strategy()).isEqualTo("RULE_BASED");
        assertThat(r.fallbackUsed()).isFalse();
        assertThat(r.slot()).isSameAs(patel15);
    }

    @Test
    void runtimeSwitchIsHonoured() {
        aiAnswers(new SlotRecommendation("SLT-011", null, 0.8, "We can offer you a new time."));
        assertThat(run().strategy()).isEqualTo("AI");
        registry.setActive("ROOM_CONSTRAINT");
        assertThat(run().strategy()).isEqualTo("ROOM_CONSTRAINT");
    }

    @Test
    void unknownOverrideIsCallerError() {
        assertThatThrownBy(() -> engine.recommend(f.appt, TriggerReason.SLOT_CANCELLED, Set.of(), "magic"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown strategy 'magic'");
        verifyNoInteractions(slots);
    }

    @Test
    void unregisteredConfiguredStrategyWarnsAndFallsBack() {
        registry = new StrategyRegistry(List.of(new RuleBasedStrategy()), "AI");
        engine = new ScheduleEngine(filters, registry, slots, providers, suggestions, typeRules, load, prefs, activity);
        ScheduleEngine.EngineResult r = run();
        assertThat(r.strategy()).isEqualTo("RULE_BASED");
        assertThat(r.fallbackUsed()).isTrue();
        verify(activity).log(eq(ActivityKind.WARN), contains("Strategy AI is not registered"));
    }

    @Test
    void ruleBasedFailureIsNotSwallowed() {
        FakeStrategy brokenRule = new FakeStrategy("RULE_BASED", ctx -> { throw new IllegalStateException("bug"); });
        registry = new StrategyRegistry(List.of(brokenRule), "RULE_BASED");
        engine = new ScheduleEngine(filters, registry, slots, providers, suggestions, typeRules, load, prefs, activity);
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class).hasMessage("bug");
        verifyNoInteractions(activity);
    }

    @Test
    void invalidRuleBasedAnswerIsNotSwallowed() {
        FakeStrategy badRule = new FakeStrategy("RULE_BASED", ctx -> List.of(new SlotRecommendation("NOPE", null, 1, "x")));
        registry = new StrategyRegistry(List.of(badRule), "RULE_BASED");
        engine = new ScheduleEngine(filters, registry, slots, providers, suggestions, typeRules, load, prefs, activity);
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class).hasMessageContaining("not a legal open candidate");
    }

    // ---------------------------------------------------------------- violatedConstraint (accept-time re-check)

    @Test
    void violatedConstraintNullForLegalSlot() {
        assertThat(engine.violatedConstraint(f.appt, chen11)).isNull();
        assertThat(engine.violatedConstraint(f.appt, east12)).as("clinic scope is not re-checked at accept").isNull();
    }

    @Test
    void violatedConstraintReportsFirstFailingFilter() {
        assertThat(engine.violatedConstraint(f.appt, Fx.slot("S", f.chen, null, 11, 0, SlotStatus.BOOKED))).isEqualTo("OpenFutureSlot");
        assertThat(engine.violatedConstraint(f.appt, kim11)).isEqualTo("SpecialtyMatch");
        when(load.bookedToday()).thenReturn(Map.of("PRV-002", 8));
        assertThat(engine.violatedConstraint(f.appt, chen11)).isEqualTo("ProviderDailyCap");
        f.chen.changeStatus(ProviderStatus.OFFLINE);
        assertThat(engine.violatedConstraint(f.appt, chen11)).isEqualTo("ProviderOnline");
    }

    @Test
    void violatedConstraintUsesTypeRule() {
        Appointment urgent = new Appointment("APT-002", "Ann", null, AppointmentType.URGENT, f.patel, f.original,
                AppointmentStatus.RESCHEDULE_PENDING);
        assertThat(engine.violatedConstraint(urgent, chen11)).isEqualTo("DurationFit");
        Slot longBare = new Slot("SLT-046", f.chen, f.exam2, Fx.NOW.withHour(13), 45, SlotStatus.OPEN);
        assertThat(engine.violatedConstraint(urgent, longBare)).isEqualTo("RoomEquipment");
        when(typeRules.findById(AppointmentType.URGENT)).thenReturn(Optional.empty());
        assertThat(engine.violatedConstraint(urgent, chen11)).isNull();
    }
}
