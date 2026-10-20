package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.support.Fixture;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.IntegrationTestBase;
import com.slotmind.view.Views.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LoadService, QueryService views and ActivityService. */
class QueryLoadActivityServiceTest extends IntegrationTestBase {

    @Test
    void bookedTodayCountsOnlyTodaysBookedSlotsPerProvider() {
        Clinic c = fx.clinic();
        Provider busy = fx.provider(c, Specialty.GENERAL);
        Provider idle = fx.provider(c, Specialty.GENERAL);
        var today = LocalDate.now().atStartOfDay();
        fx.slotAt(busy, null, today.plusSeconds(10), 30, SlotStatus.BOOKED);
        fx.slotAt(busy, null, today.plusSeconds(20), 30, SlotStatus.BOOKED);
        fx.slotAt(busy, null, today.plusSeconds(30), 30, SlotStatus.OPEN);
        fx.slotAt(busy, null, today.plusSeconds(40), 30, SlotStatus.CANCELLED);
        fx.slotAt(busy, null, today.minusMinutes(1), 30, SlotStatus.BOOKED);
        fx.slotAt(busy, null, today.plusDays(1), 30, SlotStatus.BOOKED);
        fx.slotAt(idle, null, today.plusSeconds(10), 30, SlotStatus.OPEN);

        var load = loadService.bookedToday();
        assertThat(load.get(busy.getId())).isEqualTo(2);
        assertThat(load).doesNotContainKey(idle.getId());
    }

    @Test
    void scheduleShowsProvidersSlotsAppointmentsAndHeldSlots() {
        World w = fx.world();
        RescheduleSuggestion held = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        Slot yesterday = fx.slotAt(w.b(), w.room(), LocalDate.now().minusDays(1).atTime(10, 0), 30, SlotStatus.OPEN);

        ScheduleView view = queryService.schedule();
        List<String> providerIds = view.providers().stream().map(p -> p.provider().id()).toList();
        assertThat(providerIds).contains(w.a().getId(), w.b().getId()).isSorted();

        ScheduleProvider a = view.providers().stream().filter(p -> p.provider().id().equals(w.a().getId())).findFirst().orElseThrow();
        assertThat(a.provider().clinicId()).isEqualTo(w.clinic().getId());
        assertThat(a.slots()).extracting(ScheduleSlot::id).containsExactly(w.booked().getId(), w.a180().getId());
        ScheduleSlot bookedSlot = a.slots().get(0);
        assertThat(bookedSlot.appointment()).isEqualTo(new ScheduleAppointment(w.appt().getId(), w.appt().getPatientName(),
                AppointmentType.ROUTINE, AppointmentStatus.RESCHEDULE_PENDING));
        assertThat(bookedSlot.status()).isEqualTo(SlotStatus.BOOKED);
        assertThat(bookedSlot.roomName()).isEqualTo(w.room().getName());
        assertThat(bookedSlot.heldBySuggestionId()).isNull();

        ScheduleProvider b = view.providers().stream().filter(p -> p.provider().id().equals(w.b().getId())).findFirst().orElseThrow();
        assertThat(b.slots()).extracting(ScheduleSlot::id).containsExactly(w.b90().getId(), w.b240().getId())
                .doesNotContain(yesterday.getId());
        assertThat(b.slots().get(0).heldBySuggestionId()).isEqualTo(held.getId());
        assertThat(b.slots().get(0).appointment()).isNull();
        assertThat(b.slots().get(1).heldBySuggestionId()).isNull();
    }

    @Test
    void roomsAndClinics() {
        Clinic c = fx.clinic();
        Room r = fx.room(c, "TRIAGE", "EXAM", "PEDIATRIC");
        RoomView rv = queryService.rooms().stream().filter(x -> x.id().equals(r.getId())).findFirst().orElseThrow();
        assertThat(rv).isEqualTo(new RoomView(r.getId(), r.getName(), c.getId(), c.getName(), List.of("EXAM", "PEDIATRIC", "TRIAGE")));
        assertThat(queryService.rooms()).extracting(RoomView::id).isSorted();
        assertThat(queryService.clinics()).contains(new ClinicView(c.getId(), c.getName()));
    }

    @Test
    void portalShowsAppointmentAndOnlyPendingSuggestions() {
        World w = fx.world();
        RescheduleSuggestion rejected = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        suggestionService.resolve(rejected.getId(), SuggestionStatus.REJECTED, "PATIENT");
        RescheduleSuggestion open = fx.pendingSuggestion(w.appt().getId(), w.b240().getId(), TriggerReason.INITIAL);

        PortalView v = queryService.portal(w.appt().getId());
        assertThat(v.appointment().id()).isEqualTo(w.appt().getId());
        assertThat(v.appointment().status()).isEqualTo(AppointmentStatus.RESCHEDULE_PENDING);
        assertThat(v.suggestions()).extracting(SuggestionView::id).containsExactly(open.getId());
        assertThat(v.suggestions().get(0).recommendedSlot().id()).isEqualTo(w.b240().getId());

        World plain = fx.world();
        assertThat(queryService.portal(plain.appt().getId()).suggestions()).isEmpty();
        assertThatThrownBy(() -> queryService.portal("APT-NOPE")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void activityIsNewestFirstCappedAt100AndSurvivesRollback() {
        String marker = "marker-" + Fixture.uid();
        activityService.log(ActivityKind.WARN, marker + " first");
        tx.executeWithoutResult(s -> {
            activityService.log(ActivityKind.INFO, marker + " inside rolled back tx");
            s.setRollbackOnly();
        });
        List<ActivityView> recent = activityService.recent();
        assertThat(recent).hasSizeLessThanOrEqualTo(100);
        assertThat(recent).extracting(ActivityView::id).isSortedAccordingTo((a, b) -> Long.compare(b, a));
        List<ActivityView> mine = recent.stream().filter(a -> a.message().startsWith(marker)).toList();
        assertThat(mine).extracting(ActivityView::message).containsExactly(marker + " inside rolled back tx", marker + " first");
        assertThat(mine.get(1).kind()).isEqualTo(ActivityKind.WARN);
        assertThat(mine.get(1).at()).isNotNull();

        for (int i = 0; i < 105; i++) activityService.log(ActivityKind.INFO, "filler " + i);
        assertThat(activityService.recent()).hasSize(100);
    }

    @Test
    void veryLongActivityMessagesAreTruncated() {
        String prefix = "long-" + Fixture.uid();
        activityService.log(ActivityKind.INFO, prefix + "x".repeat(5000));
        assertThat(activityService.recent()).filteredOn(a -> a.message().startsWith(prefix)).singleElement()
                .satisfies(a -> assertThat(a.message()).hasSize(2000).endsWith("..."));
    }
}
