package com.slotmind.repository;

import com.slotmind.domain.*;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Custom @Query methods, fetch joins and unique constraints, against a clean schema (no seed data in this slice). */
@DataJpaTest
class RepositoryQueriesTest {

    @Autowired TestEntityManager em;
    @Autowired SlotRepository slots;
    @Autowired AppointmentRepository appointments;
    @Autowired SuggestionRepository suggestions;
    @Autowired WaitlistRepository waitlist;
    @Autowired ProviderRepository providers;
    @Autowired RoomRepository rooms;
    @Autowired ActivityRepository activity;
    @Autowired NotificationRepository notifications;

    LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
    Clinic clinic;
    Room room;
    Provider p1, p2;
    Slot s1Booked, s2Booked, s3Open, s4Open, sPastOpen, sCancelled;
    Appointment a1, a2;

    @BeforeEach
    void data() {
        clinic = em.persist(new Clinic("CLN-1", "Main"));
        room = em.persist(new Room("RM-1", "Room 1", clinic, Set.of("EXAM")));
        p1 = em.persist(new Provider("PRV-1", "Dr. One", Specialty.GENERAL, ProviderStatus.AVAILABLE, clinic, 8));
        p2 = em.persist(new Provider("PRV-2", "Dr. Two", Specialty.GENERAL, ProviderStatus.BUSY, null, 8));
        // inserted out of time order on purpose
        s2Booked = em.persist(new Slot("SLT-2", p1, room, now.plusHours(2), 30, SlotStatus.BOOKED));
        s1Booked = em.persist(new Slot("SLT-1", p1, room, now.plusHours(1), 30, SlotStatus.BOOKED));
        s4Open = em.persist(new Slot("SLT-4", p2, null, now.plusHours(4), 30, SlotStatus.OPEN));
        s3Open = em.persist(new Slot("SLT-3", p2, room, now.plusHours(3), 30, SlotStatus.OPEN));
        sPastOpen = em.persist(new Slot("SLT-0", p2, room, now.minusHours(1), 30, SlotStatus.OPEN));
        sCancelled = em.persist(new Slot("SLT-5", p2, room, now.plusHours(5), 30, SlotStatus.CANCELLED));
        a2 = em.persist(new Appointment("APT-2", "Bea", "b@x.com", AppointmentType.ROUTINE, p1, s2Booked, AppointmentStatus.CONFIRMED));
        a1 = em.persist(new Appointment("APT-1", "Al", null, AppointmentType.URGENT, p1, s1Booked, AppointmentStatus.RESCHEDULE_PENDING));
        em.flush();
        em.clear();
    }

    private RescheduleSuggestion suggestion(Appointment a, Slot target, TriggerReason trigger, LocalDateTime createdAt) {
        Appointment managed = em.find(Appointment.class, a.getId());
        Slot t = target == null ? null : em.find(Slot.class, target.getId());
        RescheduleSuggestion g = new RescheduleSuggestion(managed, t, null, 0.5, "ok", trigger, "RULE_BASED", false, false);
        ReflectionTestUtils.setField(g, "createdAt", createdAt);
        return em.persistAndFlush(g);
    }

    // ---------------------------------------------------------------- SlotRepository

    @Test
    void slotSearchFiltersAndOrdersByStartTimeThenId() {
        assertThat(slots.search(null, null)).extracting(Slot::getId).containsExactly("SLT-0", "SLT-1", "SLT-2", "SLT-3", "SLT-4", "SLT-5");
        assertThat(slots.search("PRV-1", null)).extracting(Slot::getId).containsExactly("SLT-1", "SLT-2");
        assertThat(slots.search(null, SlotStatus.OPEN)).extracting(Slot::getId).containsExactly("SLT-0", "SLT-3", "SLT-4");
        assertThat(slots.search("PRV-2", SlotStatus.CANCELLED)).extracting(Slot::getId).containsExactly("SLT-5");
        assertThat(slots.search("PRV-NOPE", null)).isEmpty();

        em.persistAndFlush(new Slot("SLT-1b", em.find(Provider.class, "PRV-1"), null, now.plusHours(1), 30, SlotStatus.OPEN));
        em.clear();
        assertThat(slots.search("PRV-1", null)).extracting(Slot::getId).containsExactly("SLT-1", "SLT-1b", "SLT-2");
    }

