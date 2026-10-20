package com.slotmind.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitlistEntryTest {
    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 20, 8, 0);
    private final Clinic clinic = new Clinic("CLN-001", "Downtown");
    private final Provider p = new Provider("PRV-001", "Dr. Patel", Specialty.GENERAL, ProviderStatus.AVAILABLE, clinic, 8);
    private final Slot slot = new Slot("SLT-009", p, null, T.plusHours(3), 30, SlotStatus.OPEN);

    private WaitlistEntry entry(PreferredWindow w) {
        return new WaitlistEntry("WL-001", "Maria", "555-1234", AppointmentType.URGENT, Specialty.GENERAL, w, clinic, T);
    }

    @Test
    void constructorStartsWaitingAndExposesFields() {
        WaitlistEntry e = entry(PreferredWindow.MORNING);
        assertThat(e.getId()).isEqualTo("WL-001");
        assertThat(e.getPatientName()).isEqualTo("Maria");
        assertThat(e.getContact()).isEqualTo("555-1234");
        assertThat(e.getType()).isEqualTo(AppointmentType.URGENT);
        assertThat(e.getSpecialty()).isEqualTo(Specialty.GENERAL);
        assertThat(e.getPreferredWindow()).isEqualTo(PreferredWindow.MORNING);
        assertThat(e.getClinic()).isSameAs(clinic);
        assertThat(e.getStatus()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(e.getCreatedAt()).isEqualTo(T);
        assertThat(e.getOfferedSlot()).isNull();
        assertThat(e.getBookedAppointmentId()).isNull();
    }

    @Test
    void nullPreferredWindowDefaultsToAny() {
        assertThat(entry(null).getPreferredWindow()).isEqualTo(PreferredWindow.ANY);
    }

    @Test
    void offerThenBook() {
        WaitlistEntry e = entry(PreferredWindow.ANY);
        e.offer(slot);
        assertThat(e.getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        assertThat(e.getOfferedSlot()).isSameAs(slot);
        e.booked("APT-101");
        assertThat(e.getStatus()).isEqualTo(WaitlistStatus.BOOKED);
        assertThat(e.getBookedAppointmentId()).isEqualTo("APT-101");
    }

    @Test
    void offerThenDecline() {
        WaitlistEntry e = entry(PreferredWindow.ANY);
        e.offer(slot);
        e.decline();
        assertThat(e.getStatus()).isEqualTo(WaitlistStatus.DECLINED);
    }

    @Test
    void offerOnlyFromWaiting() {
        WaitlistEntry offered = entry(PreferredWindow.ANY);
        offered.offer(slot);
        assertThatThrownBy(() -> offered.offer(slot)).isInstanceOf(IllegalStateTransitionException.class);

        WaitlistEntry booked = entry(PreferredWindow.ANY);
        booked.offer(slot);
        booked.booked("APT-1");
        assertThatThrownBy(() -> booked.offer(slot)).isInstanceOf(IllegalStateTransitionException.class);

        WaitlistEntry declined = entry(PreferredWindow.ANY);
        declined.offer(slot);
        declined.decline();
        assertThatThrownBy(() -> declined.offer(slot)).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(declined.getStatus()).isEqualTo(WaitlistStatus.DECLINED);
    }

    @Test
    void bookedAndDeclineRequireOffer() {
        WaitlistEntry waiting = entry(PreferredWindow.ANY);
        assertThatThrownBy(() -> waiting.booked("APT-1")).isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("no offer to accept");
        assertThatThrownBy(waiting::decline).isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("no offer to decline");
        assertThat(waiting.getStatus()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(waiting.getBookedAppointmentId()).isNull();

        WaitlistEntry booked = entry(PreferredWindow.ANY);
        booked.offer(slot);
        booked.booked("APT-1");
        assertThatThrownBy(() -> booked.booked("APT-2")).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(booked::decline).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(booked.getBookedAppointmentId()).isEqualTo("APT-1");

        WaitlistEntry declined = entry(PreferredWindow.ANY);
        declined.offer(slot);
        declined.decline();
        assertThatThrownBy(() -> declined.booked("APT-1")).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(declined::decline).isInstanceOf(IllegalStateTransitionException.class);
    }
}
