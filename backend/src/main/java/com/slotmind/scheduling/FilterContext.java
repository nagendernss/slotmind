package com.slotmind.scheduling;

import com.slotmind.domain.Appointment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Inputs for hard-constraint filters.
 * @param homeClinicId clinic to restrict to, or null when the engine has widened to all clinics
 * @param providerLoad booked appointments per provider today (queue pressure shown to strategies)
 * @param loadByDay    booked appointments per provider for each day, used by the daily-cap filter
 */
public record FilterContext(Appointment appointment, int requiredMinutes, Set<String> requiredEquipment,
                            Map<String, Integer> providerLoad, String homeClinicId, LocalDateTime now,
                            Map<LocalDate, Map<String, Integer>> loadByDay) {

    public FilterContext(Appointment appointment, int requiredMinutes, Set<String> requiredEquipment,
                         Map<String, Integer> providerLoad, String homeClinicId, LocalDateTime now) {
        this(appointment, requiredMinutes, requiredEquipment, providerLoad, homeClinicId, now, Map.of());
    }

    /** Booked appointments for the provider on that day; today's count is the fallback when no per-day data is given. */
    public int loadOn(String providerId, LocalDate day) {
        if (loadByDay != null && !loadByDay.isEmpty()) {
            return loadByDay.getOrDefault(day, Map.of()).getOrDefault(providerId, 0);
        }
        boolean today = now == null || day.equals(now.toLocalDate());
        return today ? providerLoad.getOrDefault(providerId, 0) : 0;
    }
}