    @Test
    void slotFetchQueriesReturnInitializedAssociations() {
        Slot s = slots.findDetailed("SLT-1").orElseThrow();
        assertThat(Hibernate.isInitialized(s.getProvider())).isTrue();
        assertThat(Hibernate.isInitialized(s.getProvider().getClinic())).isTrue();
        assertThat(Hibernate.isInitialized(s.getRoom())).isTrue();
        assertThat(Hibernate.isInitialized(s.getRoom().getClinic())).isTrue();
        assertThat(slots.findDetailed("SLT-NOPE")).isEmpty();
        em.clear();
        for (Slot x : slots.search(null, null)) {
            assertThat(Hibernate.isInitialized(x.getProvider())).isTrue();
            if (x.getRoom() != null) assertThat(Hibernate.isInitialized(x.getRoom())).isTrue();
        }
    }

    @Test
    void findOpenAfterOnlyReturnsOpenFutureSlotsInOrder() {
        List<Slot> open = slots.findOpenAfter(now);
        assertThat(open).extracting(Slot::getId).containsExactly("SLT-3", "SLT-4");
        assertThat(Hibernate.isInitialized(open.get(0).getProvider())).isTrue();
        assertThat(slots.findOpenAfter(now.plusHours(3))).extracting(Slot::getId).containsExactly("SLT-4");
    }

    @Test
    void countBookedBetweenGroupsByProvider() {
        LocalDate day = now.toLocalDate();
        em.persistAndFlush(new Slot("SLT-T", em.find(Provider.class, "PRV-2"), null, day.atStartOfDay().plusSeconds(1), 30, SlotStatus.BOOKED));
        List<Object[]> rows = slots.countBookedBetween(now.minusDays(1), now.plusDays(1));
        assertThat(rows).anySatisfy(r -> { assertThat(r[0]).isEqualTo("PRV-1"); assertThat(((Number) r[1]).intValue()).isEqualTo(2); });
        assertThat(rows).anySatisfy(r -> { assertThat(r[0]).isEqualTo("PRV-2"); assertThat(((Number) r[1]).intValue()).isEqualTo(1); });
        assertThat(slots.countBookedBetween(now.plusDays(2), now.plusDays(3))).isEmpty();
    }

    // ---------------------------------------------------------------- AppointmentRepository

    @Test
    void appointmentSearchOrdersBySlotTimeAndFiltersByStatus() {
        assertThat(appointments.search(null)).extracting(Appointment::getId).containsExactly("APT-1", "APT-2");
        assertThat(appointments.search(AppointmentStatus.CONFIRMED)).extracting(Appointment::getId).containsExactly("APT-2");
        assertThat(appointments.search(AppointmentStatus.COMPLETED)).isEmpty();
        Appointment a = appointments.search(null).get(0);
        assertThat(Hibernate.isInitialized(a.getProvider())).isTrue();
        assertThat(Hibernate.isInitialized(a.getSlot())).isTrue();
        assertThat(Hibernate.isInitialized(a.getSlot().getRoom())).isTrue();
        assertThat(Hibernate.isInitialized(a.getSlot().getProvider().getClinic())).isTrue();
    }

    @Test
    void appointmentFindDetailedAndLookups() {
        Appointment a = appointments.findDetailed("APT-1").orElseThrow();
        assertThat(Hibernate.isInitialized(a.getProvider())).isTrue();
        assertThat(Hibernate.isInitialized(a.getSlot())).isTrue();
        assertThat(Hibernate.isInitialized(a.getSlot().getProvider())).isTrue();
        assertThat(appointments.findDetailed("APT-NOPE")).isEmpty();

        assertThat(appointments.findBySlotId("SLT-2")).get().extracting(Appointment::getId).isEqualTo("APT-2");
        assertThat(appointments.findBySlotId("SLT-3")).isEmpty();
        List<Appointment> bySlots = appointments.findBySlotIdIn(List.of("SLT-1", "SLT-2", "SLT-3"));
        assertThat(bySlots).extracting(Appointment::getId).containsExactlyInAnyOrder("APT-1", "APT-2");
        assertThat(Hibernate.isInitialized(bySlots.get(0).getSlot())).isTrue();
    }

