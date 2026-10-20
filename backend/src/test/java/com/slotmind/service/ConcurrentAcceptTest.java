package com.slotmind.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.slotmind.domain.*;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.IntegrationTestBase;
import com.slotmind.web.ApiExceptionHandler;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGT-3 / decision.md §6: two front-desk users accept two suggestions that target the same slot at the same time.
 * Exactly one wins; the other gets 409 SLOT_UNAVAILABLE; nothing is double-booked and the loser is fully rolled back.
 */
class ConcurrentAcceptTest extends IntegrationTestBase {

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    record Race(World w, Appointment second, Slot secondOld, RescheduleSuggestion g1, RescheduleSuggestion g2) {}

    private Race setUpRace() {
        World w = fx.world();
        Slot secondOld = fx.slot(w.a(), w.room(), 61, 30, SlotStatus.BOOKED);
        Appointment second = fx.appointment(w.a(), secondOld);
        RescheduleSuggestion g1 = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.PROVIDER_OFFLINE);
        RescheduleSuggestion g2 = fx.pendingSuggestion(second.getId(), w.b90().getId(), TriggerReason.PROVIDER_OFFLINE);
        return new Race(w, second, secondOld, g1, g2);
    }

    private HttpResponse<String> patchAccept(String suggestionId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + "/suggestions/" + suggestionId))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"status\":\"ACCEPTED\",\"actor\":\"STAFF\"}"))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Invariants after any race: one appointment on the target slot, the loser untouched. */
    private void assertNoDoubleBooking(Race r, String winnerAppt, String winnerSuggestion) {
        String target = r.w().b90().getId();
        assertThat(fx.slotStatus(target)).isEqualTo(SlotStatus.BOOKED);
        assertThat(appointments.findBySlotId(target)).get().extracting(Appointment::getId).isEqualTo(winnerAppt);
        assertThat(fx.apptStatus(winnerAppt)).isEqualTo(AppointmentStatus.RESCHEDULED);
        assertThat(fx.suggestionStatus(winnerSuggestion)).isEqualTo(SuggestionStatus.ACCEPTED);

        boolean firstWon = winnerAppt.equals(r.w().appt().getId());
        String loserAppt = firstWon ? r.second().getId() : r.w().appt().getId();
        String loserOldSlot = firstWon ? r.secondOld().getId() : r.w().booked().getId();
        String loserSuggestion = firstWon ? r.g2().getId() : r.g1().getId();
        String winnerOldSlot = firstWon ? r.w().booked().getId() : r.secondOld().getId();
        assertThat(fx.apptStatus(loserAppt)).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
        assertThat(fx.apptSlotId(loserAppt)).isEqualTo(loserOldSlot);
        assertThat(fx.slotStatus(loserOldSlot)).isEqualTo(SlotStatus.BOOKED);
        assertThat(fx.suggestionStatus(loserSuggestion)).isEqualTo(SuggestionStatus.PENDING);
        assertThat(fx.slotStatus(winnerOldSlot)).isEqualTo(SlotStatus.OPEN);
    }

    @RepeatedTest(5)
    void twoConcurrentHttpAcceptsOfTheSameSlotExactlyOneWins() throws Exception {
        Race r = setUpRace();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (RescheduleSuggestion g : List.of(r.g1(), r.g2())) {
                futures.add(pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return patchAccept(g.getId()); }));
            }
            HttpResponse<String> res1 = futures.get(0).get(30, TimeUnit.SECONDS);
            HttpResponse<String> res2 = futures.get(1).get(30, TimeUnit.SECONDS);

            assertThat(List.of(res1.statusCode(), res2.statusCode())).as(res1.body() + " / " + res2.body())
                    .containsExactlyInAnyOrder(200, 409);
            HttpResponse<String> loser = res1.statusCode() == 409 ? res1 : res2;
            JsonNode err = json.readTree(loser.body());
            assertThat(err.get("error").asText()).isEqualTo("SLOT_UNAVAILABLE");
            assertThat(err.get("message").asText()).isNotBlank();

            boolean firstWon = res1.statusCode() == 200;
            assertNoDoubleBooking(r, firstWon ? r.w().appt().getId() : r.second().getId(), firstWon ? r.g1().getId() : r.g2().getId());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Forces the worst interleaving deterministically: transaction 1 has already read the slot as OPEN (version v)
     * when transaction 2 commits the booking. Transaction 1 must then fail on @Version and roll back everything.
     */
    @Test
    void staleReadLosesOnTheVersionCheckAndRollsBackCompletely() throws Exception {
        Race r = setUpRace();
        String target = r.w().b90().getId();
        CountDownLatch slotRead = new CountDownLatch(1);
        CountDownLatch otherCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                tx.executeWithoutResult(s -> {
                    Slot seen = slots.findById(target).orElseThrow(); // version v, OPEN, now in this persistence context
                    assertThat(seen.getStatus()).isEqualTo(SlotStatus.OPEN);
                    slotRead.countDown();
                    try { otherCommitted.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                    suggestionService.resolve(r.g1().getId(), SuggestionStatus.ACCEPTED, "STAFF");
                });
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "staff-1");
        t1.start();
        assertThat(slotRead.await(10, TimeUnit.SECONDS)).isTrue();

        suggestionService.resolve(r.g2().getId(), SuggestionStatus.ACCEPTED, "STAFF"); // staff-2 commits first
        otherCommitted.countDown();
        t1.join(20_000);
        assertThat(t1.isAlive()).isFalse();

        assertThat(failure.get()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(new ApiExceptionHandler().conflict((Exception) failure.get()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(new ApiExceptionHandler().conflict((Exception) failure.get()).getBody().error()).isEqualTo("SLOT_UNAVAILABLE");
        assertNoDoubleBooking(r, r.second().getId(), r.g2().getId());
    }

    @Test
    void sequentialSecondAcceptOfTheSameSlotIs409() throws Exception {
        Race r = setUpRace();
        assertThat(patchAccept(r.g1().getId()).statusCode()).isEqualTo(200);
        HttpResponse<String> second = patchAccept(r.g2().getId());
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(json.readTree(second.body()).get("error").asText()).isEqualTo("SLOT_UNAVAILABLE");
        assertNoDoubleBooking(r, r.w().appt().getId(), r.g1().getId());
    }
}
