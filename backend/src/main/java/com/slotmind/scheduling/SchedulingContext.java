package com.slotmind.scheduling;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.PreferredWindow;
import com.slotmind.domain.Provider;
import com.slotmind.domain.Slot;
import com.slotmind.domain.TriggerReason;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything a strategy needs. Built by ScheduleEngine inside a transaction, so lazy associations
 * (slot.getProvider(), slot.getRoom(), provider.getClinic()) are safe to navigate inside recommend().
 */
public record SchedulingContext(
        Appointment appointment, TriggerReason trigger,
        List<Slot> candidates,                 // already filtered: only legal slots
        List<Provider> providers,              // roster
        PreferredWindow preferredWindow,
        Map<String, Integer> providerLoad,     // providerId -> booked appointments today
        boolean crossClinic,                   // true if candidates were widened to other clinics
        LocalDateTime now) {}