    @Test
    void affectedAppointmentIdsAreFilteredByProviderAndStatusSoonestFirst() {
        assertThat(appointments.findIdsByProviderIdAndStatusIn("PRV-1",
                EnumSet.of(AppointmentStatus.CONFIRMED, AppointmentStatus.RESCHEDULE_PENDING))).containsExactly("APT-1", "APT-2");
        assertThat(appointments.findIdsByProviderIdAndStatusIn("PRV-1", EnumSet.of(AppointmentStatus.CONFIRMED))).containsExactly("APT-2");
        assertThat(appointments.findIdsByProviderIdAndStatusIn("PRV-2", EnumSet.allOf(AppointmentStatus.class))).isEmpty();
    }

    @Test
    void findByPatientNameAndStatusFetchesTheSlot() {
        Slot past = em.persist(new Slot("SLT-P", em.find(Provider.class, "PRV-1"), null, now.minusDays(3), 30, SlotStatus.BOOKED));
        Appointment done = new Appointment("APT-P", "Bea", null, AppointmentType.ROUTINE, em.find(Provider.class, "PRV-1"), past,
                AppointmentStatus.CONFIRMED);
        done.complete();
        em.persistAndFlush(done);
        em.clear();
        List<Appointment> list = appointments.findByPatientNameAndStatus("Bea", AppointmentStatus.COMPLETED);
        assertThat(list).extracting(Appointment::getId).containsExactly("APT-P");
        assertThat(Hibernate.isInitialized(list.get(0).getSlot())).isTrue();
        assertThat(appointments.findByPatientNameAndStatus("Nobody", AppointmentStatus.COMPLETED)).isEmpty();
    }

