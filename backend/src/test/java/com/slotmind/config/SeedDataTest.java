package com.slotmind.config;

import com.slotmind.domain.*;
import com.slotmind.repository.*;
import com.slotmind.service.ProviderService;
import com.slotmind.support.Fixture;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seed data (decision.md §13 / Addendum A). Runs in its own context (distinct property => separate cached context
 * and database), so the demo invariant sees untouched seed data without @DirtiesContext.
 */
@SpringBootTest(properties = "slotmind.test.context=seed-data")
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedDataTest {

    @Autowired SeedData seedData;
    @Autowired ProviderRepository providers;
    @Autowired SlotRepository slots;
    @Autowired AppointmentRepository appointments;
    @Autowired SuggestionRepository suggestions;
    @Autowired ClinicRepository clinics;
    @Autowired RoomRepository rooms;
    @Autowired WaitlistRepository waitlist;
    @Autowired AppointmentTypeRuleRepository typeRules;
    @Autowired PatientPreferenceRepository preferences;
    @Autowired ActivityRepository activity;
    @Autowired ProviderService providerService;
    @Autowired TransactionTemplate tx;

    private record P(String name, Specialty specialty, ProviderStatus status) {}
    private record S(String provider, int minutesAfterSlt001, int duration, SlotStatus status) {}
    private record A(String patient, AppointmentType type, String provider, String slot) {}

    @Test
    @Order(1)
    void addendumAProvidersSlotsAndAppointmentsHaveExactIdsAndStatuses() {
        Map<String, P> expectedProviders = Map.of(
                "PRV-001", new P("Dr. Sarah Chen", Specialty.GENERAL, ProviderStatus.BUSY),
                "PRV-002", new P("Dr. James Okonkwo", Specialty.SPECIALIST, ProviderStatus.AVAILABLE),
                "PRV-003", new P("Dr. Priya Sharma", Specialty.PEDIATRIC, ProviderStatus.BUSY),
                "PRV-004", new P("Dr. Rahul Verma", Specialty.GENERAL, ProviderStatus.AVAILABLE),
                "PRV-005", new P("Dr. Ananya Iyer", Specialty.SPECIALIST, ProviderStatus.BUSY));
        expectedProviders.forEach((id, p) -> {
            Provider actual = providers.findById(id).orElseThrow();
            assertThat(new P(actual.getName(), actual.getSpecialty(), actual.getStatus())).as(id).isEqualTo(p);
        });

        // Addendum A: now+60, +120, +90, +180, +150, +240
        Map<String, S> expectedSlots = Map.of(
                "SLT-001", new S("PRV-001", 0, 30, SlotStatus.BOOKED),
                "SLT-002", new S("PRV-001", 60, 30, SlotStatus.BOOKED),
                "SLT-003", new S("PRV-003", 30, 45, SlotStatus.BOOKED),
                "SLT-004", new S("PRV-002", 120, 30, SlotStatus.OPEN),
                "SLT-005", new S("PRV-004", 90, 30, SlotStatus.OPEN),
                "SLT-006", new S("PRV-005", 180, 60, SlotStatus.BOOKED));
        LocalDateTime base = slots.findById("SLT-001").orElseThrow().getStartTime();
        assertThat(Duration.between(LocalDateTime.now(), base).toMinutes()).isBetween(50L, 61L); // seeded at start-up, now+60
        expectedSlots.forEach((id, s) -> {
            Slot actual = slots.findDetailed(id).orElseThrow();
            assertThat(new S(actual.getProvider().getId(), (int) Duration.between(base, actual.getStartTime()).toMinutes(),
                    actual.getDurationMinutes(), actual.getStatus())).as(id).isEqualTo(s);
        });

        Map<String, A> expectedAppointments = Map.of(
                "APT-001", new A("John Martinez", AppointmentType.ROUTINE, "PRV-001", "SLT-001"),
                "APT-002", new A("Emily Watson", AppointmentType.FOLLOW_UP, "PRV-001", "SLT-002"),
                "APT-003", new A("Aisha Patel", AppointmentType.URGENT, "PRV-003", "SLT-003"),
                "APT-004", new A("Robert Kim", AppointmentType.ROUTINE, "PRV-005", "SLT-006"));
        expectedAppointments.forEach((id, a) -> {
            Appointment actual = appointments.findDetailed(id).orElseThrow();
            assertThat(new A(actual.getPatientName(), actual.getType(), actual.getProvider().getId(), actual.getSlot().getId()))
                    .as(id).isEqualTo(a);
            assertThat(actual.getStatus()).as(id).isEqualTo(AppointmentStatus.CONFIRMED);
        });

        // every BOOKED seed slot (besides history) has exactly one appointment; every OPEN one none
        for (Slot s : slots.findAll()) {
            boolean hasAppt = appointments.findBySlotId(s.getId()).isPresent();
            assertThat(hasAppt).as(s.getId()).isEqualTo(s.getStatus() == SlotStatus.BOOKED);
        }
    }

    @Test
    @Order(2)
    void supportingSeedForEverySprintFeature() {
        assertThat(clinics.findAll()).extracting(Clinic::getId).containsExactlyInAnyOrder("CLN-001", "CLN-002");
        assertThat(rooms.count()).isGreaterThanOrEqualTo(4);
        assertThat(providers.count()).isEqualTo(7);
        assertThat(slots.count()).isEqualTo(17);
        assertThat(appointments.count()).isEqualTo(5);
        assertThat(appointments.findById("APT-090").orElseThrow().getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(typeRules.findById(AppointmentType.ROUTINE).orElseThrow().getDurationMinutes()).isEqualTo(30);
        assertThat(typeRules.findById(AppointmentType.FOLLOW_UP).orElseThrow().getDurationMinutes()).isEqualTo(20);
        AppointmentTypeRule urgent = typeRules.findById(AppointmentType.URGENT).orElseThrow();
        assertThat(urgent.getDurationMinutes()).isEqualTo(45);
        assertThat(urgent.getRequiredEquipment()).containsExactly("TRIAGE");
        assertThat(waitlist.findAll()).extracting(WaitlistEntry::getId).containsExactlyInAnyOrder("WL-001", "WL-002");
        assertThat(waitlist.findAll()).allMatch(w -> w.getStatus() == WaitlistStatus.WAITING);
        PatientPreference emily = preferences.findById("Emily Watson").orElseThrow();
        assertThat(emily.getPreferredWindow()).isEqualTo(PreferredWindow.AFTERNOON);
        assertThat(emily.getSource()).isEqualTo(PreferenceSource.EXPLICIT);
        assertThat(suggestions.count()).isZero();
    }

    @Test
    @Order(3)
    void seedingIsIdempotent() throws Exception {
        long p = providers.count(), s = slots.count(), a = appointments.count(), w = waitlist.count();
        seedData.run();
        assertThat(List.of(providers.count(), slots.count(), appointments.count(), waitlist.count())).containsExactly(p, s, a, w);
    }

    @Test
    @Order(4)
    void demoInvariantProviderOneOfflineGivesExactlyTwoSuggestions() {
        providerService.changeStatus("PRV-001", ProviderStatus.OFFLINE);
        Fixture.await("loop finished", () -> activity.findTop100ByOrderByIdDesc().stream()
                .anyMatch(e -> e.getMessage().contains("new suggestion(s) waiting")));

        List<RescheduleSuggestion> all = tx.execute(t -> suggestions.search(null));
        assertThat(all).hasSize(2);
        assertThat(all).extracting(g -> g.getAppointment().getId()).containsExactlyInAnyOrder("APT-001", "APT-002");
        assertThat(all).allSatisfy(g -> {
            assertThat(g.getStatus()).isEqualTo(SuggestionStatus.PENDING);
            assertThat(g.getTriggerReason()).isEqualTo(TriggerReason.PROVIDER_OFFLINE);
            assertThat(g.getRecommendedSlot()).isNotNull();
            assertThat(g.getRecommendedSlot().getProvider().getId()).isEqualTo("PRV-004"); // same specialty, same clinic
            assertThat(g.isFallbackUsed()).isFalse();
            assertThat(g.isCrossClinic()).isFalse();
        });
        assertThat(all.get(0).getRecommendedSlot().getId()).isNotEqualTo(all.get(1).getRecommendedSlot().getId());
        assertThat(appointments.findById("APT-001").orElseThrow().getStatus()).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
        assertThat(appointments.findById("APT-002").orElseThrow().getStatus()).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
        assertThat(appointments.findById("APT-003").orElseThrow().getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(appointments.findById("APT-004").orElseThrow().getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
    }
}
