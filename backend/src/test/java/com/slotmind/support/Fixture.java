package com.slotmind.support;

import com.slotmind.domain.*;
import com.slotmind.repository.*;
import jakarta.persistence.EntityManager;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.fail;

/**
 * Per-test data builder. Every call creates rows with ids that are unique for the whole JVM, so tests sharing one
 * Spring context (and one H2 database) never see each other's data. Each test gets its own clinic: the engine's
 * ClinicScopeFilter keeps candidate sets inside that clinic as long as it has a legal slot.
 */
@TestComponent
public class Fixture {
    private static final AtomicInteger SEQ = new AtomicInteger();

    public final ClinicRepository clinics;
    public final RoomRepository rooms;
    public final ProviderRepository providers;
    public final SlotRepository slots;
    public final AppointmentRepository appointments;
    public final SuggestionRepository suggestions;
    public final WaitlistRepository waitlist;
    public final TransactionTemplate tx;
    private final EntityManager em;

    public Fixture(ClinicRepository clinics, RoomRepository rooms, ProviderRepository providers, SlotRepository slots,
                   AppointmentRepository appointments, SuggestionRepository suggestions, WaitlistRepository waitlist,
                   TransactionTemplate tx, EntityManager em) {
        this.clinics = clinics; this.rooms = rooms; this.providers = providers; this.slots = slots;
        this.appointments = appointments; this.suggestions = suggestions; this.waitlist = waitlist; this.tx = tx;
        this.em = em;
    }

    /**
     * Cancels every OPEN slot of {@code specialty} outside {@code clinicId}, so a test that exhausts its own clinic
     * cannot be widened onto other tests' (or seed) slots. Only for tests that need "no other legal slot anywhere".
     */
    public int closeForeignOpenSlots(Specialty specialty, String clinicId) {
        Integer n = tx.execute(s -> em.createQuery("update Slot s set s.status = :cancelled where s.status = :open and s.provider.id in "
                        + "(select p.id from Provider p left join p.clinic c where p.specialty = :sp and (c is null or c.id <> :clinic))")
                .setParameter("cancelled", SlotStatus.CANCELLED).setParameter("open", SlotStatus.OPEN)
                .setParameter("sp", specialty).setParameter("clinic", clinicId).executeUpdate());
        return n == null ? 0 : n;
    }

    public static String uid() { return String.format("X%05d", SEQ.incrementAndGet()); }

    public static LocalDateTime now() { return LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES); }

    public Clinic clinic() {
        String u = uid();
        return clinics.save(new Clinic("CLN-" + u, "Clinic " + u));
    }

    public Room room(Clinic clinic, String... equipment) {
        String u = uid();
        return rooms.save(new Room("RM-" + u, "Room " + u, clinic, Set.of(equipment)));
    }

    public Provider provider(Clinic clinic, Specialty specialty, ProviderStatus status, int maxDaily) {
        String u = uid();
        return providers.save(new Provider("PRV-" + u, "Dr. " + u, specialty, status, clinic, maxDaily));
    }

    public Provider provider(Clinic clinic, Specialty specialty) {
        return provider(clinic, specialty, ProviderStatus.AVAILABLE, 20);
    }

    public Slot slot(Provider p, Room r, int minutesFromNow, int duration, SlotStatus status) {
        return slotAt(p, r, now().plusMinutes(minutesFromNow), duration, status);
    }

    public Slot slotAt(Provider p, Room r, LocalDateTime start, int duration, SlotStatus status) {
        return slots.save(new Slot("SLT-" + uid(), p, r, start, duration, status));
    }

    /** Saves an appointment on {@code slot} (the slot must already be BOOKED). */
    public Appointment appointment(Provider p, Slot slot, AppointmentType type, String contact, AppointmentStatus status) {
        String u = uid();
        return appointments.save(new Appointment("APT-" + u, "Patient " + u, contact, type, p, slot, status));
    }

    public Appointment appointment(Provider p, Slot slot) {
        return appointment(p, slot, AppointmentType.ROUTINE, null, AppointmentStatus.CONFIRMED);
    }

    /** Stores a PENDING suggestion directly (bypassing the engine) for tests that need a precise target slot. */
    public RescheduleSuggestion pendingSuggestion(String appointmentId, String slotId, TriggerReason trigger) {
        return tx.execute(s -> {
            Appointment a = appointments.findDetailed(appointmentId).orElseThrow();
            a.markReschedulePending();
            Slot target = slotId == null ? null : this.slots.findDetailed(slotId).orElseThrow();
            return suggestions.saveAndFlush(new RescheduleSuggestion(a, target, null, 0.8,
                    "We can offer you a new time, would that work?", trigger, "RULE_BASED", false, false));
        });
    }

    public SlotStatus slotStatus(String slotId) { return slots.findById(slotId).orElseThrow().getStatus(); }

    public AppointmentStatus apptStatus(String id) { return appointments.findById(id).orElseThrow().getStatus(); }

    public String apptSlotId(String id) { return appointments.findDetailed(id).orElseThrow().getSlot().getId(); }

    public SuggestionStatus suggestionStatus(String id) { return suggestions.findById(id).orElseThrow().getStatus(); }

    /**
     * A small clinic: room with EXAM+TRIAGE, two GENERAL providers (A owns the booked appointment, B is the
     * alternate), one CONFIRMED ROUTINE appointment on A at now+60 and open slots A+180, B+90, B+240.
     */
    public World world() {
        Clinic c = clinic();
        Room room = room(c, "EXAM", "TRIAGE");
        Provider a = provider(c, Specialty.GENERAL, ProviderStatus.BUSY, 20);
        Provider b = provider(c, Specialty.GENERAL, ProviderStatus.AVAILABLE, 20);
        Slot booked = slot(a, room, 60, 30, SlotStatus.BOOKED);
        Appointment appt = appointment(a, booked, AppointmentType.ROUTINE, "patient@example.com", AppointmentStatus.CONFIRMED);
        Slot a180 = slot(a, room, 180, 30, SlotStatus.OPEN);
        Slot b90 = slot(b, room, 90, 30, SlotStatus.OPEN);
        Slot b240 = slot(b, room, 240, 30, SlotStatus.OPEN);
        return new World(c, room, a, b, booked, appt, a180, b90, b240);
    }

    public record World(Clinic clinic, Room room, Provider a, Provider b, Slot booked, Appointment appt,
                        Slot a180, Slot b90, Slot b240) {}

    /** Bounded polling for asynchronous after-commit work (no fixed long sleeps). */
    public static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (!condition.getAsBoolean()) fail("Timed out waiting for: " + what);
    }
}
