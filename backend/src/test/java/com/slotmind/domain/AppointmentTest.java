package com.slotmind.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentTest {
    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 20, 10, 0);
    private final Clinic clinic = new Clinic("CLN-001", "Downtown");
    private final Provider patel = new Provider("PRV-001", "Dr. Patel", Specialty.GENERAL, ProviderStatus.AVAILABLE, clinic, 8);
    private final Provider chen = new Provider("PRV-002", "Dr. Chen", Specialty.GENERAL, ProviderStatus.AVAILABLE, clinic, 8);
    private final Slot original = new Slot("SLT-001", patel, null, T, 30, SlotStatus.BOOKED);
    private final Slot chenSlot = new Slot("SLT-007", chen, null, T.plusHours(2), 30, SlotStatus.OPEN);

    private Appointment appt(AppointmentStatus status) {
        return new Appointment("APT-001", "John Martinez", "john@example.com", AppointmentType.ROUTINE, patel, original, status);
    }

    @Test
    void constructorExposesAllFields() {
        LocalDateTime before = LocalDateTime.now();
        Appointment a = appt(AppointmentStatus.CONFIRMED);
        assertThat(a.getId()).isEqualTo("APT-001");
        assertThat(a.getPatientName()).isEqualTo("John Martinez");
        assertThat(a.getContact()).isEqualTo("john@example.com");
        assertThat(a.getType()).isEqualTo(AppointmentType.ROUTINE);
        assertThat(a.getProvider()).isSameAs(patel);
        assertThat(a.getSlot()).isSameAs(original);
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(a.getCreatedAt()).isBetween(before, LocalDateTime.now());
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CONFIRMED", "RESCHEDULED", "RESCHEDULE_PENDING"})
    void markReschedulePendingFromLegalStates(AppointmentStatus status) {
        Appointment a = appt(status);
        a.markReschedulePending();
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
    }

    @Test
    void markReschedulePendingIsIdempotent() {
        Appointment a = appt(AppointmentStatus.CONFIRMED);
        a.markReschedulePending();
        a.markReschedulePending();
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
    }

    @Test
    void markReschedulePendingRejectsCompleted() {
        Appointment a = appt(AppointmentStatus.COMPLETED);
        assertThatThrownBy(a::markReschedulePending).isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("APT-001").hasMessageContaining("COMPLETED");
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
    }

    @Test
    void moveToUpdatesSlotProviderAndStatus() {
        Appointment a = appt(AppointmentStatus.RESCHEDULE_PENDING);
        a.moveTo(chenSlot);
        assertThat(a.getSlot()).isSameAs(chenSlot);
        assertThat(a.getProvider()).isSameAs(chen);
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.RESCHEDULED);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CONFIRMED", "RESCHEDULED", "COMPLETED"})
    void moveToRequiresReschedulePending(AppointmentStatus status) {
        Appointment a = appt(status);
        assertThatThrownBy(() -> a.moveTo(chenSlot)).isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("not awaiting a reschedule");
        assertThat(a.getSlot()).isSameAs(original);
        assertThat(a.getProvider()).isSameAs(patel);
        assertThat(a.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CONFIRMED", "RESCHEDULED"})
    void completeFromConfirmedOrRescheduled(AppointmentStatus status) {
        Appointment a = appt(status);
        a.complete();
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"RESCHEDULE_PENDING", "COMPLETED"})
    void completeRejectsOtherStates(AppointmentStatus status) {
        Appointment a = appt(status);
        assertThatThrownBy(a::complete).isInstanceOf(IllegalStateTransitionException.class).hasMessageContaining("cannot complete");
        assertThat(a.getStatus()).isEqualTo(status);
    }

    @Test
    void rescheduledAppointmentCanBeDisruptedAgain() {
        Appointment a = appt(AppointmentStatus.CONFIRMED);
        a.markReschedulePending();
        a.moveTo(chenSlot);
        a.markReschedulePending();
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
        a.moveTo(original);
        a.complete();
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(a.getProvider()).isSameAs(patel);
    }
}
