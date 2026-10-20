package com.slotmind.ai;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.AppointmentStatus;
import com.slotmind.domain.AppointmentType;
import com.slotmind.domain.Clinic;
import com.slotmind.domain.PreferredWindow;
import com.slotmind.domain.Provider;
import com.slotmind.domain.ProviderStatus;
import com.slotmind.domain.Room;
import com.slotmind.domain.Slot;
import com.slotmind.domain.SlotStatus;
import com.slotmind.domain.Specialty;
import com.slotmind.domain.TriggerReason;
import com.slotmind.scheduling.SchedulingContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plain domain objects (no Mockito: the machine runs a JRE, inline mock maker can't attach). */
final class AiTestFixtures {
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 9, 0);

    final Clinic clinic = new Clinic("CLN-001", "Downtown Clinic");
    final Room room = new Room("RM-001", "Exam Room 1", clinic, Set.of("TRIAGE"));
    final Provider patel = new Provider("PRV-001", "Dr. Patel", Specialty.GENERAL, ProviderStatus.AVAILABLE, clinic, 8);
    final Provider chen = new Provider("PRV-002", "Dr. Chen", Specialty.GENERAL, ProviderStatus.AVAILABLE, clinic, 8);
    final Slot original = new Slot("SLT-001", patel, room, NOW.plusMinutes(60), 30, SlotStatus.BOOKED);
    final Slot patelLater = new Slot("SLT-005", patel, room, NOW.plusHours(5).plusMinutes(30), 30, SlotStatus.OPEN);
    final Slot chenSoon = new Slot("SLT-007", chen, room, NOW.plusHours(2), 30, SlotStatus.OPEN);
    final Appointment appointment = new Appointment("APT-001", "Maria Lopez", null, AppointmentType.FOLLOW_UP,
            patel, original, AppointmentStatus.RESCHEDULE_PENDING);

    SchedulingContext ctx(TriggerReason trigger, List<Slot> candidates) {
        return new SchedulingContext(appointment, trigger, candidates, List.of(patel, chen), PreferredWindow.AFTERNOON,
                Map.of("PRV-001", 3, "PRV-002", 7), false, NOW);
    }
}
