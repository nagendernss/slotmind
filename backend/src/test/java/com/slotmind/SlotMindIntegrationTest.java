package com.slotmind;

import com.slotmind.domain.*;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.SlotRepository;
import com.slotmind.repository.SuggestionRepository;
import com.slotmind.scheduling.SchedulingContext;
import com.slotmind.scheduling.SchedulingStrategy;
import com.slotmind.scheduling.SlotRecommendation;
import com.slotmind.service.ProviderService;
import com.slotmind.service.RescheduleOrchestrator;
import com.slotmind.service.SlotUnavailableException;
import com.slotmind.service.SuggestionService;
import com.slotmind.view.Views.SuggestionView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** decision.md §14 — the three paths that carry the points. No mocks (Mockito cannot attach on this JRE). */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SlotMindIntegrationTest {

    @TestConfiguration
    static class BadStrategyConfig {
        /** A strategy that returns an unknown slot and leaks ids — the engine must reject it and fall back. */
        @Bean
        SchedulingStrategy badAi() {
            return new SchedulingStrategy() {
                public String key() { return "BAD_AI"; }
                public List<SlotRecommendation> recommend(SchedulingContext ctx) {
                    return List.of(new SlotRecommendation("SLT-999", "PRV-999", 1.7, "Go to SLT-999"));
                }
            };
        }
    }

    @Autowired ProviderService providerService;
    @Autowired SuggestionService suggestionService;
    @Autowired RescheduleOrchestrator orchestrator;
    @Autowired SuggestionRepository suggestions;
    @Autowired AppointmentRepository appointments;
    @Autowired SlotRepository slots;
    @Autowired TransactionTemplate tx;

    private List<RescheduleSuggestion> pending() { return suggestions.findByStatus(SuggestionStatus.PENDING); }

    private void await(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.get() && System.currentTimeMillis() < deadline) Thread.sleep(100);
    }

    @Test
    void providerOfflineCreatesExactlyOneSuggestionPerAffectedAppointmentAndIsIdempotent() throws Exception {
        providerService.changeStatus("PRV-001", ProviderStatus.OFFLINE);
        await(() -> pending().size() >= 2);
        Thread.sleep(500);
        List<RescheduleSuggestion> first = pending();
        assertThat(first).hasSize(2);
        List<String> appts = tx.execute(s -> suggestions.search(SuggestionStatus.PENDING).stream().map(SuggestionView::of)
                .map(SuggestionView::appointmentId).sorted().toList());
        assertThat(appts).containsExactly("APT-001", "APT-002");
        assertThat(appointments.findById("APT-001").orElseThrow().getStatus()).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);

        // Toggle back and offline again: no duplicate suggestions.
        providerService.changeStatus("PRV-001", ProviderStatus.AVAILABLE);
        providerService.changeStatus("PRV-001", ProviderStatus.OFFLINE);
        Thread.sleep(1500);
        assertThat(pending()).hasSize(2);
        assertThat(suggestions.count()).isEqualTo(2);
        // Direct re-processing is skipped as well.
        assertThat(orchestrator.process("APT-001", TriggerReason.PROVIDER_OFFLINE)).isEmpty();
    }

    @Test
    void acceptIsAtomicAndSecondAcceptOfSameSlotGets409() {
        SuggestionView a = orchestrator.requestNew("APT-001", "RULE_BASED", "STAFF");
        assertThat(a.recommendedSlot()).isNotNull();
        String targetSlot = a.recommendedSlot().id();

        // A second pending suggestion (another appointment) that points at the very same slot.
        String secondId = tx.execute(s -> {
            Appointment other = appointments.findById("APT-002").orElseThrow();
            other.markReschedulePending();
            RescheduleSuggestion g = new RescheduleSuggestion(other, slots.findById(targetSlot).orElseThrow(), null, 0.8,
                    "Same time, different patient.", TriggerReason.INITIAL, "RULE_BASED", false, false);
            return suggestions.save(g).getId();
        });

        SuggestionView accepted = suggestionService.resolve(a.id(), SuggestionStatus.ACCEPTED, "STAFF");
        assertThat(accepted.status()).isEqualTo(SuggestionStatus.ACCEPTED);
        assertThat(slots.findById(targetSlot).orElseThrow().getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(slots.findById("SLT-001").orElseThrow().getStatus()).isEqualTo(SlotStatus.OPEN);
        assertThat((String) tx.execute(s -> appointments.findById("APT-001").orElseThrow().getSlot().getId())).isEqualTo(targetSlot);

        assertThatThrownBy(() -> suggestionService.resolve(secondId, SuggestionStatus.ACCEPTED, "STAFF"))
                .isInstanceOf(SlotUnavailableException.class);
        // Rolled back: APT-002 untouched, its slot still booked, suggestion still pending.
        assertThat((String) tx.execute(s -> appointments.findById("APT-002").orElseThrow().getSlot().getId())).isEqualTo("SLT-002");
        assertThat(slots.findById("SLT-002").orElseThrow().getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(suggestions.findById(secondId).orElseThrow().getStatus()).isEqualTo(SuggestionStatus.PENDING);
    }

    @Test
    void invalidAiOutputFallsBackToRuleBased() {
        SuggestionView v = orchestrator.requestNew("APT-001", "BAD_AI", "STAFF");
        assertThat(v.fallbackUsed()).isTrue();
        assertThat(v.strategy()).isEqualTo("RULE_BASED");
        assertThat(v.recommendedSlot()).isNotNull();
        assertThat(v.reasoning()).doesNotContain("SLT-").doesNotContain("PRV-");
        assertThat(slots.findById(v.recommendedSlot().id()).orElseThrow().getStatus()).isEqualTo(SlotStatus.OPEN);
    }
}
