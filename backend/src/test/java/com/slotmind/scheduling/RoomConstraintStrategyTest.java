package com.slotmind.scheduling;

import com.slotmind.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** decision.md §10: same provider → same room → best-fit room (fewest surplus equipment) → earliest. Original: Patel, Exam Room 1. */
class RoomConstraintStrategyTest {
    private final Fx f = new Fx();
    private final RoomConstraintStrategy strategy = new RoomConstraintStrategy();

    private List<String> order(Slot... slots) {
        return strategy.recommend(f.ctx(List.of(slots))).stream().map(SlotRecommendation::slotId).toList();
    }

    @Test
    void key() {
        assertThat(strategy.key()).isEqualTo("ROOM_CONSTRAINT");
    }

    @Test
    void emptyCandidates() {
        assertThat(strategy.recommend(f.ctx(List.of()))).isEmpty();
    }

    @Test
    void sameProviderFirstEvenInWorseRoomAndLater() {
        Slot chenSameRoomEarly = Fx.open("SLT-A", f.chen, f.exam1, 9, 0);
        Slot patelBigRoomLate = Fx.open("SLT-B", f.patel, f.bigRoom, 17, 0);
        assertThat(order(chenSameRoomEarly, patelBigRoomLate)).containsExactly("SLT-B", "SLT-A");
    }

    @Test
    void sameRoomBeatsBetterFitRoom() {
        Slot exam2Early = Fx.open("SLT-A", f.patel, f.exam2, 9, 0);     // 0 equipment
        Slot exam1Late = Fx.open("SLT-B", f.patel, f.exam1, 16, 0);     // original room
        assertThat(order(exam2Early, exam1Late)).containsExactly("SLT-B", "SLT-A");
    }

    @Test
    void fewestEquipmentRoomBeatsSpecialisedRoom() {
        Slot big = Fx.open("SLT-A", f.chen, f.bigRoom, 9, 0);
        Slot bare = Fx.open("SLT-B", f.chen, f.exam2, 15, 0);
        assertThat(order(big, bare)).containsExactly("SLT-B", "SLT-A");
    }

    @Test
    void slotWithoutRoomRanksAfterAnyRoom() {
        Slot noRoom = Fx.open("SLT-A", f.chen, null, 9, 0);
        Slot big = Fx.open("SLT-B", f.chen, f.bigRoom, 15, 0);
        assertThat(order(noRoom, big)).containsExactly("SLT-B", "SLT-A");
    }

    @Test
    void earliestThenIdBreakRemainingTies() {
        Room twin = new Room("RM-004", "Exam Room 4", f.downtown, Set.of());
        Slot late = Fx.open("SLT-A", f.chen, f.exam2, 15, 0);
        Slot early = Fx.open("SLT-B", f.chen, twin, 9, 0);
        Slot earlyB = Fx.open("SLT-0", f.chen, f.exam2, 9, 0);
        assertThat(order(late, early, earlyB)).containsExactly("SLT-0", "SLT-B", "SLT-A");
    }

    @Test
    void fullRankingAcrossAllCriteria() {
        Slot a = Fx.open("SLT-A", f.chen, f.exam2, 9, 0);    // other provider, bare room
        Slot b = Fx.open("SLT-B", f.patel, f.exam2, 9, 30);  // same provider, other room
        Slot c = Fx.open("SLT-C", f.patel, f.exam1, 17, 0);  // same provider, same room
        Slot d = Fx.open("SLT-D", f.chen, f.exam1, 8, 30);   // other provider, same room
        Slot e = Fx.open("SLT-E", f.patel, f.bigRoom, 9, 0); // same provider, specialised room
        assertThat(order(a, b, c, d, e)).containsExactly("SLT-C", "SLT-B", "SLT-E", "SLT-D", "SLT-A");
    }

    @Test
    void sameProviderSameRoomRecommendation() {
        SlotRecommendation r = strategy.recommend(f.ctx(List.of(Fx.open("SLT-C", f.patel, f.exam1, 11, 0)))).get(0);
        assertThat(r.alternateProviderId()).isNull();
        assertThat(r.confidence()).isCloseTo(0.90, within(1e-9));
        assertThat(r.reasoning()).contains("We can see you at 11:00 AM today with Dr. Patel in the Exam Room 1, the same room as before.");
    }

    @Test
    void otherProviderOtherRoomRecommendation() {
        SchedulingContext ctx = f.ctx(TriggerReason.PROVIDER_OFFLINE, List.of(Fx.open("SLT-A", f.chen, f.exam2, 11, 0)),
                PreferredWindow.ANY, java.util.Map.of(), false);
        SlotRecommendation r = strategy.recommend(ctx).get(0);
        assertThat(r.alternateProviderId()).isEqualTo("PRV-002");
        assertThat(r.confidence()).isCloseTo(0.72, within(1e-9));
        assertThat(r.reasoning()).startsWith("Dr. Patel is unavailable today")
                .endsWith("with Dr. Chen in the Exam Room 2, which has everything your visit needs.");
    }

    @Test
    void confidenceMatrix() {
        assertThat(strategy.recommend(f.ctx(List.of(Fx.open("S", f.patel, f.exam2, 11, 0)))).get(0).confidence()).isCloseTo(0.85, within(1e-9));
        assertThat(strategy.recommend(f.ctx(List.of(Fx.open("S", f.chen, f.exam1, 11, 0)))).get(0).confidence()).isCloseTo(0.77, within(1e-9));
    }

    @Test
    void noRoomAnywhere() {
        Slot originalNoRoom = new Slot("SLT-100", f.patel, null, Fx.NOW.withHour(10), 30, SlotStatus.BOOKED);
        Appointment a = new Appointment("APT-100", "Ann", null, AppointmentType.ROUTINE, f.patel, originalNoRoom,
                AppointmentStatus.RESCHEDULE_PENDING);
        SchedulingContext ctx = new SchedulingContext(a, TriggerReason.INITIAL, List.of(Fx.open("SLT-Z", f.patel, null, 11, 0)),
                List.of(), PreferredWindow.ANY, java.util.Map.of(), false, Fx.NOW);
        SlotRecommendation r = strategy.recommend(ctx).get(0);
        assertThat(r.confidence()).as("null rooms never count as 'same room'").isCloseTo(0.85, within(1e-9));
        assertThat(r.reasoning()).contains("in an available room, which has everything");
    }
}
