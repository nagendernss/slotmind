package com.slotmind.scheduling;

import com.slotmind.domain.Slot;
import com.slotmind.domain.SlotStatus;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** The hard-constraint filters (decision.md §5, §10, §11). Each is an independent Spring bean. */
public final class Filters {
    private Filters() {}

    @Component
    @Order(1)
    public static class OpenFutureSlotFilter implements CandidateFilter {
        public String name() { return "OpenFutureSlot"; }
        public boolean test(Slot s, FilterContext c) {
            return s.getStatus() == SlotStatus.OPEN && s.getStartTime().isAfter(c.now());
        }
    }

    @Component
    @Order(2)
    public static class ProviderOnlineFilter implements CandidateFilter {
        public String name() { return "ProviderOnline"; }
        public boolean test(Slot s, FilterContext c) { return s.getProvider().isOnline(); }
    }

    /** Alternate provider must share the original provider's specialty (a pediatric visit stays pediatric). */
    @Component
    @Order(3)
    public static class SpecialtyMatchFilter implements CandidateFilter {
        public String name() { return "SpecialtyMatch"; }
        public boolean test(Slot s, FilterContext c) {
            return s.getProvider().getSpecialty() == c.appointment().getProvider().getSpecialty();
        }
    }

    @Component
    @Order(4)
    public static class DurationFitFilter implements CandidateFilter {
        public String name() { return "DurationFit"; }
        public boolean test(Slot s, FilterContext c) { return s.getDurationMinutes() >= c.requiredMinutes(); }
    }

    @Component
    @Order(5)
    public static class RoomEquipmentFilter implements CandidateFilter {
        public String name() { return "RoomEquipment"; }
        public boolean test(Slot s, FilterContext c) {
            if (c.requiredEquipment().isEmpty()) return true;
            return s.getRoom() != null && s.getRoom().getEquipment().containsAll(c.requiredEquipment());
        }
    }

    @Component
    @Order(6)
    public static class ProviderDailyCapFilter implements CandidateFilter {
        public String name() { return "ProviderDailyCap"; }
        public boolean test(Slot s, FilterContext c) {
            var p = s.getProvider();
            if (p.getId().equals(c.appointment().getProvider().getId())) return true; // moving within same provider: load unchanged
            return c.providerLoad().getOrDefault(p.getId(), 0) < p.getMaxDailyAppointments();
        }
    }

    @Component
    @Order(7)
    public static class ClinicScopeFilter implements CandidateFilter {
        public String name() { return "ClinicScope"; }
        public boolean test(Slot s, FilterContext c) {
            if (c.homeClinicId() == null) return true; // widened
            var clinic = s.getProvider().getClinic();
            return clinic != null && clinic.getId().equals(c.homeClinicId());
        }
    }
}
