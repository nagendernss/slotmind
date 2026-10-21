package com.slotmind.service;

import com.slotmind.repository.SlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** Queue pressure: booked slots per provider for today, and per day for the daily cap. */
@Service
@Transactional(readOnly = true)
public class LoadService {
    private final SlotRepository slots;

    public LoadService(SlotRepository slots) { this.slots = slots; }

    public Map<String, Integer> bookedToday() {
        LocalDate today = LocalDate.now();
        Map<String, Integer> load = new HashMap<>();
        for (Object[] row : slots.countBookedBetween(today.atStartOfDay(), today.plusDays(1).atStartOfDay())) {
            load.put((String) row[0], ((Number) row[1]).intValue());
        }
        return load;
    }

    /** Booked appointments per provider for each day from {@code from} on. */
    public Map<LocalDate, Map<String, Integer>> bookedByDay(LocalDate from) {
        Map<LocalDate, Map<String, Integer>> byDay = new HashMap<>();
        for (Object[] row : slots.findBookedFrom(from.atStartOfDay())) {
            LocalDate day = ((LocalDateTime) row[1]).toLocalDate();
            byDay.computeIfAbsent(day, d -> new HashMap<>()).merge((String) row[0], 1, Integer::sum);
        }
        return byDay;
    }
}
