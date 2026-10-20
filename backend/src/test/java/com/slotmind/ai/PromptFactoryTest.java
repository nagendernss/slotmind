package com.slotmind.ai;

import com.slotmind.domain.*;
import com.slotmind.scheduling.SchedulingContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptFactoryTest {
    private final AiTestFixtures f = new AiTestFixtures();
    private final PromptFactory factory = new PromptFactory();

    @Test
    void offlineAndCancelledPromptsDiffer() {
        String offline = factory.build(f.ctx(TriggerReason.PROVIDER_OFFLINE, List.of(f.chenSoon))).user();
        String cancelled = factory.build(f.ctx(TriggerReason.SLOT_CANCELLED, List.of(f.chenSoon))).user();
        String initial = factory.build(f.ctx(TriggerReason.INITIAL, List.of(f.chenSoon))).user();
        assertThat(offline).isNotEqualTo(cancelled);
        assertThat(offline).contains("unavailable for today").contains("same specialty");
        assertThat(cancelled).contains("was cancelled").contains("SAME doctor");
        assertThat(initial).contains("Staff requested a new time");
    }

    @Test
    void userPromptCarriesContextAndCandidates() {
        String u = factory.build(f.ctx(TriggerReason.SLOT_CANCELLED, List.of(f.chenSoon, f.patelLater))).user();
        assertThat(u).contains("Maria").doesNotContain("Lopez")
                .contains("FOLLOW_UP").contains("AFTERNOON")
                .contains("Dr. Chen (PRV-002)").contains("7/8 booked today (nearly full)")
                .contains("2:30 PM today").contains("Exam Room 1").contains("Downtown Clinic")
                .contains("CANDIDATE_IDS: SLT-005,SLT-007");
        assertThat(factory.build(f.ctx(TriggerReason.INITIAL, List.of())).system()).contains("ONLY from the numbered candidate list").contains("READ IT ALOUD");
    }

    // ---------------------------------------------------------------- trigger differences

    @Test
    void initialPromptIsStaffRequestNotCancellation() {
        String initial = factory.build(f.ctx(TriggerReason.INITIAL, List.of(f.chenSoon))).user();
        String cancelled = factory.build(f.ctx(TriggerReason.SLOT_CANCELLED, List.of(f.chenSoon))).user();
        assertThat(initial).isNotEqualTo(cancelled)
                .contains("Staff requested a new time").contains("SAME doctor")
                .doesNotContain("was cancelled").doesNotContain("unavailable for today");
        assertThat(cancelled).doesNotContain("Staff requested");
    }

    @Test
    void systemPromptIsTheSameForEveryTrigger() {
        for (TriggerReason t : TriggerReason.values()) {
            assertThat(factory.build(f.ctx(t, List.of(f.chenSoon))).system()).isEqualTo(PromptFactory.SYSTEM_PROMPT);
        }
        assertThat(PromptFactory.SYSTEM_PROMPT).contains("never guess").contains("OFFER").contains("JSON");
    }

    @Test
    void nullTriggerIsTreatedAsInitial() {
        SchedulingContext c = ctx(null, List.of(f.chenSoon), PreferredWindow.ANY, Map.of(), false, f.appointment);
        assertThat(factory.userPrompt(c)).contains("Staff requested a new time");
    }

    @Test
    void cancelledPutsSameDoctorFirstOfflinePutsSpecialtyThenWindowFirst() {
        Slot patelMorning = new Slot("SLT-020", f.patel, f.room, NOW.plusMinutes(90), 30, SlotStatus.OPEN);        // 10:30
        Slot chenAfternoon = new Slot("SLT-021", f.chen, f.room, NOW.plusHours(5), 30, SlotStatus.OPEN);            // 14:00
        Slot kimAfternoon = new Slot("SLT-022", kim, f.room, NOW.plusHours(4), 30, SlotStatus.OPEN);                // 13:00
        List<Slot> cands = List.of(kimAfternoon, chenAfternoon, patelMorning);

        List<Slot> cancelled = factory.order(ctx(TriggerReason.SLOT_CANCELLED, cands, PreferredWindow.AFTERNOON, Map.of(), false, f.appointment));
        assertThat(cancelled).extracting(Slot::getId).containsExactly("SLT-020", "SLT-022", "SLT-021");

        List<Slot> offline = factory.order(ctx(TriggerReason.PROVIDER_OFFLINE, cands, PreferredWindow.AFTERNOON, Map.of(), false, f.appointment));
        assertThat(offline).extracting(Slot::getId).containsExactly("SLT-021", "SLT-020", "SLT-022");

        String user = factory.userPrompt(ctx(TriggerReason.PROVIDER_OFFLINE, cands, PreferredWindow.AFTERNOON, Map.of(), false, f.appointment));
        assertThat(user).contains("CANDIDATE_IDS: SLT-021,SLT-020,SLT-022")
                .contains("1. slotId=SLT-021").contains("2. slotId=SLT-020").contains("3. slotId=SLT-022");
    }

    @Test
    void orderWithAnyWindowIsEarliestFirstAndDoesNotMutateInput() {
        List<Slot> cands = List.of(f.patelLater, f.chenSoon);
        List<Slot> ordered = factory.order(ctx(TriggerReason.PROVIDER_OFFLINE, cands, null, Map.of(), false, f.appointment));
        assertThat(ordered).containsExactly(f.chenSoon, f.patelLater);
        assertThat(cands).containsExactly(f.patelLater, f.chenSoon);
    }

    // ---------------------------------------------------------------- appointment / preference section

    @Test
    void urgencyLine() {
        Appointment urgent = new Appointment("APT-009", "Ann Lee", null, AppointmentType.URGENT, f.patel, f.original,
                AppointmentStatus.RESCHEDULE_PENDING);
        assertThat(factory.userPrompt(ctx(TriggerReason.SLOT_CANCELLED, List.of(f.chenSoon), PreferredWindow.ANY, Map.of(), false, urgent)))
                .contains("Visit type: URGENT (urgent problem)").contains("Urgency: HIGH");
        assertThat(factory.build(f.ctx(TriggerReason.SLOT_CANCELLED, List.of(f.chenSoon))).user())
                .contains("Visit type: FOLLOW_UP (follow-up on an earlier visit, continuity matters)").contains("Urgency: normal");
        Appointment routine = new Appointment("APT-010", "Bo", null, AppointmentType.ROUTINE, f.patel, f.original,
                AppointmentStatus.RESCHEDULE_PENDING);
        assertThat(factory.userPrompt(ctx(TriggerReason.SLOT_CANCELLED, List.of(f.chenSoon), PreferredWindow.ANY, Map.of(), false, routine)))
                .contains("(regular check-up)");
    }

    @Test
    void preferredWindowLines() {
        assertThat(factory.userPrompt(ctx(TriggerReason.INITIAL, List.of(f.chenSoon), PreferredWindow.MORNING, Map.of(), false, f.appointment)))
                .contains("MORNING (before noon)");
        assertThat(factory.userPrompt(ctx(TriggerReason.INITIAL, List.of(f.chenSoon), PreferredWindow.ANY, Map.of(), false, f.appointment)))
                .contains("Patient preferred time of day: no preference");
        assertThat(factory.userPrompt(ctx(TriggerReason.INITIAL, List.of(f.chenSoon), null, Map.of(), false, f.appointment)))
                .contains("Patient preferred time of day: no preference");
    }

    @Test
    void originalSlotProviderAndClinicAreDescribed() {
        String u = factory.build(f.ctx(TriggerReason.PROVIDER_OFFLINE, List.of(f.chenSoon))).user();
        assertThat(u).contains("Original time: 10:00 AM today")
                .contains("Original provider: Dr. Patel (PRV-001), GENERAL, status AVAILABLE")
                .contains("Patient's clinic: Downtown Clinic")
                .contains("ORIGINAL_PROVIDER_ID: PRV-001")
                .contains("SLOT_PROVIDER: SLT-007=PRV-002");
    }

    @Test
    void crossClinicNoteOnlyWhenWidened() {
        assertThat(factory.userPrompt(ctx(TriggerReason.SLOT_CANCELLED, List.of(f.chenSoon), PreferredWindow.ANY, Map.of(), true, f.appointment)))
                .contains("NOTE: no suitable time exists at the patient's own clinic").contains("Mention the clinic name");
        assertThat(factory.build(f.ctx(TriggerReason.SLOT_CANCELLED, List.of(f.chenSoon))).user()).doesNotContain("NOTE:");
    }

    @Test
    void sameDoctorMarkerOnlyOnOriginalProvider() {
        String u = factory.build(f.ctx(TriggerReason.SLOT_CANCELLED, List.of(f.chenSoon, f.patelLater))).user();
        assertThat(u).contains("Dr. Patel (PRV-001), GENERAL, SAME DOCTOR").doesNotContain("Dr. Chen (PRV-002), GENERAL, SAME DOCTOR");
    }

    @Test
    void missingNameRoomClinicAndLoadAreHandled() {
        Provider noClinic = new Provider("PRV-030", "Dr. Solo", Specialty.GENERAL, ProviderStatus.AVAILABLE, null, 8);
        Slot bare = new Slot("SLT-030", noClinic, null, NOW.plusHours(1), 20, SlotStatus.OPEN);
        Appointment nameless = new Appointment("APT-030", "  ", null, AppointmentType.ROUTINE, noClinic,
                new Slot("SLT-031", noClinic, null, NOW, 20, SlotStatus.BOOKED), AppointmentStatus.RESCHEDULE_PENDING);
        String u = factory.userPrompt(ctx(TriggerReason.INITIAL, List.of(bare), PreferredWindow.ANY, null, false, nameless));
        assertThat(u).contains("Patient first name: the patient")
                .contains("| 20 min").contains("| room n/a").doesNotContain("| clinic ")
                .contains("load unknown").doesNotContain("Patient's clinic");
    }

    @Test
    void clinicFallsBackToProviderClinicWhenNoRoom() {
        Slot noRoom = new Slot("SLT-040", f.chen, null, NOW.plusHours(1), 30, SlotStatus.OPEN);
        assertThat(factory.build(f.ctx(TriggerReason.INITIAL, List.of(noRoom))).user()).contains("| room n/a | clinic Downtown Clinic");
    }

    @Test
    void loadThresholds() {
        Provider zero = new Provider("PRV-031", "Dr. Zero", Specialty.GENERAL, ProviderStatus.AVAILABLE, f.clinic, 0);
        Slot zs = new Slot("SLT-050", zero, f.room, NOW.plusHours(1), 30, SlotStatus.OPEN);
        String u = factory.userPrompt(ctx(TriggerReason.INITIAL, List.of(f.chenSoon, f.patelLater, zs), PreferredWindow.ANY,
                Map.of("PRV-001", 6, "PRV-002", 8, "PRV-031", 0), false, f.appointment));
        assertThat(u).contains("6/8 booked today\n").doesNotContain("6/8 booked today (nearly full)")
                .contains("8/8 booked today (nearly full)")
                .contains("0/0 booked today\n");
    }

    @Test
    void humanTimes() {
        assertThat(PromptFactory.human(null, NOW)).isEqualTo("unknown time");
        assertThat(PromptFactory.human(NOW.plusHours(3), NOW)).isEqualTo("12:00 PM today");
        assertThat(PromptFactory.human(NOW.plusDays(1), NOW)).isEqualTo("9:00 AM tomorrow");
        assertThat(PromptFactory.human(NOW.plusDays(2), NOW)).isEqualTo("9:00 AM Thu Oct 22");
    }

    @Test
    void defensiveNullsDoNotCrash() {
        Appointment bare = new Appointment("APT-060", "Kai", null, null, null, null, AppointmentStatus.RESCHEDULE_PENDING);
        Slot orphan = new Slot("SLT-060", null, null, null, 30, SlotStatus.OPEN);
        Slot timed = new Slot("SLT-061", f.chen, null, NOW.plusHours(2), 30, SlotStatus.OPEN);
        for (TriggerReason t : TriggerReason.values()) {
            String u = factory.userPrompt(ctx(t, List.of(orphan, timed), PreferredWindow.MORNING, Map.of(), false, bare));
            assertThat(u).as(t.name()).contains("slotId=SLT-060 | unknown time | 30 min | room n/a\n")
                    .contains("Visit type: null ()").doesNotContain("Original time").doesNotContain("ORIGINAL_PROVIDER_ID")
                    .doesNotContain("SLOT_PROVIDER: SLT-060").contains("SLOT_PROVIDER: SLT-061=PRV-002")
                    .contains("CANDIDATE_IDS: ");
        }
    }

    @Test
    void nullNowInContextUsesClock() {
        SchedulingContext c = new SchedulingContext(f.appointment, TriggerReason.INITIAL, List.of(f.chenSoon), List.of(),
                PreferredWindow.ANY, Map.of(), false, null);
        assertThat(factory.userPrompt(c)).contains("CANDIDATE_IDS: SLT-007");
    }

    // ---------------------------------------------------------------- helpers

    private static final LocalDateTime NOW = AiTestFixtures.NOW;
    private final Provider kim = new Provider("PRV-003", "Dr. Kim", Specialty.SPECIALIST, ProviderStatus.AVAILABLE, f.clinic, 8);

    private SchedulingContext ctx(TriggerReason t, List<Slot> cands, PreferredWindow w, Map<String, Integer> load,
                                  boolean cross, Appointment a) {
        return new SchedulingContext(a, t, cands, List.of(), w, load, cross, NOW);
    }
}
