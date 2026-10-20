package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.service.DomainEvents.SlotCancelled;
import com.slotmind.service.DomainEvents.SlotFreed;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.IntegrationTestBase;
import com.slotmind.view.Views.SlotView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotServiceTest extends IntegrationTestBase {

    private long cancelledEvents(String slotId) {
        return recorder.of(SlotCancelled.class).stream().filter(e -> e.slotId().equals(slotId)).count();
    }

    private long freedEvents(String slotId) {
        return recorder.of(SlotFreed.class).stream().filter(e -> e.slotId().equals(slotId)).count();
    }

    @Test
    void listFiltersByProviderAndStatusOrderedByStartTime() {
        World w = fx.world();
        List<SlotView> forB = slotService.list(w.b().getId(), null);
        assertThat(forB).extracting(SlotView::id).containsExactly(w.b90().getId(), w.b240().getId());
        assertThat(forB.get(0).providerName()).isEqualTo(w.b().getName());
        assertThat(forB.get(0).roomId()).isEqualTo(w.room().getId());
        assertThat(forB.get(0).clinicName()).isEqualTo(w.clinic().getName());

        assertThat(slotService.list(w.a().getId(), SlotStatus.BOOKED)).extracting(SlotView::id).containsExactly(w.booked().getId());
        assertThat(slotService.list(w.a().getId(), SlotStatus.OPEN)).extracting(SlotView::id).containsExactly(w.a180().getId());
        assertThat(slotService.list(null, SlotStatus.OPEN)).extracting(SlotView::id).contains(w.a180().getId(), w.b90().getId())
                .doesNotContain(w.booked().getId());
        assertThat(slotService.list(null, null)).extracting(SlotView::startTime).isSorted();
    }

    @Test
    void cancellingPublishesSlotCancelled() {
        World w = fx.world();
        SlotView v = slotService.changeStatus(w.b240().getId(), SlotStatus.CANCELLED);
        assertThat(v.status()).isEqualTo(SlotStatus.CANCELLED);
        assertThat(fx.slotStatus(w.b240().getId())).isEqualTo(SlotStatus.CANCELLED);
        assertThat(cancelledEvents(w.b240().getId())).isEqualTo(1);

        // CANCELLED is terminal
        assertThatThrownBy(() -> slotService.changeStatus(w.b240().getId(), SlotStatus.CANCELLED))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> slotService.changeStatus(w.b240().getId(), SlotStatus.OPEN))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(cancelledEvents(w.b240().getId())).isEqualTo(1);
    }

    @Test
    void openOnAnOpenSlotIsANoOp() {
        World w = fx.world();
        assertThat(slotService.changeStatus(w.b90().getId(), SlotStatus.OPEN).status()).isEqualTo(SlotStatus.OPEN);
        assertThat(freedEvents(w.b90().getId())).isZero();
    }

    @Test
    void openOnASlotHeldByAnAppointmentIsRefused() {
        World w = fx.world();
        assertThatThrownBy(() -> slotService.changeStatus(w.booked().getId(), SlotStatus.OPEN))
                .isInstanceOf(IllegalStateTransitionException.class).hasMessageContaining(w.appt().getId());
        assertThat(fx.slotStatus(w.booked().getId())).isEqualTo(SlotStatus.BOOKED);
        assertThat(freedEvents(w.booked().getId())).isZero();
    }

    @Test
    void openOnAnUnreferencedBookedSlotFreesItAndPublishesSlotFreed() {
        World w = fx.world();
        Slot orphan = fx.slot(w.b(), w.room(), 500, 30, SlotStatus.BOOKED);
        assertThat(slotService.changeStatus(orphan.getId(), SlotStatus.OPEN).status()).isEqualTo(SlotStatus.OPEN);
        assertThat(fx.slotStatus(orphan.getId())).isEqualTo(SlotStatus.OPEN);
        assertThat(freedEvents(orphan.getId())).isEqualTo(1);
    }

    @Test
    void bookedAndNullAreValidationErrorsAndUnknownIsNotFound() {
        World w = fx.world();
        assertThatThrownBy(() -> slotService.changeStatus(w.b90().getId(), SlotStatus.BOOKED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> slotService.changeStatus(w.b90().getId(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> slotService.changeStatus("SLT-NOPE", SlotStatus.CANCELLED)).isInstanceOf(NotFoundException.class);
        assertThat(fx.slotStatus(w.b90().getId())).isEqualTo(SlotStatus.OPEN);
    }
}
