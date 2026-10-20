package com.slotmind.scheduling;

import com.slotmind.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Original appointment: Dr. Patel (GENERAL, Downtown) at 10:00 on Fx.NOW's day. */
class RuleBasedStrategyTest {
    private final Fx f = new Fx();
    private final RuleBasedStrategy strategy = new RuleBasedStrategy();

    private List<String> order(List<Slot> candidates, PreferredWindow w, Map<String, Integer> load) {
        return strategy.recommend(f.ctx(TriggerReason.SLOT_CANCELLED, candidates, w, load, false))
                .stream().map(SlotRecommendation::slotId).toList();
    }

    @Test
    void keyIsRuleBased() {
        assertThat(strategy.key()).isEqualTo("RULE_BASED").isEqualTo(StrategyRegistry.RULE_BASED);
    }

    @Test
    void emptyCandidatesGiveEmptyList() {
        assertThat(strategy.recommend(f.ctx(List.of()))).isEmpty();
    }

    @Test
    void sameProviderBeatsCloserOtherProvider() {
        Slot chenClose = Fx.open("SLT-A", f.chen, null, 10, 30);
        Slot patelFar = Fx.open("SLT-B", f.patel, null, 16, 0);
        assertThat(order(List.of(chenClose, patelFar), PreferredWindow.ANY, Map.of())).containsExactly("SLT-B", "SLT-A");
    }

    @Test
    void sameSpecialtyBeatsOtherSpecialty() {
        Slot kimClose = Fx.open("SLT-A", f.kim, null, 10, 30);
        Slot chenFar = Fx.open("SLT-B", f.chen, null, 17, 0);
        assertThat(order(List.of(kimClose, chenFar), PreferredWindow.ANY, Map.of())).containsExactly("SLT-B", "SLT-A");
    }

    @Test
    void nearestToOriginalTimeWithinTierBothDirections() {
        Slot early = Fx.open("SLT-A", f.patel, null, 8, 30);   // 90 min before
        Slot near = Fx.open("SLT-B", f.patel, null, 10, 40);   // 40 min after
        Slot later = Fx.open("SLT-C", f.patel, null, 13, 0);   // 3 h after
        assertThat(order(List.of(later, early, near), PreferredWindow.ANY, Map.of())).containsExactly("SLT-B", "SLT-A", "SLT-C");
    }

    @Test
    void preferredWindowBreaksTieWithinSameHourBucket() {
        // 7:45 (135 min before 10:00) and 12:30 (150 min after) share the 2-hour bucket
        Slot morning = Fx.open("SLT-A", f.chen, null, 7, 45);
        Slot afternoon = Fx.open("SLT-B", f.chen, null, 12, 30);
        assertThat(order(List.of(morning, afternoon), PreferredWindow.AFTERNOON, Map.of())).containsExactly("SLT-B", "SLT-A");
        assertThat(order(List.of(morning, afternoon), PreferredWindow.MORNING, Map.of())).containsExactly("SLT-A", "SLT-B");
        assertThat(order(List.of(afternoon, morning), PreferredWindow.ANY, Map.of())).as("exact distance").containsExactly("SLT-A", "SLT-B");
    }

    @Test
    void preferredWindowDoesNotBeatACloserHourBucket() {
        Slot morningNear = Fx.open("SLT-A", f.chen, null, 10, 30);  // bucket 0
        Slot afternoonFar = Fx.open("SLT-B", f.chen, null, 14, 0); // bucket 4
        assertThat(order(List.of(afternoonFar, morningNear), PreferredWindow.AFTERNOON, Map.of())).containsExactly("SLT-A", "SLT-B");
    }

    @Test
    void lowerLoadBreaksTieAfterWindow() {
        Slot chen = Fx.open("SLT-A", f.chen, null, 11, 0);
        Provider lee = new Provider("PRV-005", "Dr. Lee", Specialty.GENERAL, ProviderStatus.AVAILABLE, f.downtown, 8);
        Slot leeSlot = Fx.open("SLT-B", lee, null, 11, 30);
        // without load, the closer one (11:00) wins
        assertThat(order(List.of(leeSlot, chen), PreferredWindow.ANY, Map.of())).containsExactly("SLT-A", "SLT-B");
        // Chen is busier → Lee wins although 30 min further
        assertThat(order(List.of(chen, leeSlot), PreferredWindow.ANY, Map.of("PRV-002", 6, "PRV-005", 1))).containsExactly("SLT-B", "SLT-A");
    }

    @Test
    void slotIdIsFinalTieBreak() {
        Slot b = Fx.open("SLT-B", f.chen, null, 11, 0);
        Slot a = Fx.open("SLT-A", f.chen, null, 11, 0);
        assertThat(order(List.of(b, a), PreferredWindow.ANY, Map.of())).containsExactly("SLT-A", "SLT-B");
    }

    @Test
    void sameProviderRecommendationHasNoAlternateAndHighConfidence() {
        SlotRecommendation r = strategy.recommend(f.ctx(List.of(Fx.open("SLT-B", f.patel, null, 11, 0)))).get(0);
        assertThat(r.alternateProviderId()).isNull();
        assertThat(r.confidence()).isCloseTo(0.9, within(1e-9));
        assertThat(r.reasoning()).contains("The closest opening with Dr. Patel is at 11:00 AM today, so you keep the same doctor.")
                .startsWith("Your original appointment time had to be cancelled");
    }

