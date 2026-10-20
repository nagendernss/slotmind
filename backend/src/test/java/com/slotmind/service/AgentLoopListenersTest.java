package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.support.Fixture;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** decision.md §7: the async AFTER_COMMIT agentic loop. */
class AgentLoopListenersTest extends IntegrationTestBase {

    private List<RescheduleSuggestion> all(String apptId) { return suggestions.findDetailedByAppointmentId(apptId); }

    private boolean activityContains(String... parts) {
        return activityRepo.findTop100ByOrderByIdDesc().stream().anyMatch(a -> {
            for (String p : parts) if (!a.getMessage().contains(p)) return false;
            return true;
        });
    }

    private long activityCount(String... parts) {
        return activityRepo.findAll().stream().filter(a -> {
            for (String p : parts) if (!a.getMessage().contains(p)) return false;
            return true;
        }).count();
    }

    @Test
    void providerOfflineCreatesExactlyOneSuggestionPerActiveAppointmentOfThatProvider() {
        World w = fx.world();
        Slot booked2 = fx.slot(w.a(), w.room(), 120, 30, SlotStatus.BOOKED);
        Appointment second = fx.appointment(w.a(), booked2, AppointmentType.FOLLOW_UP, "+1-555-0100", AppointmentStatus.CONFIRMED);
        Slot doneSlot = fx.slot(w.a(), w.room(), 30, 30, SlotStatus.BOOKED);
        Appointment completed = fx.appointment(w.a(), doneSlot, AppointmentType.ROUTINE, null, AppointmentStatus.COMPLETED);
        Slot bBooked = fx.slot(w.b(), w.room(), 45, 30, SlotStatus.BOOKED);
        Appointment otherProvider = fx.appointment(w.b(), bBooked);
        fx.slot(w.b(), w.room(), 300, 30, SlotStatus.OPEN); // enough room for everyone

        long t0 = System.nanoTime();
        providerService.changeStatus(w.a().getId(), ProviderStatus.OFFLINE);
        long returnedAfterMs = (System.nanoTime() - t0) / 1_000_000;

        Fixture.await("both suggestions", () -> all(w.appt().getId()).size() == 1 && all(second.getId()).size() == 1);
        Fixture.await("loop summary", () -> activityContains(w.a().getName() + " went offline", "2 affected"));

        for (String id : List.of(w.appt().getId(), second.getId())) {
            List<RescheduleSuggestion> s = all(id);
            assertThat(s).hasSize(1);
            assertThat(s.get(0).getStatus()).isEqualTo(SuggestionStatus.PENDING);
            assertThat(s.get(0).getTriggerReason()).isEqualTo(TriggerReason.PROVIDER_OFFLINE);
            assertThat(s.get(0).getRecommendedSlot()).isNotNull();
            assertThat(s.get(0).getRecommendedSlot().getProvider().getId()).isEqualTo(w.b().getId()); // A is offline
            assertThat(s.get(0).getAlternateProviderId()).isEqualTo(w.b().getId());
            assertThat(fx.apptStatus(id)).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
        }
        // decision §15.2: the two suggestions do not compete for the same slot
        assertThat(all(w.appt().getId()).get(0).getRecommendedSlot().getId())
                .isNotEqualTo(all(second.getId()).get(0).getRecommendedSlot().getId());
        assertThat(all(completed.getId())).isEmpty();
        assertThat(all(otherProvider.getId())).isEmpty();
        assertThat(fx.apptStatus(completed.getId())).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(returnedAfterMs).isLessThan(5_000);
    }

    @Test
    void offlineAgainIsIdempotent() {
        World w = fx.world();
        providerService.changeStatus(w.a().getId(), ProviderStatus.OFFLINE);
        Fixture.await("first suggestion", () -> all(w.appt().getId()).size() == 1);
        String first = all(w.appt().getId()).get(0).getId();

        providerService.changeStatus(w.a().getId(), ProviderStatus.AVAILABLE);
        providerService.changeStatus(w.a().getId(), ProviderStatus.OFFLINE);
        Fixture.await("second loop skipped the appointment", () -> activityContains("Skipped", w.appt().getId()));
        assertThat(all(w.appt().getId())).extracting(RescheduleSuggestion::getId).containsExactly(first);
        assertThat(fx.suggestionStatus(first)).isEqualTo(SuggestionStatus.PENDING);
    }

