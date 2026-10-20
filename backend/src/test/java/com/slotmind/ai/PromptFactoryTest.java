package com.slotmind.ai;

import com.slotmind.domain.TriggerReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptFactoryTest {
    private final AiTestFixtures f = new AiTestFixtures();
    private final PromptFactory factory = new PromptFactory();

    @Test
    void offlineAndCancelledPromptsDiffer() {
        String offline = factory.build(f.ctx(TriggerReason.PROVIDER_OFFLINE, List.of(f.chenSoon))).user();
        String cancelled = factory.build(f.ctx(TriggerReason.SLOT_CANCELLED, List.of(f.chenSoon))).user();
        String initial = factory.build(f.ctx(TriggerReason.INITIAL, List.of(f.chenSoon))).user();
        assertThat(offline).isNotEqualTo(cancelled);
        assertThat(offline).contains("unavailable for today").contains("same specialty");
        assertThat(cancelled).contains("was cancelled").contains("SAME doctor");
        assertThat(initial).contains("Staff requested a new time");
    }

    @Test
    void userPromptCarriesContextAndCandidates() {
        String u = factory.build(f.ctx(TriggerReason.SLOT_CANCELLED, List.of(f.chenSoon, f.patelLater))).user();
        assertThat(u).contains("Maria").doesNotContain("Lopez")
                .contains("FOLLOW_UP").contains("AFTERNOON")
                .contains("Dr. Chen (PRV-002)").contains("7/8 booked today (nearly full)")
                .contains("2:30 PM today").contains("Exam Room 1").contains("Downtown Clinic")
                .contains("CANDIDATE_IDS: SLT-005,SLT-007");
        assertThat(factory.build(f.ctx(TriggerReason.INITIAL, List.of())).system()).contains("ONLY from the numbered candidate list").contains("READ IT ALOUD");
    }
}
