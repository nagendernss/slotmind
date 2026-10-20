package com.slotmind.scheduling;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.Room;
import com.slotmind.domain.Slot;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Sprint 2 third strategy — registered purely by being a bean (decision.md §10).
 * Ranking: same provider -> same room -> best-fit room (fewest surplus equipment, keeps specialised rooms free) -> earliest.
 */
@Component
public class RoomConstraintStrategy implements SchedulingStrategy {

    @Override
    public String key() { return "ROOM_CONSTRAINT"; }

    @Override
    public List<SlotRecommendation> recommend(SchedulingContext ctx) {
        Appointment a = ctx.appointment();
        Room originalRoom = a.getSlot().getRoom();
        return ctx.candidates().stream()
                .sorted(Comparator.<Slot>comparingInt(s -> s.getProvider().getId().equals(a.getProvider().getId()) ? 0 : 1)
                        .thenComparingInt(s -> sameRoom(originalRoom, s) ? 0 : 1)
                        .thenComparingInt(RoomConstraintStrategy::surplus)
                        .thenComparing(Slot::getStartTime)
                        .thenComparing(Slot::getId))
                .map(s -> {
                    boolean sameProvider = s.getProvider().getId().equals(a.getProvider().getId());
                    boolean sameRoom = sameRoom(originalRoom, s);
                    double conf = (sameProvider ? 0.85 : 0.72) + (sameRoom ? 0.05 : 0);
                    String roomName = s.getRoom() == null ? "an available room" : "the " + s.getRoom().getName();
                    String reasoning = Texts.triggerOpening(ctx.trigger(), a.getProvider().getName()) + " We can see you at "
                            + Texts.when(s.getStartTime(), ctx.now()) + " with " + s.getProvider().getName() + " in " + roomName
                            + (sameRoom ? ", the same room as before." : ", which has everything your visit needs.");
                    return new SlotRecommendation(s.getId(), sameProvider ? null : s.getProvider().getId(), conf, reasoning);
                })
                .toList();
    }

    private static boolean sameRoom(Room original, Slot s) {
        return original != null && s.getRoom() != null && Objects.equals(original.getId(), s.getRoom().getId());
    }

    private static int surplus(Slot s) {
        return s.getRoom() == null ? 99 : s.getRoom().getEquipment().size();
    }
}