    @Test
    void slotCancelledCreatesOneSuggestionForTheBookedAppointment() {
        World w = fx.world();
        slotService.changeStatus(w.booked().getId(), SlotStatus.CANCELLED);
        Fixture.await("slot-cancelled suggestion", () -> all(w.appt().getId()).size() == 1);
        Fixture.await("loop summary", () -> activityContains("slot " + w.booked().getId() + " was cancelled", w.appt().getId()));
        RescheduleSuggestion g = all(w.appt().getId()).get(0);
        assertThat(g.getTriggerReason()).isEqualTo(TriggerReason.SLOT_CANCELLED);
        assertThat(g.getStatus()).isEqualTo(SuggestionStatus.PENDING);
        assertThat(g.getRecommendedSlot()).isNotNull();
        assertThat(g.getRecommendedSlot().getId()).isNotEqualTo(w.booked().getId());
        assertThat(g.getOriginalSlot().getId()).isEqualTo(w.booked().getId());
        assertThat(fx.apptStatus(w.appt().getId())).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
    }

    @Test
    void cancellingAnUnbookedSlotOrACompletedAppointmentsSlotCreatesNothing() {
        World w = fx.world();
        slotService.changeStatus(w.b240().getId(), SlotStatus.CANCELLED);
        Fixture.await("no-op loop", () -> activityContains("slot " + w.b240().getId() + " cancelled", "no booked appointment"));

        appointmentService.complete(w.appt().getId());
        slotService.changeStatus(w.booked().getId(), SlotStatus.CANCELLED);
        Fixture.await("no-op loop for completed", () -> activityContains("slot " + w.booked().getId() + " cancelled", "no booked appointment"));
        assertThat(all(w.appt().getId())).isEmpty();
    }

    @Test
    void loopRunsOnlyAfterCommitNeverForARolledBackChange() {
        World rolledBack = fx.world();
        World sentinel = fx.world();
        tx.executeWithoutResult(s -> {
            providerService.changeStatus(rolledBack.a().getId(), ProviderStatus.OFFLINE);
            slotService.changeStatus(rolledBack.booked().getId(), SlotStatus.CANCELLED);
            // still inside the transaction: the async loop must not have started yet
            assertThat(suggestions.findDetailedByAppointmentId(rolledBack.appt().getId())).isEmpty();
            s.setRollbackOnly();
        });
        // A committed change published afterwards proves the async machinery is running.
        providerService.changeStatus(sentinel.a().getId(), ProviderStatus.OFFLINE);
        Fixture.await("sentinel suggestion", () -> all(sentinel.appt().getId()).size() == 1);
        Fixture.await("sentinel summary", () -> activityContains(sentinel.a().getName() + " went offline"));

        assertThat(providers.findById(rolledBack.a().getId()).orElseThrow().getStatus()).isEqualTo(ProviderStatus.BUSY);
        assertThat(fx.slotStatus(rolledBack.booked().getId())).isEqualTo(SlotStatus.BOOKED);
        assertThat(all(rolledBack.appt().getId())).isEmpty();
        assertThat(fx.apptStatus(rolledBack.appt().getId())).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(activityCount(rolledBack.a().getName() + " went offline")).isZero();
    }

    @Test
    void offlineProviderAppointmentAlreadyRescheduledOntoThatProviderIsAffectedToo() {
        World w = fx.world();
        Slot bBooked = fx.slot(w.b(), w.room(), 50, 30, SlotStatus.BOOKED);
        Appointment onB = fx.appointment(w.b(), bBooked);
        // move w.appt onto provider B -> RESCHEDULED
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "STAFF");
        fx.slot(w.a(), w.room(), 400, 30, SlotStatus.OPEN);
        fx.slot(w.a(), w.room(), 420, 30, SlotStatus.OPEN);

        providerService.changeStatus(w.b().getId(), ProviderStatus.OFFLINE);
        Fixture.await("both of B's appointments processed", () ->
                suggestions.findByAppointmentIdAndStatus(w.appt().getId(), SuggestionStatus.PENDING).size() == 1
                        && suggestions.findByAppointmentIdAndStatus(onB.getId(), SuggestionStatus.PENDING).size() == 1);
        assertThat(suggestions.findByAppointmentIdAndStatus(w.appt().getId(), SuggestionStatus.PENDING).get(0).getTriggerReason())
                .isEqualTo(TriggerReason.PROVIDER_OFFLINE);
    }
}
