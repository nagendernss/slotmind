package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.IntegrationTestBase;
import com.slotmind.view.Views.SuggestionView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RescheduleOrchestratorTest extends IntegrationTestBase {

    private List<RescheduleSuggestion> pending(String apptId) {
        return suggestions.findByAppointmentIdAndStatus(apptId, SuggestionStatus.PENDING);
    }

    private long countFor(String apptId) {
        return suggestions.findDetailedByAppointmentId(apptId).size();
    }

    private Set<String> worldSlots(World w) { return Set.of(w.a180().getId(), w.b90().getId(), w.b240().getId()); }

    @Test
    void processCreatesOnePendingSuggestionAndIsIdempotentPerTrigger() {
        World w = fx.world();
        Optional<String> id = orchestrator.process(w.appt().getId(), TriggerReason.PROVIDER_OFFLINE);
        assertThat(id).isPresent();
        RescheduleSuggestion g = suggestions.findById(id.get()).orElseThrow();
        assertThat(g.getStatus()).isEqualTo(SuggestionStatus.PENDING);
        assertThat(g.getTriggerReason()).isEqualTo(TriggerReason.PROVIDER_OFFLINE);
        assertThat(g.getPendingKey()).isEqualTo(w.appt().getId() + ":PROVIDER_OFFLINE");
        assertThat(fx.apptStatus(w.appt().getId())).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
        SuggestionView v = tx.execute(t -> SuggestionView.of(suggestions.findDetailed(id.get()).orElseThrow()));
        assertThat(v.recommendedSlot()).isNotNull();
        assertThat(worldSlots(w)).contains(v.recommendedSlot().id());
        assertThat(v.reasoning()).isNotBlank().doesNotContain("SLT-").doesNotContain("PRV-");
        assertThat(v.originalSlot().id()).isEqualTo(w.booked().getId());

        // Same trigger again: skipped (service check), nothing new stored.
        assertThat(orchestrator.process(w.appt().getId(), TriggerReason.PROVIDER_OFFLINE)).isEmpty();
        assertThat(orchestrator.process(w.appt().getId(), TriggerReason.PROVIDER_OFFLINE)).isEmpty();
        assertThat(pending(w.appt().getId())).extracting(RescheduleSuggestion::getId).containsExactly(id.get());
        assertThat(countFor(w.appt().getId())).isEqualTo(1);
        assertThat(activityRepo.findTop100ByOrderByIdDesc()).anyMatch(a -> a.getMessage().contains("Skipped")
                && a.getMessage().contains(w.appt().getId()));
    }

    @Test
    void processWithADifferentTriggerSupersedesTheOlderPendingSuggestion() {
        World w = fx.world();
        String first = orchestrator.process(w.appt().getId(), TriggerReason.PROVIDER_OFFLINE).orElseThrow();
        String second = orchestrator.process(w.appt().getId(), TriggerReason.SLOT_CANCELLED).orElseThrow();
        assertThat(fx.suggestionStatus(first)).isEqualTo(SuggestionStatus.SUPERSEDED);
        assertThat(pending(w.appt().getId())).extracting(RescheduleSuggestion::getId).containsExactly(second);
    }

    @Test
    void processSkipsCompletedAppointmentsAndRejectsUnknownOnes() {
        World w = fx.world();
        appointmentService.complete(w.appt().getId());
        assertThat(orchestrator.process(w.appt().getId(), TriggerReason.PROVIDER_OFFLINE)).isEmpty();
        assertThat(countFor(w.appt().getId())).isZero();
        assertThatThrownBy(() -> orchestrator.process("APT-NOPE", TriggerReason.SLOT_CANCELLED)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void requestNewOnConfirmedAppointmentIsInitial() {
        World w = fx.world();
        SuggestionView v = orchestrator.requestNew(w.appt().getId(), "RULE_BASED", "STAFF");
        assertThat(v.triggerReason()).isEqualTo(TriggerReason.INITIAL);
        assertThat(v.status()).isEqualTo(SuggestionStatus.PENDING);
        assertThat(v.strategy()).isEqualTo("RULE_BASED");
        assertThat(v.fallbackUsed()).isFalse();
        assertThat(v.appointmentStatus()).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
        // rule-based: same provider first
        assertThat(v.recommendedSlot().id()).isEqualTo(w.a180().getId());
        assertThat(v.alternateProviderId()).isNull();
        assertThat(v.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    void requestNewUsesTheActiveStrategyByDefault() {
        World w = fx.world();
        SuggestionView v = orchestrator.requestNew(w.appt().getId(), null, "STAFF");
        assertThat(v.strategy()).isEqualTo("AI"); // test profile: mock LLM, deterministic
        assertThat(v.fallbackUsed()).isFalse();
        assertThat(worldSlots(w)).contains(v.recommendedSlot().id());
    }

    @Test
    void requestNewWithUnknownStrategyIsValidationErrorAndRollsBack() {
        World w = fx.world();
        assertThatThrownBy(() -> orchestrator.requestNew(w.appt().getId(), "NOPE", "STAFF"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown strategy");
        assertThat(fx.apptStatus(w.appt().getId())).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(countFor(w.appt().getId())).isZero();
    }

    @Test
    void requestNewOnCompletedAppointmentIsInvalidStateAndUnknownIsNotFound() {
        World w = fx.world();
        appointmentService.complete(w.appt().getId());
        assertThatThrownBy(() -> orchestrator.requestNew(w.appt().getId(), null, "STAFF")).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> orchestrator.requestNew("APT-NOPE", null, "STAFF")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void suggestAnotherKeepsOriginalTriggerSupersedesOlderAndNeverRepeatsAnOfferedSlot() {
        World w = fx.world();
        String s1 = orchestrator.process(w.appt().getId(), TriggerReason.PROVIDER_OFFLINE).orElseThrow();
        String slot1 = suggestions.findDetailed(s1).orElseThrow().getRecommendedSlot().getId();

        SuggestionView v2 = orchestrator.requestNew(w.appt().getId(), "RULE_BASED", "STAFF");
        assertThat(v2.triggerReason()).isEqualTo(TriggerReason.PROVIDER_OFFLINE);
        assertThat(v2.recommendedSlot().id()).isNotEqualTo(slot1);
        assertThat(fx.suggestionStatus(s1)).isEqualTo(SuggestionStatus.SUPERSEDED);

        SuggestionView v3 = orchestrator.requestNew(w.appt().getId(), "RULE_BASED", "STAFF");
        assertThat(v3.triggerReason()).isEqualTo(TriggerReason.PROVIDER_OFFLINE);
        assertThat(v3.recommendedSlot().id()).isNotIn(slot1, v2.recommendedSlot().id());
        assertThat(fx.suggestionStatus(v2.id())).isEqualTo(SuggestionStatus.SUPERSEDED);
        assertThat(pending(w.appt().getId())).extracting(RescheduleSuggestion::getId).containsExactly(v3.id());
    }

    @Test
    void suggestAnotherAfterRejectKeepsTriggerAndCyclesBackWhenEveryLegalSlotWasOffered() {
        World w = fx.world();
        fx.closeForeignOpenSlots(Specialty.GENERAL, w.clinic().getId()); // no cross-clinic widening for this test
        String s1 = orchestrator.process(w.appt().getId(), TriggerReason.PROVIDER_OFFLINE).orElseThrow();
        String rejected = suggestions.findDetailed(s1).orElseThrow().getRecommendedSlot().getId();
        suggestionService.resolve(s1, SuggestionStatus.REJECTED, "STAFF");

        SuggestionView v2 = orchestrator.requestNew(w.appt().getId(), "RULE_BASED", "STAFF");
        SuggestionView v3 = orchestrator.requestNew(w.appt().getId(), "RULE_BASED", "STAFF");
        assertThat(v2.triggerReason()).isEqualTo(TriggerReason.PROVIDER_OFFLINE);
        assertThat(v3.triggerReason()).isEqualTo(TriggerReason.PROVIDER_OFFLINE);
        assertThat(Set.of(rejected, v2.recommendedSlot().id(), v3.recommendedSlot().id())).isEqualTo(worldSlots(w));

        // All three legal slots have been offered: cycle back, but never to the rejected one.
        SuggestionView v4 = orchestrator.requestNew(w.appt().getId(), "RULE_BASED", "STAFF");
        assertThat(v4.recommendedSlot()).isNotNull();
        assertThat(v4.recommendedSlot().id()).isIn(v2.recommendedSlot().id(), v3.recommendedSlot().id());
        assertThat(v4.crossClinic()).isFalse();
        assertThat(pending(w.appt().getId())).extracting(RescheduleSuggestion::getId).containsExactly(v4.id());
    }

    @Test
    void whenEveryLegalSlotWasRejectedASlotlessSuggestionIsStored() {
        World w = fx.world();
        fx.closeForeignOpenSlots(Specialty.GENERAL, w.clinic().getId());
        for (int i = 0; i < 3; i++) {
            SuggestionView v = orchestrator.requestNew(w.appt().getId(), "RULE_BASED", "STAFF");
            assertThat(v.recommendedSlot()).isNotNull();
            suggestionService.resolve(v.id(), SuggestionStatus.REJECTED, "STAFF");
        }
        SuggestionView none = orchestrator.requestNew(w.appt().getId(), "RULE_BASED", "STAFF");
        assertThat(none.recommendedSlot()).isNull();
        assertThat(none.status()).isEqualTo(SuggestionStatus.PENDING);
        assertThat(none.reasoning()).contains("waitlist");
        assertThat(fx.apptStatus(w.appt().getId())).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
        assertThat(activityRepo.findTop100ByOrderByIdDesc()).anyMatch(a -> a.getKind() == ActivityKind.WARN
                && a.getMessage().contains(w.appt().getId()) && a.getMessage().contains("No legal slot"));
    }

    @Test
    void requestNewOnConfirmedAppointmentExcludesOnlyPreviouslyRejectedSlots() {
        World w = fx.world();
        // Round 1: reject b90, accept b240 -> appointment RESCHEDULED on b240 with provider B.
        RescheduleSuggestion r = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        suggestionService.resolve(r.getId(), SuggestionStatus.REJECTED, "STAFF");
        RescheduleSuggestion ok = fx.pendingSuggestion(w.appt().getId(), w.b240().getId(), TriggerReason.INITIAL);
        suggestionService.resolve(ok.getId(), SuggestionStatus.ACCEPTED, "STAFF");
        assertThat(fx.apptStatus(w.appt().getId())).isEqualTo(AppointmentStatus.RESCHEDULED);

        SuggestionView v = orchestrator.requestNew(w.appt().getId(), "RULE_BASED", "STAFF");
        assertThat(v.triggerReason()).isEqualTo(TriggerReason.INITIAL);
        assertThat(v.recommendedSlot().id()).isNotEqualTo(w.b90().getId()).isNotEqualTo(w.b240().getId());
        assertThat(v.originalSlot().id()).isEqualTo(w.b240().getId());
    }

    @Test
    void secondPatientsSuggestionAvoidsSlotsHeldByAnotherPendingSuggestion() {
        World w = fx.world();
        Slot booked2 = fx.slot(w.a(), w.room(), 61, 30, SlotStatus.BOOKED);
        Appointment second = fx.appointment(w.a(), booked2);
        SuggestionView first = orchestrator.requestNew(w.appt().getId(), "RULE_BASED", "STAFF");
        SuggestionView other = orchestrator.requestNew(second.getId(), "RULE_BASED", "STAFF");
        assertThat(other.recommendedSlot().id()).isNotEqualTo(first.recommendedSlot().id());
    }
}
