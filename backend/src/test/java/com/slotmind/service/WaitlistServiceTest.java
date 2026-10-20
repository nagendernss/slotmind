package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.service.AppointmentService.BookRequest;
import com.slotmind.service.DomainEvents.SlotFreed;
import com.slotmind.service.DomainEvents.WaitlistBooked;
import com.slotmind.service.WaitlistService.WaitlistRequest;
import com.slotmind.support.Fixture;
import com.slotmind.support.IntegrationTestBase;
import com.slotmind.view.Views.WaitlistView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitlistServiceTest extends IntegrationTestBase {

    private Clinic clinic;
    private Room triageRoom;
    private Provider general;
    private final LocalDateTime tomorrow = LocalDate.now().plusDays(1).atStartOfDay();

    @BeforeEach
    void setUp() {
        clinic = fx.clinic();
        triageRoom = fx.room(clinic, "EXAM", "TRIAGE");
        general = fx.provider(clinic, Specialty.GENERAL);
    }

    private WaitlistEntry entry(AppointmentType type, Specialty sp, PreferredWindow window, Clinic c, int hoursAgo, String contact) {
        String u = Fixture.uid();
        return waitlist.save(new WaitlistEntry("WL-" + u, "Waiting " + u, contact, type, sp, window, c,
                LocalDateTime.now().minusHours(hoursAgo)));
    }

    private WaitlistEntry entry(AppointmentType type, int hoursAgo) {
        return entry(type, Specialty.GENERAL, PreferredWindow.ANY, clinic, hoursAgo, null);
    }

    private WaitlistEntry reload(WaitlistEntry e) { return waitlist.findById(e.getId()).orElseThrow(); }

    private String offeredSlotId(WaitlistEntry e) {
        return tx.execute(s -> { Slot o = reload(e).getOfferedSlot(); return o == null ? null : o.getId(); });
    }

    private Slot openSlot(int hourTomorrow, int minutes) {
        return fx.slotAt(general, triageRoom, tomorrow.plusHours(hourTomorrow), minutes, SlotStatus.OPEN);
    }

    @Test
    void urgentFirstThenLongestWait() {
        WaitlistEntry routineOld = entry(AppointmentType.ROUTINE, 10);
        WaitlistEntry urgentNew = entry(AppointmentType.URGENT, 1);
        WaitlistEntry routineOlder = entry(AppointmentType.ROUTINE, 20);
        Slot s = openSlot(10, 45);

        waitlistService.autoFill(s.getId());
        assertThat(reload(urgentNew).getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        assertThat(offeredSlotId(urgentNew)).isEqualTo(s.getId());
        assertThat(reload(routineOld).getStatus()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(reload(routineOlder).getStatus()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(fx.slotStatus(s.getId())).isEqualTo(SlotStatus.OPEN); // only an offer: human checkpoint

        Slot s2 = openSlot(11, 30);
        waitlistService.autoFill(s2.getId());
        assertThat(reload(routineOlder).getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        assertThat(reload(routineOld).getStatus()).isEqualTo(WaitlistStatus.WAITING);
    }

    @Test
    void matchingRespectsSpecialtyDurationEquipmentClinicAndWindow() {
        Clinic otherClinic = fx.clinic();
        WaitlistEntry wrongSpecialty = entry(AppointmentType.ROUTINE, Specialty.SPECIALIST, PreferredWindow.ANY, clinic, 50, null);
        WaitlistEntry wrongClinic = entry(AppointmentType.ROUTINE, Specialty.GENERAL, PreferredWindow.ANY, otherClinic, 49, null);
        WaitlistEntry wantsAfternoon = entry(AppointmentType.ROUTINE, Specialty.GENERAL, PreferredWindow.AFTERNOON, clinic, 48, null);
        WaitlistEntry urgentNeeds45 = entry(AppointmentType.URGENT, Specialty.GENERAL, PreferredWindow.ANY, clinic, 47, null);
        WaitlistEntry routineNeeds30 = entry(AppointmentType.ROUTINE, Specialty.GENERAL, PreferredWindow.MORNING, clinic, 46, null);
        WaitlistEntry followUp = entry(AppointmentType.FOLLOW_UP, Specialty.GENERAL, PreferredWindow.MORNING, clinic, 1, null);

        Slot morning20 = openSlot(9, 20); // only FOLLOW_UP (20 min) in the morning fits
        waitlistService.autoFill(morning20.getId());
        assertThat(reload(followUp).getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        for (WaitlistEntry e : new WaitlistEntry[]{wrongSpecialty, wrongClinic, wantsAfternoon, urgentNeeds45, routineNeeds30}) {
            assertThat(reload(e).getStatus()).as(e.getPatientName()).isEqualTo(WaitlistStatus.WAITING);
        }

        // URGENT needs TRIAGE: a 45-minute slot in a plain room does not fit it, the morning ROUTINE does.
        Room plain = fx.room(clinic, "EXAM");
        Slot morningPlain45 = fx.slotAt(general, plain, tomorrow.plusHours(9).plusMinutes(30), 45, SlotStatus.OPEN);
        waitlistService.autoFill(morningPlain45.getId());
        assertThat(reload(urgentNeeds45).getStatus()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(reload(routineNeeds30).getStatus()).isEqualTo(WaitlistStatus.OFFERED);

        Slot afternoon = openSlot(15, 30);
        waitlistService.autoFill(afternoon.getId());
        assertThat(reload(wantsAfternoon).getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        assertThat(reload(wrongClinic).getStatus()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(reload(wrongSpecialty).getStatus()).isEqualTo(WaitlistStatus.WAITING);
    }

    @Test
    void entryWithoutClinicMatchesAnyClinic() {
        Provider pediatric = fx.provider(clinic, Specialty.PEDIATRIC);
        Room kids = fx.room(clinic, "EXAM", "PEDIATRIC", "TRIAGE");
        // an entry without clinic that has waited longer than anyone else (incl. seed data) of the same specialty
        WaitlistEntry anyClinic = entry(AppointmentType.FOLLOW_UP, Specialty.PEDIATRIC, PreferredWindow.ANY, null, 24 * 365, null);
        Slot s = fx.slotAt(pediatric, kids, tomorrow.plusHours(10), 30, SlotStatus.OPEN);
        waitlistService.autoFill(s.getId());
        assertThat(reload(anyClinic).getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        waitlistService.decline(anyClinic.getId()); // leave nothing behind that could match other tests
    }

    @Test
    void noOfferForUnsuitableSlots() {
        WaitlistEntry e = entry(AppointmentType.ROUTINE, 5);
        Slot booked = fx.slotAt(general, triageRoom, tomorrow.plusHours(10), 30, SlotStatus.BOOKED);
        Slot past = fx.slotAt(general, triageRoom, LocalDateTime.now().minusHours(1), 30, SlotStatus.OPEN);
        Provider off = fx.provider(clinic, Specialty.GENERAL, ProviderStatus.OFFLINE, 5);
        Slot offline = fx.slotAt(off, triageRoom, tomorrow.plusHours(10), 30, SlotStatus.OPEN);
        for (String id : new String[]{booked.getId(), past.getId(), offline.getId(), "SLT-NOPE"}) {
            waitlistService.autoFill(id);
        }
        assertThat(reload(e).getStatus()).isEqualTo(WaitlistStatus.WAITING);

        Slot specialistSlot = fx.slotAt(fx.provider(clinic, Specialty.SPECIALIST), triageRoom, tomorrow.plusHours(10), 30, SlotStatus.OPEN);
        waitlistService.autoFill(specialistSlot.getId());
        assertThat(reload(e).getStatus()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(activityRepo.findTop100ByOrderByIdDesc()).anyMatch(a -> a.getMessage().contains(specialistSlot.getId())
                && a.getMessage().contains("no matching waiting patient"));
    }

    @Test
    void aSlotIsOfferedToOnlyOneEntryAtATime() {
        WaitlistEntry first = entry(AppointmentType.ROUTINE, 9);
        WaitlistEntry second = entry(AppointmentType.ROUTINE, 8);
        Slot s = openSlot(10, 30);
        waitlistService.autoFill(s.getId());
        waitlistService.autoFill(s.getId());
        assertThat(reload(first).getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        assertThat(reload(second).getStatus()).isEqualTo(WaitlistStatus.WAITING);
    }

    @Test
    void acceptBooksTheOfferedSlotTransactionallyAndNotifies() {
        WaitlistEntry e = entry(AppointmentType.ROUTINE, Specialty.GENERAL, PreferredWindow.ANY, clinic, 3, "+1 555 0101");
        Slot s = openSlot(10, 30);
        waitlistService.autoFill(s.getId());

        WaitlistView v = waitlistService.accept(e.getId());
        assertThat(v.status()).isEqualTo(WaitlistStatus.BOOKED);
        assertThat(v.bookedAppointmentId()).startsWith("APT-");
        assertThat(v.offeredSlot().id()).isEqualTo(s.getId());
        assertThat(fx.slotStatus(s.getId())).isEqualTo(SlotStatus.BOOKED);
        Appointment a = appointments.findDetailed(v.bookedAppointmentId()).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(a.getSlot().getId()).isEqualTo(s.getId());
        assertThat(a.getProvider().getId()).isEqualTo(general.getId());
        assertThat(a.getPatientName()).isEqualTo(e.getPatientName());
        assertThat(recorder.of(WaitlistBooked.class)).anyMatch(x -> x.entryId().equals(e.getId()) && x.appointmentId().equals(a.getId()));

        Fixture.await("waitlist notification", () -> notifications.findAllByOrderByIdDesc().stream()
                .anyMatch(n -> n.getAppointmentId().equals(a.getId())));
        Notification n = notifications.findAllByOrderByIdDesc().stream().filter(x -> x.getAppointmentId().equals(a.getId())).findFirst().orElseThrow();
        assertThat(n.getChannel()).isEqualTo(NotificationChannel.SMS);
        assertThat(n.getMessage()).contains("is confirmed for");

        assertThatThrownBy(() -> waitlistService.accept(e.getId())).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void acceptWhenTheOfferedSlotWasTakenIsSlotUnavailableAndKeepsTheOffer() {
        WaitlistEntry e = entry(AppointmentType.ROUTINE, 3);
        Slot s = openSlot(10, 30);
        waitlistService.autoFill(s.getId());
        appointmentService.book(new BookRequest("Walk-in", AppointmentType.ROUTINE, general.getId(), s.getId(), null));
        long before = appointments.count();

        assertThatThrownBy(() -> waitlistService.accept(e.getId())).isInstanceOf(SlotUnavailableException.class);
        assertThat(reload(e).getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        assertThat(appointments.count()).isEqualTo(before);
    }

    @Test
    void acceptWhenProviderWentOfflineIsSlotUnavailable() {
        WaitlistEntry e = entry(AppointmentType.ROUTINE, 3);
        Slot s = openSlot(10, 30);
        waitlistService.autoFill(s.getId());
        providerService.changeStatus(general.getId(), ProviderStatus.OFFLINE);
        assertThatThrownBy(() -> waitlistService.accept(e.getId())).isInstanceOf(SlotUnavailableException.class);
        assertThat(fx.slotStatus(s.getId())).isEqualTo(SlotStatus.OPEN);
    }

    @Test
    void acceptOfAnOfferWhoseTimeHasPassedIsSlotUnavailable() {
        WaitlistEntry e = entry(AppointmentType.ROUTINE, 3);
        Slot s = openSlot(10, 30);
        waitlistService.autoFill(s.getId());
        assertThat(reload(e).getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        // the offer sat unconfirmed until the slot's time went by
        tx.executeWithoutResult(t -> org.springframework.test.util.ReflectionTestUtils.setField(
                slots.findById(s.getId()).orElseThrow(), "startTime", LocalDateTime.now().minusMinutes(5)));
        long before = appointments.count();

        assertThatThrownBy(() -> waitlistService.accept(e.getId())).isInstanceOf(SlotUnavailableException.class);
        assertThat(fx.slotStatus(s.getId())).isEqualTo(SlotStatus.OPEN);
        assertThat(reload(e).getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        assertThat(appointments.count()).isEqualTo(before);
    }

    @Test
    void acceptAndDeclineNeedAnOffer() {
        WaitlistEntry e = entry(AppointmentType.ROUTINE, 3);
        assertThatThrownBy(() -> waitlistService.accept(e.getId())).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> waitlistService.decline(e.getId())).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> waitlistService.accept("WL-NOPE")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> waitlistService.decline("WL-NOPE")).isInstanceOf(NotFoundException.class);
        assertThat(reload(e).getStatus()).isEqualTo(WaitlistStatus.WAITING);
    }

    @Test
    void declineOffersTheSlotToTheNextMatchingEntry() {
        WaitlistEntry first = entry(AppointmentType.ROUTINE, 9);
        WaitlistEntry next = entry(AppointmentType.ROUTINE, 8);
        Slot s = openSlot(10, 30);
        waitlistService.autoFill(s.getId());
        assertThat(reload(first).getStatus()).isEqualTo(WaitlistStatus.OFFERED);

        WaitlistView v = waitlistService.decline(first.getId());
        assertThat(v.status()).isEqualTo(WaitlistStatus.DECLINED);
        assertThat(recorder.of(SlotFreed.class)).anyMatch(x -> x.slotId().equals(s.getId()));
        Fixture.await("offer moves to next entry", () -> reload(next).getStatus() == WaitlistStatus.OFFERED);
        assertThat(offeredSlotId(next)).isEqualTo(s.getId());
        assertThat(reload(first).getStatus()).isEqualTo(WaitlistStatus.DECLINED);
    }

    @Test
    void slotFreedByAcceptedSuggestionIsAutoOfferedAfterCommit() {
        Fixture.World w = fx.world();
        WaitlistEntry e = entry(AppointmentType.ROUTINE, Specialty.GENERAL, PreferredWindow.ANY, w.clinic(), 4, null);
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "STAFF");
        Fixture.await("freed slot offered to waitlist", () -> reload(e).getStatus() == WaitlistStatus.OFFERED);
        assertThat(offeredSlotId(e)).isEqualTo(w.booked().getId());
    }

    @Test
    void addValidatesAndList() {
        WaitlistView v = waitlistService.add(new WaitlistRequest(" New Person ", "n@example.com", AppointmentType.FOLLOW_UP,
                Specialty.SPECIALIST, null, clinic.getId()));
        assertThat(v.id()).startsWith("WL-");
        assertThat(v.patientName()).isEqualTo("New Person");
        assertThat(v.preferredWindow()).isEqualTo(PreferredWindow.ANY);
        assertThat(v.status()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(v.clinicId()).isEqualTo(clinic.getId());
        assertThat(v.offeredSlot()).isNull();
        assertThat(waitlistService.list()).extracting(WaitlistView::id).contains(v.id());
        assertThat(waitlistService.list()).extracting(WaitlistView::createdAt).isSorted();

        WaitlistView noClinic = waitlistService.add(new WaitlistRequest("Nc", null, AppointmentType.ROUTINE, Specialty.SPECIALIST,
                PreferredWindow.MORNING, " "));
        assertThat(noClinic.clinicId()).isNull();
        // Take the clinic-less entry out of the WAITING pool again so it cannot match other tests' slots.
        Provider specialist = fx.provider(clinic, Specialty.SPECIALIST);
        waitlistService.autoFill(fx.slotAt(specialist, triageRoom, tomorrow.plusHours(8), 30, SlotStatus.OPEN).getId());
        assertThat(waitlist.findById(v.id()).orElseThrow().getStatus()).isEqualTo(WaitlistStatus.OFFERED); // waited longest
        waitlistService.autoFill(fx.slotAt(specialist, triageRoom, tomorrow.plusHours(9), 30, SlotStatus.OPEN).getId());
        assertThat(waitlist.findById(noClinic.id()).orElseThrow().getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        waitlistService.decline(noClinic.id());

        assertThatThrownBy(() -> waitlistService.add(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> waitlistService.add(new WaitlistRequest(" ", null, AppointmentType.ROUTINE, Specialty.GENERAL, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> waitlistService.add(new WaitlistRequest("X", null, null, Specialty.GENERAL, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> waitlistService.add(new WaitlistRequest("X", null, AppointmentType.ROUTINE, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> waitlistService.add(new WaitlistRequest("X", null, AppointmentType.ROUTINE, Specialty.GENERAL, null, "CLN-NOPE")))
                .isInstanceOf(NotFoundException.class);
    }
}
