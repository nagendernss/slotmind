package com.slotmind.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotTest {
    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 20, 10, 0);
    private final Clinic clinic = new Clinic("CLN-001", "Downtown");
    private final Provider provider = new Provider("PRV-001", "Dr. Patel", Specialty.GENERAL, ProviderStatus.AVAILABLE, clinic, 8);
    private final Room room = new Room("RM-001", "Exam 1", clinic, Set.of("TRIAGE"));

    private Slot slot(SlotStatus status) { return new Slot("SLT-001", provider, room, T, 30, status); }

    @Test
    void constructorExposesAllFields() {
        Slot s = slot(SlotStatus.OPEN);
        assertThat(s.getId()).isEqualTo("SLT-001");
        assertThat(s.getProvider()).isSameAs(provider);
        assertThat(s.getRoom()).isSameAs(room);
        assertThat(s.getStartTime()).isEqualTo(T);
        assertThat(s.getDurationMinutes()).isEqualTo(30);
        assertThat(s.getStatus()).isEqualTo(SlotStatus.OPEN);
        assertThat(s.getVersion()).isNull();
    }

    @Test
    void endTimeIsStartPlusDuration() {
        assertThat(slot(SlotStatus.OPEN).getEndTime()).isEqualTo(LocalDateTime.of(2026, 10, 20, 10, 30));
        assertThat(new Slot("S", provider, null, T, 0, SlotStatus.OPEN).getEndTime()).isEqualTo(T);
        assertThat(new Slot("S", provider, null, LocalDateTime.of(2026, 10, 20, 23, 45), 45, SlotStatus.OPEN).getEndTime())
                .isEqualTo(LocalDateTime.of(2026, 10, 21, 0, 30));
    }

    @Test
    void bookOpenSlot() {
        Slot s = slot(SlotStatus.OPEN);
        s.book();
        assertThat(s.getStatus()).isEqualTo(SlotStatus.BOOKED);
    }

    @ParameterizedTest
    @EnumSource(value = SlotStatus.class, names = {"BOOKED", "CANCELLED"})
    void bookRejectsNonOpen(SlotStatus status) {
        Slot s = slot(status);
        assertThatThrownBy(s::book).isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("SLT-001").hasMessageContaining(status.name());
        assertThat(s.getStatus()).isEqualTo(status);
    }

    @Test
    void freeBookedSlot() {
        Slot s = slot(SlotStatus.BOOKED);
        s.free();
        assertThat(s.getStatus()).isEqualTo(SlotStatus.OPEN);
    }

    @ParameterizedTest
    @EnumSource(value = SlotStatus.class, names = {"OPEN", "CANCELLED"})
    void freeRejectsNonBooked(SlotStatus status) {
        Slot s = slot(status);
        assertThatThrownBy(s::free).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(s.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = SlotStatus.class, names = {"OPEN", "BOOKED"})
    void cancelFromOpenOrBooked(SlotStatus status) {
        Slot s = slot(status);
        s.cancel();
        assertThat(s.getStatus()).isEqualTo(SlotStatus.CANCELLED);
    }

    @Test
    void cancelledIsTerminal() {
        Slot s = slot(SlotStatus.CANCELLED);
        assertThatThrownBy(s::cancel).isInstanceOf(IllegalStateTransitionException.class).hasMessageContaining("already CANCELLED");
        assertThatThrownBy(s::book).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(s::free).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void fullRoundTripOpenBookedOpen() {
        Slot s = slot(SlotStatus.OPEN);
        s.book();
        s.free();
        s.book();
        assertThat(s.getStatus()).isEqualTo(SlotStatus.BOOKED);
    }
}
