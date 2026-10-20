package com.slotmind.scheduling;

import com.slotmind.domain.Appointment;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Inputs for hard-constraint filters.
 * @param homeClinicId clinic to restrict to, or null when the engine has widened to all clinics
 */
public record FilterContext(Appointment appointment, int requiredMinutes, Set<String> requiredEquipment,
                            Map<String, Integer> providerLoad, String homeClinicId, LocalDateTime now) {}
