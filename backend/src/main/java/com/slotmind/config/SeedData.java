package com.slotmind.config;

import com.slotmind.domain.*;
import com.slotmind.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * decision.md §13: Addendum A providers/slots/appointments with the exact ids and now+N times, plus a second clinic,
 * rooms, type rules, extra open slots, a waitlist, one explicit preference and one completed visit for inference.
 * Demo: PATCH /providers/PRV-001/status OFFLINE -> exactly two suggestions (APT-001, APT-002).
 */
@Component
public class SeedData implements CommandLineRunner {
    private final ClinicRepository clinics;
    private final RoomRepository rooms;
    private final ProviderRepository providers;
    private final SlotRepository slots;
    private final AppointmentRepository appointments;
    private final AppointmentTypeRuleRepository typeRules;
    private final WaitlistRepository waitlist;
    private final PatientPreferenceRepository preferences;
    private final ActivityRepository activity;

    public SeedData(ClinicRepository clinics, RoomRepository rooms, ProviderRepository providers, SlotRepository slots,
                    AppointmentRepository appointments, AppointmentTypeRuleRepository typeRules, WaitlistRepository waitlist,
                    PatientPreferenceRepository preferences, ActivityRepository activity) {
        this.clinics = clinics; this.rooms = rooms; this.providers = providers; this.slots = slots;
        this.appointments = appointments; this.typeRules = typeRules; this.waitlist = waitlist;
        this.preferences = preferences; this.activity = activity;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (providers.count() > 0) return;
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);

        Clinic downtown = clinics.save(new Clinic("CLN-001", "Downtown"));
        Clinic eastside = clinics.save(new Clinic("CLN-002", "Eastside"));

        Room consult1 = rooms.save(new Room("RM-101", "Consult Room 1", downtown, Set.of("EXAM")));
        Room triage = rooms.save(new Room("RM-102", "Triage Bay", downtown, Set.of("EXAM", "TRIAGE")));
        Room kids = rooms.save(new Room("RM-103", "Kids Room", downtown, Set.of("EXAM", "PEDIATRIC")));
        Room kidsTriage = rooms.save(new Room("RM-104", "Pediatric Triage", downtown, Set.of("EXAM", "PEDIATRIC", "TRIAGE")));
        Room eConsult = rooms.save(new Room("RM-201", "Eastside Consult", eastside, Set.of("EXAM")));
        Room eKids = rooms.save(new Room("RM-202", "Eastside Kids & Triage", eastside, Set.of("EXAM", "PEDIATRIC", "TRIAGE")));

        typeRules.save(new AppointmentTypeRule(AppointmentType.ROUTINE, 30, Set.of()));
        typeRules.save(new AppointmentTypeRule(AppointmentType.FOLLOW_UP, 20, Set.of()));
        typeRules.save(new AppointmentTypeRule(AppointmentType.URGENT, 45, Set.of("TRIAGE")));

        Provider chen = providers.save(new Provider("PRV-001", "Dr. Sarah Chen", Specialty.GENERAL, ProviderStatus.BUSY, downtown, 8));
        Provider okonkwo = providers.save(new Provider("PRV-002", "Dr. James Okonkwo", Specialty.SPECIALIST, ProviderStatus.AVAILABLE, downtown, 8));
        Provider sharma = providers.save(new Provider("PRV-003", "Dr. Priya Sharma", Specialty.PEDIATRIC, ProviderStatus.BUSY, downtown, 8));
        Provider verma = providers.save(new Provider("PRV-004", "Dr. Rahul Verma", Specialty.GENERAL, ProviderStatus.AVAILABLE, downtown, 8));
        Provider iyer = providers.save(new Provider("PRV-005", "Dr. Ananya Iyer", Specialty.SPECIALIST, ProviderStatus.BUSY, downtown, 8));
        Provider lee = providers.save(new Provider("PRV-006", "Dr. Marcus Lee", Specialty.GENERAL, ProviderStatus.AVAILABLE, eastside, 8));
        Provider alvarez = providers.save(new Provider("PRV-007", "Dr. Sofia Alvarez", Specialty.PEDIATRIC, ProviderStatus.AVAILABLE, eastside, 8));

