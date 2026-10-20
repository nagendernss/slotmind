package com.slotmind.scheduling;

import com.slotmind.domain.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plain domain fixtures for scheduling tests. Fixed far-future clock so OpenFutureSlotFilter and window logic are deterministic. */
final class Fx {
    /** A Monday, 08:00. */
    static final LocalDateTime NOW = LocalDateTime.of(2099, 1, 5, 8, 0);

    final Clinic downtown = new Clinic("CLN-001", "Downtown");
    final Clinic eastside = new Clinic("CLN-002", "Eastside");
    final Room exam1 = new Room("RM-001", "Exam Room 1", downtown, Set.of("TRIAGE"));
    final Room exam2 = new Room("RM-002", "Exam Room 2", downtown, Set.of());
    final Room bigRoom = new Room("RM-003", "Procedure Room", downtown, Set.of("TRIAGE", "XRAY", "PEDIATRIC"));
    final Room eastRoom = new Room("RM-010", "East Room", eastside, Set.of("TRIAGE"));

    final Provider patel = new Provider("PRV-001", "Dr. Patel", Specialty.GENERAL, ProviderStatus.AVAILABLE, downtown, 8);
    final Provider chen = new Provider("PRV-002", "Dr. Chen", Specialty.GENERAL, ProviderStatus.AVAILABLE, downtown, 8);
    final Provider kim = new Provider("PRV-003", "Dr. Kim", Specialty.SPECIALIST, ProviderStatus.AVAILABLE, downtown, 8);
    final Provider east = new Provider("PRV-004", "Dr. East", Specialty.GENERAL, ProviderStatus.AVAILABLE, eastside, 8);

    /** Patel's booked slot at 10:00 — the appointment's original slot. */
    final Slot original = slot("SLT-001", patel, exam1, 10, 0, SlotStatus.BOOKED);

    Appointment appointment(AppointmentStatus status) {
        return new Appointment("APT-001", "John Martinez", "john@example.com", AppointmentType.ROUTINE, patel, original, status);
    }

    final Appointment appt = appointment(AppointmentStatus.RESCHEDULE_PENDING);

    static Slot slot(String id, Provider p, Room r, int hour, int minute, SlotStatus status) {
        return new Slot(id, p, r, NOW.withHour(hour).withMinute(minute), 30, status);
    }

    static Slot open(String id, Provider p, Room r, int hour, int minute) { return slot(id, p, r, hour, minute, SlotStatus.OPEN); }

    SchedulingContext ctx(TriggerReason trigger, List<Slot> candidates, PreferredWindow w, Map<String, Integer> load,
                          boolean crossClinic) {
        return new SchedulingContext(appt, trigger, candidates, List.of(patel, chen, kim, east), w, load, crossClinic, NOW);
    }

    SchedulingContext ctx(List<Slot> candidates) {
        return ctx(TriggerReason.SLOT_CANCELLED, candidates, PreferredWindow.ANY, Map.of(), false);
    }

    FilterContext fc(int minutes, Set<String> equipment, Map<String, Integer> load, String homeClinicId) {
        return new FilterContext(appt, minutes, equipment, load, homeClinicId, NOW);
    }

    FilterContext fc() { return fc(0, Set.of(), Map.of(), null); }
}