    @Test
    void sameSpecialtyRecommendationNamesAlternate() {
        SchedulingContext ctx = f.ctx(TriggerReason.PROVIDER_OFFLINE, List.of(Fx.open("SLT-B", f.chen, null, 11, 0)),
                PreferredWindow.ANY, Map.of(), false);
        SlotRecommendation r = strategy.recommend(ctx).get(0);
        assertThat(r.alternateProviderId()).isEqualTo("PRV-002");
        assertThat(r.confidence()).isCloseTo(0.78, within(1e-9));
        assertThat(r.reasoning()).startsWith("Dr. Patel is unavailable today")
                .contains("11:00 AM today with Dr. Chen, another general practitioner.")
                .doesNotContain("PRV-").doesNotContain("SLT-");
    }

    @Test
    void otherSpecialtyTierIsLowestConfidence() {
        SlotRecommendation r = strategy.recommend(f.ctx(List.of(Fx.open("SLT-B", f.kim, null, 11, 0)))).get(0);
        assertThat(r.alternateProviderId()).isEqualTo("PRV-003");
        assertThat(r.confidence()).isCloseTo(0.55, within(1e-9));
        assertThat(r.reasoning()).contains("with Dr. Kim, who can see you.");
    }

    @Test
    void windowMissAndCrossClinicLowerConfidenceAndExplain() {
        Slot east = Fx.open("SLT-E", f.east, f.eastRoom, 15, 0);
        SchedulingContext ctx = f.ctx(TriggerReason.SLOT_CANCELLED, List.of(east), PreferredWindow.MORNING, Map.of(), true);
        SlotRecommendation r = strategy.recommend(ctx).get(0);
        assertThat(r.confidence()).isCloseTo(0.78 - 0.08 - 0.05, within(1e-9));
        assertThat(r.reasoning()).contains("at our Eastside clinic because your usual clinic has no suitable openings")
                .endsWith("It is outside your usual preference for mornings.");
    }

    @Test
    void windowMatchIsMentioned() {
        SchedulingContext ctx = f.ctx(TriggerReason.INITIAL, List.of(Fx.open("SLT-B", f.patel, null, 14, 0)),
                PreferredWindow.AFTERNOON, Map.of(), false);
        SlotRecommendation r = strategy.recommend(ctx).get(0);
        assertThat(r.confidence()).isCloseTo(0.9, within(1e-9));
        assertThat(r.reasoning()).startsWith("You asked for a new appointment time")
                .endsWith("It also matches your preference for afternoons.");
    }

    @Test
    void noPreferenceSentenceForAnyOrNull() {
        Slot s = Fx.open("SLT-B", f.patel, null, 14, 0);
        assertThat(strategy.recommend(f.ctx(TriggerReason.INITIAL, List.of(s), PreferredWindow.ANY, Map.of(), false)).get(0).reasoning())
                .doesNotContain("preference");
        assertThat(strategy.recommend(f.ctx(TriggerReason.INITIAL, List.of(s), null, Map.of(), false)).get(0).reasoning())
                .doesNotContain("preference");
    }

    @Test
    void crossClinicWithoutClinicHasNoClinicSentenceAndConfidenceFloor() {
        Provider homeless = new Provider("PRV-097", "Dr. Free", Specialty.PEDIATRIC, ProviderStatus.AVAILABLE, null, 8);
        Slot s = Fx.open("SLT-X", homeless, null, 20, 0);
        SchedulingContext ctx = f.ctx(TriggerReason.SLOT_CANCELLED, List.of(s), PreferredWindow.MORNING, Map.of(), true);
        SlotRecommendation r = strategy.recommend(ctx).get(0);
        assertThat(r.reasoning()).doesNotContain("clinic because");
        assertThat(r.confidence()).isCloseTo(0.55 - 0.08 - 0.05, within(1e-9)).isGreaterThanOrEqualTo(0.1);
    }

    @Test
    void returnsOneRecommendationPerCandidate() {
        List<Slot> cands = List.of(Fx.open("SLT-A", f.patel, null, 11, 0), Fx.open("SLT-B", f.chen, null, 12, 0),
                Fx.open("SLT-C", f.kim, null, 13, 0));
        assertThat(strategy.recommend(f.ctx(cands))).hasSize(3);
    }

    @Test
    void tierAndDistanceHelpers() {
        assertThat(RuleBasedStrategy.tier(f.appt, Fx.open("S", f.patel, null, 9, 0))).isZero();
        assertThat(RuleBasedStrategy.tier(f.appt, Fx.open("S", f.chen, null, 9, 0))).isEqualTo(1);
        assertThat(RuleBasedStrategy.tier(f.appt, Fx.open("S", f.kim, null, 9, 0))).isEqualTo(2);
        assertThat(RuleBasedStrategy.distance(f.appt, Fx.open("S", f.chen, null, 9, 15))).isEqualTo(45);
        assertThat(RuleBasedStrategy.distance(f.appt, Fx.open("S", f.chen, null, 11, 15))).isEqualTo(75);
    }
}
