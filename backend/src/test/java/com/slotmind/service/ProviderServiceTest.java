package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.service.DomainEvents.ProviderWentOffline;
import com.slotmind.support.Fixture;
import com.slotmind.support.IntegrationTestBase;
import com.slotmind.view.Views.ProviderView;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderServiceTest extends IntegrationTestBase {

    private long offlineEventsFor(String providerId) {
        return recorder.of(ProviderWentOffline.class).stream().filter(e -> e.providerId().equals(providerId)).count();
    }

    @Test
    void listIncludesClinicAndTodaysLoad() {
        Clinic c = fx.clinic();
        Provider p = fx.provider(c, Specialty.PEDIATRIC, ProviderStatus.BUSY, 7);
        var today = LocalDate.now().atStartOfDay();
        fx.slotAt(p, null, today.plusSeconds(30), 30, SlotStatus.BOOKED);
        fx.slotAt(p, null, today.plusSeconds(40), 30, SlotStatus.BOOKED);
        fx.slotAt(p, null, today.plusSeconds(50), 30, SlotStatus.OPEN);
        fx.slotAt(p, null, today.plusDays(1).plusHours(9), 30, SlotStatus.BOOKED);

        ProviderView v = providerService.list().stream().filter(x -> x.id().equals(p.getId())).findFirst().orElseThrow();
        assertThat(v.name()).isEqualTo(p.getName());
        assertThat(v.specialty()).isEqualTo(Specialty.PEDIATRIC);
        assertThat(v.status()).isEqualTo(ProviderStatus.BUSY);
        assertThat(v.clinicId()).isEqualTo(c.getId());
        assertThat(v.clinicName()).isEqualTo(c.getName());
        assertThat(v.maxDailyAppointments()).isEqualTo(7);
        assertThat(v.bookedToday()).isEqualTo(2);
        assertThat(providerService.list()).extracting(ProviderView::id).isSorted();
    }

    @Test
    void nonOfflineChangesDoNotPublishEvents() {
        Provider p = fx.provider(fx.clinic(), Specialty.GENERAL);
        assertThat(providerService.changeStatus(p.getId(), ProviderStatus.BUSY).status()).isEqualTo(ProviderStatus.BUSY);
        assertThat(providerService.changeStatus(p.getId(), ProviderStatus.AVAILABLE).status()).isEqualTo(ProviderStatus.AVAILABLE);
        assertThat(providers.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(ProviderStatus.AVAILABLE);
        assertThat(offlineEventsFor(p.getId())).isZero();
    }

    @Test
    void goingOfflinePublishesExactlyOneEventAndRepeatedOfflineDoesNot() {
        Provider p = fx.provider(fx.clinic(), Specialty.GENERAL);
        ProviderView v = providerService.changeStatus(p.getId(), ProviderStatus.OFFLINE);
        assertThat(v.status()).isEqualTo(ProviderStatus.OFFLINE);
        assertThat(providers.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(ProviderStatus.OFFLINE);
        assertThat(offlineEventsFor(p.getId())).isEqualTo(1);
        assertThat(recorder.of(ProviderWentOffline.class)).filteredOn(e -> e.providerId().equals(p.getId()))
                .extracting(ProviderWentOffline::providerName).containsExactly(p.getName());

        providerService.changeStatus(p.getId(), ProviderStatus.OFFLINE); // already offline: no new loop
        assertThat(offlineEventsFor(p.getId())).isEqualTo(1);

        providerService.changeStatus(p.getId(), ProviderStatus.BUSY);
        providerService.changeStatus(p.getId(), ProviderStatus.OFFLINE);
        assertThat(offlineEventsFor(p.getId())).isEqualTo(2);
        Fixture.await("activity for offline provider", () -> activityRepo.findTop100ByOrderByIdDesc().stream()
                .anyMatch(a -> a.getMessage().contains(p.getName() + " went offline")));
    }

    @Test
    void validation() {
        Provider p = fx.provider(fx.clinic(), Specialty.GENERAL);
        assertThatThrownBy(() -> providerService.changeStatus(p.getId(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> providerService.changeStatus("PRV-NOPE", ProviderStatus.BUSY)).isInstanceOf(NotFoundException.class);
    }
}
