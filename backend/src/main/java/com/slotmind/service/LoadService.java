package com.slotmind.service;

import com.slotmind.repository.SlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Queue pressure: booked slots per provider for today. */
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
}
