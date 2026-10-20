package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.service.AppointmentService.BookRequest;
import com.slotmind.service.DomainEvents.SlotFreed;
import com.slotmind.service.DomainEvents.SuggestionAccepted;
import com.slotmind.support.Fixture;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.IntegrationTestBase;
import com.slotmind.view.Views.SuggestionView;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuggestionServiceTest extends IntegrationTestBase {

    private List<SuggestionAccepted> acceptedEvents(String suggestionId) {
        return recorder.of(SuggestionAccepted.class).stream().filter(e -> e.suggestionId().equals(suggestionId)).toList();
    }

    private long freedEvents(String slotId) {
        return recorder.of(SlotFreed.class).stream().filter(e -> e.slotId().equals(slotId)).count();
    }

    /** Everything about the appointment, its old slot and the target slot is exactly as before. */
    private void assertUntouched(World w, String suggestionId, String targetSlotId) {
        assertThat(fx.apptStatus(w.appt().getId())).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
        assertThat(fx.apptSlotId(w.appt().getId())).isEqualTo(w.booked().getId());
        assertThat(appointments.findDetailed(w.appt().getId()).orElseThrow().getProvider().getId()).isEqualTo(w.a().getId());
        assertThat(fx.slotStatus(w.booked().getId())).isEqualTo(SlotStatus.BOOKED);
        assertThat(fx.suggestionStatus(suggestionId)).isEqualTo(SuggestionStatus.PENDING);
        assertThat(suggestions.findById(suggestionId).orElseThrow().getPendingKey()).isNotNull();
        assertThat(acceptedEvents(suggestionId)).isEmpty();
        assertThat(freedEvents(w.booked().getId())).isZero();
    }

    @Test
    void acceptIsAtomicMovesAppointmentFreesOldBooksNewAndSupersedesSiblings() {
        World w = fx.world();
        RescheduleSuggestion chosen = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.PROVIDER_OFFLINE);
        RescheduleSuggestion sibling = fx.pendingSuggestion(w.appt().getId(), w.b240().getId(), TriggerReason.INITIAL);

        SuggestionView v = suggestionService.resolve(chosen.getId(), SuggestionStatus.ACCEPTED, "staff");

        assertThat(v.status()).isEqualTo(SuggestionStatus.ACCEPTED);
        assertThat(v.resolvedAt()).isNotNull();
        assertThat(v.appointmentStatus()).isEqualTo(AppointmentStatus.RESCHEDULED);
        assertThat(v.recommendedSlot().status()).isEqualTo(SlotStatus.BOOKED);

        assertThat(fx.apptStatus(w.appt().getId())).isEqualTo(AppointmentStatus.RESCHEDULED);
        assertThat(fx.apptSlotId(w.appt().getId())).isEqualTo(w.b90().getId());
        assertThat(appointments.findDetailed(w.appt().getId()).orElseThrow().getProvider().getId()).isEqualTo(w.b().getId());
        assertThat(fx.slotStatus(w.b90().getId())).isEqualTo(SlotStatus.BOOKED);
        assertThat(fx.slotStatus(w.booked().getId())).isEqualTo(SlotStatus.OPEN);
        assertThat(fx.slotStatus(w.b240().getId())).isEqualTo(SlotStatus.OPEN);

        RescheduleSuggestion acc = suggestions.findById(chosen.getId()).orElseThrow();
        assertThat(acc.getPendingKey()).isNull();
        assertThat(acc.getResolvedBy()).isEqualTo("STAFF");
        assertThat(fx.suggestionStatus(sibling.getId())).isEqualTo(SuggestionStatus.SUPERSEDED);
        assertThat(suggestions.findById(sibling.getId()).orElseThrow().getPendingKey()).isNull();

        assertThat(freedEvents(w.booked().getId())).isEqualTo(1);
        assertThat(acceptedEvents(chosen.getId())).singleElement().satisfies(e -> {
            assertThat(e.appointmentId()).isEqualTo(w.appt().getId());
            assertThat(e.actor()).isEqualTo("STAFF");
        });
        // exactly one appointment holds the new slot
        assertThat(appointments.findBySlotId(w.b90().getId())).get().extracting(Appointment::getId).isEqualTo(w.appt().getId());
    }

    @Test
    void acceptSendsNotificationAfterCommit() {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, null);
        Fixture.await("notification row", () -> notifications.findAllByOrderByIdDesc().stream()
                .anyMatch(n -> n.getAppointmentId().equals(w.appt().getId())));
        Notification n = notifications.findAllByOrderByIdDesc().stream()
                .filter(x -> x.getAppointmentId().equals(w.appt().getId())).findFirst().orElseThrow();
        assertThat(n.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(n.getRecipient()).isEqualTo("patient@example.com");
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getMessage()).contains("has been moved to").contains(w.b().getName()).contains(w.clinic().getName());
    }

    @Test
    void secondAcceptIsInvalidState() {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "STAFF");
        assertThatThrownBy(() -> suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "STAFF"))
                .isInstanceOf(IllegalStateTransitionException.class).hasMessageContaining("ACCEPTED");
        assertThatThrownBy(() -> suggestionService.resolve(g.getId(), SuggestionStatus.REJECTED, "STAFF"))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(acceptedEvents(g.getId())).hasSize(1);
        assertThat(fx.apptSlotId(w.appt().getId())).isEqualTo(w.b90().getId());
    }

    @Test
    void acceptWhenTargetSlotWasTakenIsSlotUnavailableWithFullRollback() {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.PROVIDER_OFFLINE);
        appointmentService.book(new BookRequest("Walk-in", AppointmentType.ROUTINE, w.b().getId(), w.b90().getId(), null));

        assertThatThrownBy(() -> suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "STAFF"))
                .isInstanceOf(SlotUnavailableException.class).hasMessageContaining("no longer free");
        assertUntouched(w, g.getId(), w.b90().getId());
        assertThat(appointments.findBySlotId(w.b90().getId())).get().extracting(Appointment::getPatientName).isEqualTo("Walk-in");
    }

    @Test
    void acceptWhenTargetSlotWasCancelledIsSlotUnavailable() {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        slotService.changeStatus(w.b90().getId(), SlotStatus.CANCELLED);
        assertThatThrownBy(() -> suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "STAFF"))
                .isInstanceOf(SlotUnavailableException.class);
        assertUntouched(w, g.getId(), w.b90().getId());
    }

    @Test
    void acceptWhenTargetProviderWentOfflineIsSlotUnavailable() {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        providerService.changeStatus(w.b().getId(), ProviderStatus.OFFLINE);
        assertThatThrownBy(() -> suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "STAFF"))
                .isInstanceOf(SlotUnavailableException.class).hasMessageContaining("ProviderOnline");
        assertThat(fx.slotStatus(w.b90().getId())).isEqualTo(SlotStatus.OPEN);
        assertThat(fx.apptSlotId(w.appt().getId())).isEqualTo(w.booked().getId());
        assertThat(acceptedEvents(g.getId())).isEmpty();
    }

    @Test
    void acceptRechecksProviderDailyCapInsideTheTransaction() {
        World w = fx.world();
        Provider capped = fx.provider(w.clinic(), Specialty.GENERAL, ProviderStatus.AVAILABLE, 1);
        Slot target = fx.slot(capped, w.room(), 130, 30, SlotStatus.OPEN);
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), target.getId(), TriggerReason.INITIAL);
        // After the suggestion was made, the provider fills up for today.
        fx.slotAt(capped, w.room(), LocalDate.now().atStartOfDay().plusSeconds(30), 30, SlotStatus.BOOKED);

        assertThatThrownBy(() -> suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "STAFF"))
                .isInstanceOf(SlotUnavailableException.class).hasMessageContaining("ProviderDailyCap");
        assertUntouched(w, g.getId(), target.getId());
        assertThat(fx.slotStatus(target.getId())).isEqualTo(SlotStatus.OPEN);
    }

    @Test
    void dailyCapDoesNotBlockAMoveWithinTheSameProvider() {
        Clinic c = fx.clinic();
        Room room = fx.room(c, "EXAM");
        Provider p = fx.provider(c, Specialty.GENERAL, ProviderStatus.AVAILABLE, 1);
        Slot booked = fx.slotAt(p, room, LocalDate.now().atStartOfDay().plusSeconds(30), 30, SlotStatus.BOOKED);
        Appointment a = fx.appointment(p, booked);
        Slot later = fx.slot(p, room, 200, 30, SlotStatus.OPEN);
        RescheduleSuggestion g = fx.pendingSuggestion(a.getId(), later.getId(), TriggerReason.INITIAL);
        assertThat(suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "STAFF").status()).isEqualTo(SuggestionStatus.ACCEPTED);
    }

    @Test
    void acceptOfASuggestionWithoutSlotIsInvalidState() {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), null, TriggerReason.INITIAL);
        assertThatThrownBy(() -> suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "STAFF"))
                .isInstanceOf(IllegalStateTransitionException.class).hasMessageContaining("waitlist");
        assertThat(fx.suggestionStatus(g.getId())).isEqualTo(SuggestionStatus.PENDING);
    }

    @Test
    void acceptAfterSlotCancelledTriggerDoesNotFreeTheCancelledOldSlot() {
        World w = fx.world();
        slotService.changeStatus(w.booked().getId(), SlotStatus.CANCELLED);
        Fixture.await("slot-cancelled suggestion", () -> !suggestions.findByAppointmentIdAndStatus(w.appt().getId(), SuggestionStatus.PENDING).isEmpty());
        RescheduleSuggestion g = suggestions.findByAppointmentIdAndStatus(w.appt().getId(), SuggestionStatus.PENDING).get(0);
        assertThat(g.getTriggerReason()).isEqualTo(TriggerReason.SLOT_CANCELLED);

        SuggestionView v = suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "STAFF");
        assertThat(v.status()).isEqualTo(SuggestionStatus.ACCEPTED);
        assertThat(fx.slotStatus(w.booked().getId())).isEqualTo(SlotStatus.CANCELLED);
        assertThat(freedEvents(w.booked().getId())).isZero();
        assertThat(fx.slotStatus(v.recommendedSlot().id())).isEqualTo(SlotStatus.BOOKED);
    }

    @Test
    void rejectKeepsAppointmentPendingAndChangesNoSlot() {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        SuggestionView v = suggestionService.resolve(g.getId(), SuggestionStatus.REJECTED, " patient ");
        assertThat(v.status()).isEqualTo(SuggestionStatus.REJECTED);
        assertThat(v.resolvedAt()).isNotNull();
        RescheduleSuggestion stored = suggestions.findById(g.getId()).orElseThrow();
        assertThat(stored.getResolvedBy()).isEqualTo("PATIENT");
        assertThat(stored.getPendingKey()).isNull();
        assertThat(fx.apptStatus(w.appt().getId())).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
        assertThat(fx.slotStatus(w.b90().getId())).isEqualTo(SlotStatus.OPEN);
        assertThat(fx.slotStatus(w.booked().getId())).isEqualTo(SlotStatus.BOOKED);

        assertThatThrownBy(() -> suggestionService.resolve(g.getId(), SuggestionStatus.REJECTED, "STAFF"))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "STAFF"))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void acceptByPatientRecordsActor() {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        suggestionService.resolve(g.getId(), SuggestionStatus.ACCEPTED, "patient");
        assertThat(acceptedEvents(g.getId())).singleElement().extracting(SuggestionAccepted::actor).isEqualTo("PATIENT");
    }

    @Test
    void resolveValidation() {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        assertThatThrownBy(() -> suggestionService.resolve(g.getId(), SuggestionStatus.PENDING, "STAFF")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> suggestionService.resolve(g.getId(), SuggestionStatus.SUPERSEDED, "STAFF")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> suggestionService.resolve(g.getId(), null, "STAFF")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> suggestionService.resolve("SUG-NOPE", SuggestionStatus.ACCEPTED, "STAFF")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> suggestionService.resolve("SUG-NOPE", SuggestionStatus.REJECTED, "STAFF")).isInstanceOf(NotFoundException.class);
        assertThat(fx.suggestionStatus(g.getId())).isEqualTo(SuggestionStatus.PENDING);
    }

    @Test
    void listFiltersByStatusNewestFirstWithFullView() {
        World w = fx.world();
        RescheduleSuggestion older = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        suggestionService.resolve(older.getId(), SuggestionStatus.REJECTED, "STAFF");
        RescheduleSuggestion newer = fx.pendingSuggestion(w.appt().getId(), w.b240().getId(), TriggerReason.INITIAL);

        List<SuggestionView> pending = suggestionService.list(SuggestionStatus.PENDING);
        assertThat(pending).extracting(SuggestionView::id).contains(newer.getId()).doesNotContain(older.getId());
        assertThat(pending).allMatch(s -> s.status() == SuggestionStatus.PENDING);
        assertThat(suggestionService.list(SuggestionStatus.REJECTED)).extracting(SuggestionView::id).contains(older.getId());

        List<SuggestionView> all = suggestionService.list(null);
        assertThat(all).extracting(SuggestionView::createdAt).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(all.indexOf(all.stream().filter(s -> s.id().equals(newer.getId())).findFirst().orElseThrow()))
                .isLessThan(all.indexOf(all.stream().filter(s -> s.id().equals(older.getId())).findFirst().orElseThrow()));
        SuggestionView v = all.stream().filter(s -> s.id().equals(newer.getId())).findFirst().orElseThrow();
        assertThat(v.appointmentId()).isEqualTo(w.appt().getId());
        assertThat(v.patientName()).isEqualTo(w.appt().getPatientName());
        assertThat(v.appointmentType()).isEqualTo(AppointmentType.ROUTINE);
        assertThat(v.originalSlot().id()).isEqualTo(w.booked().getId());
        assertThat(v.originalSlot().providerName()).isEqualTo(w.a().getName());
        assertThat(v.recommendedSlot().id()).isEqualTo(w.b240().getId());
        assertThat(v.recommendedSlot().roomName()).isEqualTo(w.room().getName());
    }
}
