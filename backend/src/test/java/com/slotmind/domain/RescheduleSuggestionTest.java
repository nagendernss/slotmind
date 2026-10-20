package com.slotmind.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RescheduleSuggestionTest {
    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 20, 10, 0);
    private final Clinic clinic = new Clinic("CLN-001", "Downtown");
    private final Provider patel = new Provider("PRV-001", "Dr. Patel", Specialty.GENERAL, ProviderStatus.AVAILABLE, clinic, 8);
    private final Provider chen = new Provider("PRV-002", "Dr. Chen", Specialty.GENERAL, ProviderStatus.AVAILABLE, clinic, 8);
    private final Slot original = new Slot("SLT-001", patel, null, T, 30, SlotStatus.BOOKED);
    private final Slot target = new Slot("SLT-007", chen, null, T.plusHours(2), 30, SlotStatus.OPEN);
    private final Appointment appt = new Appointment("APT-001", "John", null, AppointmentType.ROUTINE, patel, original,
            AppointmentStatus.RESCHEDULE_PENDING);

    private RescheduleSuggestion suggestion(Slot recommended, TriggerReason trigger) {
        return new RescheduleSuggestion(appt, recommended, "PRV-002", 0.82, "We can offer you 12:00 PM.", trigger,
                "AI", true, true);
    }

    @Test
    void keyIsAppointmentColonTrigger() {
        assertThat(RescheduleSuggestion.key("APT-001", TriggerReason.PROVIDER_OFFLINE)).isEqualTo("APT-001:PROVIDER_OFFLINE");
        assertThat(RescheduleSuggestion.key("APT-9", TriggerReason.INITIAL)).isEqualTo("APT-9:INITIAL");
        assertThat(RescheduleSuggestion.key("APT-001", TriggerReason.SLOT_CANCELLED))
                .isNotEqualTo(RescheduleSuggestion.key("APT-001", TriggerReason.PROVIDER_OFFLINE));
    }

    @Test
    void constructorStartsPendingWithPendingKeyAndCopiesFields() {
        LocalDateTime before = LocalDateTime.now();
        RescheduleSuggestion s = suggestion(target, TriggerReason.PROVIDER_OFFLINE);
        assertThat(s.getId()).matches("SUG-\\d{3,}");
        assertThat(s.getAppointment()).isSameAs(appt);
        assertThat(s.getOriginalSlot()).isSameAs(original);
        assertThat(s.getRecommendedSlot()).isSameAs(target);
        assertThat(s.getAlternateProviderId()).isEqualTo("PRV-002");
        assertThat(s.getConfidence()).isEqualTo(0.82);
        assertThat(s.getReasoning()).isEqualTo("We can offer you 12:00 PM.");
        assertThat(s.getTriggerReason()).isEqualTo(TriggerReason.PROVIDER_OFFLINE);
        assertThat(s.getStrategy()).isEqualTo("AI");
        assertThat(s.isFallbackUsed()).isTrue();
        assertThat(s.isCrossClinic()).isTrue();
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PENDING);
        assertThat(s.getPendingKey()).isEqualTo("APT-001:PROVIDER_OFFLINE");
        assertThat(s.getCreatedAt()).isBetween(before, LocalDateTime.now());
        assertThat(s.getResolvedAt()).isNull();
        assertThat(s.getResolvedBy()).isNull();
    }

    @Test
    void originalSlotIsSnapshotAtCreation() {
        RescheduleSuggestion s = suggestion(target, TriggerReason.SLOT_CANCELLED);
        appt.moveTo(target);
        assertThat(s.getOriginalSlot()).isSameAs(original);
    }

    @Test
    void eachSuggestionGetsUniqueId() {
        assertThat(suggestion(target, TriggerReason.INITIAL).getId()).isNotEqualTo(suggestion(target, TriggerReason.INITIAL).getId());
    }

    @Test
    void acceptResolvesAndClearsPendingKey() {
        RescheduleSuggestion s = suggestion(target, TriggerReason.PROVIDER_OFFLINE);
        s.accept("STAFF");
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.ACCEPTED);
        assertThat(s.getPendingKey()).isNull();
        assertThat(s.getResolvedAt()).isNotNull();
        assertThat(s.getResolvedBy()).isEqualTo("STAFF");
    }

    @Test
    void acceptWithoutSlotIsIllegalAndStaysPending() {
        RescheduleSuggestion s = suggestion(null, TriggerReason.PROVIDER_OFFLINE);
        assertThatThrownBy(() -> s.accept("STAFF")).isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("no slot");
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PENDING);
        assertThat(s.getPendingKey()).isEqualTo("APT-001:PROVIDER_OFFLINE");
        assertThat(s.getResolvedBy()).isNull();
    }

    @Test
    void rejectResolves() {
        RescheduleSuggestion s = suggestion(target, TriggerReason.SLOT_CANCELLED);
        s.reject("PATIENT");
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.REJECTED);
        assertThat(s.getPendingKey()).isNull();
        assertThat(s.getResolvedAt()).isNotNull();
        assertThat(s.getResolvedBy()).isEqualTo("PATIENT");
    }

    @Test
    void rejectWithoutSlotIsAllowed() {
        RescheduleSuggestion s = suggestion(null, TriggerReason.SLOT_CANCELLED);
        s.reject("STAFF");
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.REJECTED);
    }

    @Test
    void supersedeResolvesWithoutActor() {
        RescheduleSuggestion s = suggestion(null, TriggerReason.INITIAL);
        s.supersede();
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.SUPERSEDED);
        assertThat(s.getPendingKey()).isNull();
        assertThat(s.getResolvedAt()).isNotNull();
        assertThat(s.getResolvedBy()).isNull();
    }

    enum Action {
        ACCEPT(s -> s.accept("STAFF")), REJECT(s -> s.reject("STAFF")), SUPERSEDE(RescheduleSuggestion::supersede);
        final Consumer<RescheduleSuggestion> op;
        Action(Consumer<RescheduleSuggestion> op) { this.op = op; }
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void everyResolutionIsTerminal(Action first) {
        for (Action second : Action.values()) {
            RescheduleSuggestion s = suggestion(target, TriggerReason.PROVIDER_OFFLINE);
            first.op.accept(s);
            SuggestionStatus resolved = s.getStatus();
            String resolvedBy = s.getResolvedBy();
            assertThatThrownBy(() -> second.op.accept(s)).as(first + " then " + second)
                    .isInstanceOf(IllegalStateTransitionException.class).hasMessageContaining("not PENDING");
            assertThat(s.getStatus()).isEqualTo(resolved);
            assertThat(s.getResolvedBy()).isEqualTo(resolvedBy);
        }
    }
}