        // Addendum A
        Slot s1 = slot("SLT-001", chen, consult1, now.plusMinutes(60), 30, SlotStatus.BOOKED);
        Slot s2 = slot("SLT-002", chen, consult1, now.plusMinutes(120), 30, SlotStatus.BOOKED);
        Slot s3 = slot("SLT-003", sharma, kidsTriage, now.plusMinutes(90), 45, SlotStatus.BOOKED);
        slot("SLT-004", okonkwo, triage, now.plusMinutes(180), 30, SlotStatus.OPEN);
        slot("SLT-005", verma, consult1, now.plusMinutes(150), 30, SlotStatus.OPEN);
        Slot s6 = slot("SLT-006", iyer, triage, now.plusMinutes(240), 60, SlotStatus.BOOKED);
        // Extra open slots so every feature is demoable
        slot("SLT-007", verma, consult1, now.plusMinutes(75), 30, SlotStatus.OPEN);
        slot("SLT-008", verma, consult1, now.plusMinutes(210), 30, SlotStatus.OPEN);
        slot("SLT-009", chen, consult1, now.plusMinutes(300), 30, SlotStatus.OPEN);
        slot("SLT-010", sharma, kidsTriage, now.plusMinutes(300), 45, SlotStatus.OPEN);
        slot("SLT-011", okonkwo, triage, now.plusMinutes(270), 60, SlotStatus.OPEN);
        slot("SLT-012", iyer, triage, now.plusMinutes(360), 30, SlotStatus.OPEN);
        slot("SLT-013", lee, eConsult, now.plusMinutes(120), 30, SlotStatus.OPEN);
        slot("SLT-014", lee, eConsult, now.plusMinutes(240), 30, SlotStatus.OPEN);
        slot("SLT-015", alvarez, eKids, now.plusMinutes(180), 45, SlotStatus.OPEN);
        slot("SLT-016", sharma, kids, now.plusMinutes(420), 30, SlotStatus.OPEN);

        appointments.save(new Appointment("APT-001", "John Martinez", "john.martinez@example.com", AppointmentType.ROUTINE, chen, s1, AppointmentStatus.CONFIRMED));
        appointments.save(new Appointment("APT-002", "Emily Watson", "+1-555-0142", AppointmentType.FOLLOW_UP, chen, s2, AppointmentStatus.CONFIRMED));
        appointments.save(new Appointment("APT-003", "Aisha Patel", "aisha.patel@example.com", AppointmentType.URGENT, sharma, s3, AppointmentStatus.CONFIRMED));
        appointments.save(new Appointment("APT-004", "Robert Kim", "+1-555-0199", AppointmentType.ROUTINE, iyer, s6, AppointmentStatus.CONFIRMED));

        // History for preference inference: John Martinez usually comes in the morning.
        Slot past = slot("SLT-090", chen, consult1, now.toLocalDate().minusDays(7).atTime(9, 30), 30, SlotStatus.BOOKED);
        Appointment done = new Appointment("APT-090", "John Martinez", "john.martinez@example.com", AppointmentType.ROUTINE, chen, past, AppointmentStatus.CONFIRMED);
        done.complete();
        appointments.save(done);

        preferences.save(new PatientPreference("Emily Watson", PreferredWindow.AFTERNOON, PreferenceSource.EXPLICIT));

        waitlist.save(new WaitlistEntry("WL-001", "Maria Gonzalez", "maria.g@example.com", AppointmentType.ROUTINE,
                Specialty.GENERAL, PreferredWindow.ANY, downtown, now.minusHours(3)));
        waitlist.save(new WaitlistEntry("WL-002", "Leo Park", "+1-555-0177", AppointmentType.FOLLOW_UP,
                Specialty.PEDIATRIC, PreferredWindow.ANY, null, now.minusHours(1)));

        activity.save(new ActivityEvent(ActivityKind.INFO, "SlotMind started — seeded 2 clinics, 7 providers, 17 slots, 5 appointments, 2 waitlist entries"));
    }

    private Slot slot(String id, Provider p, Room r, LocalDateTime start, int minutes, SlotStatus status) {
        return slots.save(new Slot(id, p, r, start, minutes, status));
    }
}