    @Test
    void uniqueSlotConstraintRejectsASecondAppointmentOnTheSameSlot() {
        Appointment dup = new Appointment("APT-DUP", "Dup", null, AppointmentType.ROUTINE, em.find(Provider.class, "PRV-1"),
                em.find(Slot.class, "SLT-1"), AppointmentStatus.CONFIRMED);
        assertThatThrownBy(() -> appointments.saveAndFlush(dup)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- SuggestionRepository

    @Test
    void suggestionSearchAndFetchJoins() {
        RescheduleSuggestion older = suggestion(a1, s3Open, TriggerReason.PROVIDER_OFFLINE, now.minusMinutes(10));
        older.reject("STAFF");
        em.flush();
        RescheduleSuggestion newer = suggestion(a1, s4Open, TriggerReason.PROVIDER_OFFLINE, now.minusMinutes(5));
        RescheduleSuggestion other = suggestion(a2, null, TriggerReason.INITIAL, now.minusMinutes(1));
        em.clear();

        assertThat(suggestions.search(null)).extracting(RescheduleSuggestion::getId)
                .containsExactly(other.getId(), newer.getId(), older.getId());
        assertThat(suggestions.search(SuggestionStatus.PENDING)).extracting(RescheduleSuggestion::getId)
                .containsExactly(other.getId(), newer.getId());
        assertThat(suggestions.findDetailedByAppointmentId("APT-1")).extracting(RescheduleSuggestion::getId)
                .containsExactly(newer.getId(), older.getId());

        em.clear();
        RescheduleSuggestion d = suggestions.findDetailed(newer.getId()).orElseThrow();
        assertThat(Hibernate.isInitialized(d.getAppointment())).isTrue();
        assertThat(Hibernate.isInitialized(d.getOriginalSlot())).isTrue();
        assertThat(Hibernate.isInitialized(d.getOriginalSlot().getProvider())).isTrue();
        assertThat(Hibernate.isInitialized(d.getOriginalSlot().getRoom())).isTrue();
        assertThat(Hibernate.isInitialized(d.getRecommendedSlot())).isTrue();
        assertThat(Hibernate.isInitialized(d.getRecommendedSlot().getProvider())).isTrue();
        assertThat(d.getRecommendedSlot().getRoom()).isNull(); // SLT-4 has no room: left join keeps the row
        assertThat(suggestions.findDetailed(other.getId())).get().extracting(RescheduleSuggestion::getRecommendedSlot).isNull();
        assertThat(suggestions.findDetailed("SUG-NOPE")).isEmpty();
    }

    @Test
    void slotIdQueriesForExclusionAndHolding() {
        RescheduleSuggestion rejected = suggestion(a1, s3Open, TriggerReason.PROVIDER_OFFLINE, now.minusMinutes(9));
        rejected.reject("STAFF");
        em.flush();
        RescheduleSuggestion superseded = suggestion(a1, s4Open, TriggerReason.PROVIDER_OFFLINE, now.minusMinutes(8));
        superseded.supersede();
        em.flush();
        RescheduleSuggestion slotless = suggestion(a1, null, TriggerReason.PROVIDER_OFFLINE, now.minusMinutes(7));
        slotless.supersede();
        em.flush();
        Slot extra = em.persist(new Slot("SLT-6", em.find(Provider.class, "PRV-2"), null, now.plusHours(6), 30, SlotStatus.OPEN));
        RescheduleSuggestion pendingA1 = suggestion(a1, extra, TriggerReason.PROVIDER_OFFLINE, now.minusMinutes(6));
        RescheduleSuggestion pendingA2 = suggestion(a2, s3Open, TriggerReason.INITIAL, now.minusMinutes(5));

        assertThat(suggestions.findRejectedSlotIds("APT-1")).containsExactly("SLT-3");
        assertThat(suggestions.findPreviouslySuggestedSlotIds("APT-1")).containsExactlyInAnyOrder("SLT-3", "SLT-4", "SLT-6");
        assertThat(suggestions.findSlotIdsHeldByOthers("APT-1")).containsExactly("SLT-3"); // a2's pending one
        assertThat(suggestions.findSlotIdsHeldByOthers("APT-2")).containsExactly("SLT-6");
        assertThat(suggestions.findRejectedSlotIds("APT-2")).isEmpty();

        // accepted suggestions are not "previously suggested" (the patient took that slot)
        pendingA2.accept("STAFF");
        em.flush();
        assertThat(suggestions.findPreviouslySuggestedSlotIds("APT-2")).isEmpty();
        assertThat(suggestions.findSlotIdsHeldByOthers("APT-1")).isEmpty();

        assertThat(suggestions.existsByPendingKey("APT-1:PROVIDER_OFFLINE")).isTrue();
        assertThat(suggestions.existsByPendingKey("APT-2:INITIAL")).isFalse();
        assertThat(suggestions.findByAppointmentIdAndStatus("APT-1", SuggestionStatus.PENDING)).extracting(RescheduleSuggestion::getId)
                .containsExactly(pendingA1.getId());
        assertThat(suggestions.findByStatus(SuggestionStatus.SUPERSEDED)).extracting(RescheduleSuggestion::getId)
                .contains(superseded.getId(), slotless.getId());
    }

    @Test
    void pendingKeyIsUniqueButResolvedSuggestionsDoNotCollide() {
        suggestion(a1, s3Open, TriggerReason.PROVIDER_OFFLINE, now);
        Appointment managed = em.find(Appointment.class, "APT-1");
        RescheduleSuggestion dup = new RescheduleSuggestion(managed, null, null, 0.1, "dup", TriggerReason.PROVIDER_OFFLINE,
                "RULE_BASED", false, false);
        assertThatThrownBy(() -> suggestions.saveAndFlush(dup)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void manyResolvedSuggestionsWithNullKeysCoexist() {
        for (int i = 0; i < 3; i++) {
            RescheduleSuggestion g = suggestion(a1, s3Open, TriggerReason.PROVIDER_OFFLINE, now.plusSeconds(i));
            g.reject("STAFF");
            em.flush();
            assertThat(g.getPendingKey()).isNull();
        }
        // a different trigger is a different key
        suggestion(a1, s3Open, TriggerReason.PROVIDER_OFFLINE, now.plusSeconds(10));
        suggestion(a1, s4Open, TriggerReason.SLOT_CANCELLED, now.plusSeconds(11));
        assertThat(suggestions.findByAppointmentIdAndStatus("APT-1", SuggestionStatus.PENDING)).hasSize(2);
    }

    // ---------------------------------------------------------------- other repositories

    @Test
    void waitlistQueries() {
        Clinic c = em.find(Clinic.class, "CLN-1");
        em.persist(new WaitlistEntry("WL-2", "Late", null, AppointmentType.ROUTINE, Specialty.GENERAL, null, null, now.minusHours(1)));
        WaitlistEntry early = em.persist(new WaitlistEntry("WL-1", "Early", null, AppointmentType.ROUTINE, Specialty.GENERAL,
                PreferredWindow.MORNING, c, now.minusHours(5)));
        WaitlistEntry offered = em.persist(new WaitlistEntry("WL-3", "Offered", null, AppointmentType.ROUTINE, Specialty.GENERAL,
                null, c, now.minusHours(9)));
        offered.offer(em.find(Slot.class, "SLT-3"));
        em.flush();
        em.clear();

        List<WaitlistEntry> all = waitlist.findAllDetailed();
        assertThat(all).extracting(WaitlistEntry::getId).containsExactly("WL-3", "WL-1", "WL-2");
        assertThat(Hibernate.isInitialized(all.get(0).getOfferedSlot())).isTrue();
        assertThat(Hibernate.isInitialized(all.get(0).getOfferedSlot().getProvider())).isTrue();
        assertThat(Hibernate.isInitialized(all.get(1).getClinic())).isTrue();
        assertThat(waitlist.findByStatusOrderByCreatedAtAsc(WaitlistStatus.WAITING)).extracting(WaitlistEntry::getId)
                .containsExactly("WL-1", "WL-2");
        assertThat(waitlist.existsByOfferedSlotIdAndStatus("SLT-3", WaitlistStatus.OFFERED)).isTrue();
        assertThat(waitlist.existsByOfferedSlotIdAndStatus("SLT-4", WaitlistStatus.OFFERED)).isFalse();
        assertThat(waitlist.existsByOfferedSlotIdAndStatus("SLT-3", WaitlistStatus.BOOKED)).isFalse();
        assertThat(early.getPreferredWindow()).isEqualTo(PreferredWindow.MORNING);
    }

    @Test
    void providerRoomActivityAndNotificationQueries() {
        List<Provider> ps = providers.findAllWithClinic();
        assertThat(ps).extracting(Provider::getId).containsExactly("PRV-1", "PRV-2");
        assertThat(Hibernate.isInitialized(ps.get(0).getClinic())).isTrue();
        assertThat(ps.get(1).getClinic()).isNull();

        em.persistAndFlush(new Room("RM-0", "Room 0", null, Set.of()));
        em.clear();
        List<Room> rs = rooms.findAllWithClinic();
        assertThat(rs).extracting(Room::getId).containsExactly("RM-0", "RM-1");
        assertThat(Hibernate.isInitialized(rs.get(1).getClinic())).isTrue();

        for (int i = 0; i < 105; i++) em.persist(new ActivityEvent(ActivityKind.INFO, "e" + i));
        em.flush();
        List<ActivityEvent> top = activity.findTop100ByOrderByIdDesc();
        assertThat(top).hasSize(100);
        assertThat(top.get(0).getMessage()).isEqualTo("e104");
        assertThat(top).extracting(ActivityEvent::getId).isSortedAccordingTo((x, y) -> Long.compare(y, x));

        em.persist(new Notification("APT-1", NotificationChannel.LOG, "desk", "first"));
        em.persist(new Notification("APT-1", NotificationChannel.SMS, "+1", "second"));
        em.flush();
        assertThat(notifications.findAllByOrderByIdDesc()).extracting(Notification::getMessage).containsExactly("second", "first");
    }
}
