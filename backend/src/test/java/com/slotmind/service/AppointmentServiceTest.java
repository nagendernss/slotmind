package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.service.AppointmentService.BookRequest;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.IntegrationTestBase;
import com.slotmind.view.Views.AppointmentView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentServiceTest extends IntegrationTestBase {

    @Test
    void bookOpenSlotCreatesConfirmedAppointmentAndBooksSlot() {
        World w = fx.world();
        AppointmentView v = appointmentService.book(new BookRequest("  Jane Roe ", AppointmentType.ROUTINE, w.b().getId(),
                w.b90().getId(), " jane@example.com "));

        assertThat(v.id()).startsWith("APT-");
        assertThat(v.patientName()).isEqualTo("Jane Roe");
        assertThat(v.contact()).isEqualTo("jane@example.com");
        assertThat(v.status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(v.providerId()).isEqualTo(w.b().getId());
        assertThat(v.slot().id()).isEqualTo(w.b90().getId());
        assertThat(v.slot().status()).isEqualTo(SlotStatus.BOOKED);
        assertThat(v.slot().clinicId()).isEqualTo(w.clinic().getId());
        assertThat(fx.slotStatus(w.b90().getId())).isEqualTo(SlotStatus.BOOKED);
        assertThat(fx.apptSlotId(v.id())).isEqualTo(w.b90().getId());
    }

    @Test
    void blankContactIsStoredAsNull() {
        World w = fx.world();
        AppointmentView v = appointmentService.book(new BookRequest("Jo", AppointmentType.ROUTINE, w.b().getId(), w.b90().getId(), "  "));
        assertThat(v.contact()).isNull();
    }

    @Test
    void bookingATakenSlotIsSlotUnavailableAndChangesNothing() {
        World w = fx.world();
        long before = appointments.count();
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("Late", AppointmentType.ROUTINE, w.a().getId(),
                w.booked().getId(), null)))
                .isInstanceOf(SlotUnavailableException.class).hasMessageContaining("BOOKED");
        assertThat(appointments.count()).isEqualTo(before);
        assertThat(fx.apptSlotId(w.appt().getId())).isEqualTo(w.booked().getId());
    }

    @Test
    void bookingACancelledSlotIsSlotUnavailable() {
        World w = fx.world();
        Slot cancelled = fx.slot(w.b(), w.room(), 300, 30, SlotStatus.CANCELLED);
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("X", AppointmentType.ROUTINE, w.b().getId(), cancelled.getId(), null)))
                .isInstanceOf(SlotUnavailableException.class).hasMessageContaining("CANCELLED");
    }

    @Test
    void secondBookingOfSameSlotGetsSlotUnavailable() {
        World w = fx.world();
        appointmentService.book(new BookRequest("First", AppointmentType.ROUTINE, w.b().getId(), w.b240().getId(), null));
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("Second", AppointmentType.ROUTINE, w.b().getId(), w.b240().getId(), null)))
                .isInstanceOf(SlotUnavailableException.class);
        assertThat(appointments.findBySlotId(w.b240().getId())).get().extracting(Appointment::getPatientName).isEqualTo("First");
    }

    @Test
    void offlineProviderCannotBeBooked() {
        World w = fx.world();
        Provider off = fx.provider(w.clinic(), Specialty.GENERAL, ProviderStatus.OFFLINE, 5);
        Slot s = fx.slot(off, w.room(), 100, 30, SlotStatus.OPEN);
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("X", AppointmentType.ROUTINE, off.getId(), s.getId(), null)))
                .isInstanceOf(SlotUnavailableException.class).hasMessageContaining("offline");
        assertThat(fx.slotStatus(s.getId())).isEqualTo(SlotStatus.OPEN);
    }

    @Test
    void durationRuleIsValidated() {
        World w = fx.world();
        // URGENT needs 45 minutes (and TRIAGE): a 30-minute slot is rejected.
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("X", AppointmentType.URGENT, w.b().getId(), w.b90().getId(), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("45");
        // FOLLOW_UP needs only 20: a 20-minute slot is fine; ROUTINE (30) on a 20-minute slot is not.
        Slot short20 = fx.slot(w.b(), w.room(), 400, 20, SlotStatus.OPEN);
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("X", AppointmentType.ROUTINE, w.b().getId(), short20.getId(), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("30");
        assertThat(fx.slotStatus(short20.getId())).isEqualTo(SlotStatus.OPEN);
        assertThat(appointmentService.book(new BookRequest("X", AppointmentType.FOLLOW_UP, w.b().getId(), short20.getId(), null)).status())
                .isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void equipmentRuleIsValidated() {
        World w = fx.world();
        Room plain = fx.room(w.clinic(), "EXAM");
        Slot noTriage = fx.slot(w.b(), plain, 300, 45, SlotStatus.OPEN);
        Slot noRoom = fx.slot(w.b(), null, 320, 45, SlotStatus.OPEN);
        Slot triage = fx.slot(w.b(), w.room(), 340, 45, SlotStatus.OPEN);
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("X", AppointmentType.URGENT, w.b().getId(), noTriage.getId(), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TRIAGE");
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("X", AppointmentType.URGENT, w.b().getId(), noRoom.getId(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(appointmentService.book(new BookRequest("X", AppointmentType.URGENT, w.b().getId(), triage.getId(), null)).type())
                .isEqualTo(AppointmentType.URGENT);
    }

    @Test
    void slotMustBelongToTheGivenProvider() {
        World w = fx.world();
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("X", AppointmentType.ROUTINE, w.a().getId(), w.b90().getId(), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("belongs to");
    }

    @Test
    void missingFieldsAndUnknownIds() {
        World w = fx.world();
        assertThatThrownBy(() -> appointmentService.book(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appointmentService.book(new BookRequest(" ", AppointmentType.ROUTINE, w.b().getId(), w.b90().getId(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("X", null, w.b().getId(), w.b90().getId(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("X", AppointmentType.ROUTINE, null, w.b90().getId(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("X", AppointmentType.ROUTINE, w.b().getId(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("X", AppointmentType.ROUTINE, "PRV-NOPE", w.b90().getId(), null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> appointmentService.book(new BookRequest("X", AppointmentType.ROUTINE, w.b().getId(), "SLT-NOPE", null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getAndListByStatus() {
        World w = fx.world();
        AppointmentView v = appointmentService.get(w.appt().getId());
        assertThat(v.patientName()).isEqualTo(w.appt().getPatientName());
        assertThat(v.providerName()).isEqualTo(w.a().getName());
        assertThat(v.slot().roomName()).isEqualTo(w.room().getName());
        assertThat(appointmentService.list(AppointmentStatus.CONFIRMED)).extracting(AppointmentView::id).contains(w.appt().getId());
        assertThat(appointmentService.list(AppointmentStatus.COMPLETED)).extracting(AppointmentView::id).doesNotContain(w.appt().getId());
        assertThat(appointmentService.list(null)).extracting(AppointmentView::id).contains(w.appt().getId());
        assertThat(appointmentService.list(AppointmentStatus.CONFIRMED)).allMatch(a -> a.status() == AppointmentStatus.CONFIRMED);
        assertThatThrownBy(() -> appointmentService.get("APT-NOPE")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void completeMovesToCompletedAndIsTerminal() {
        World w = fx.world();
        assertThat(appointmentService.complete(w.appt().getId()).status()).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(fx.apptStatus(w.appt().getId())).isEqualTo(AppointmentStatus.COMPLETED);
        assertThatThrownBy(() -> appointmentService.complete(w.appt().getId())).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> appointmentService.complete("APT-NOPE")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void reschedulePendingAppointmentCannotBeCompleted() {
        World w = fx.world();
        fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        assertThatThrownBy(() -> appointmentService.complete(w.appt().getId())).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(fx.apptStatus(w.appt().getId())).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
    }
}
