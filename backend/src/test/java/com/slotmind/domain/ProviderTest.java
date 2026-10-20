package com.slotmind.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderTest {
    private final Clinic clinic = new Clinic("CLN-001", "Downtown");

    private Provider provider(ProviderStatus status) {
        return new Provider("PRV-001", "Dr. Patel", Specialty.PEDIATRIC, status, clinic, 6);
    }

    @Test
    void constructorExposesAllFields() {
        Provider p = provider(ProviderStatus.BUSY);
        assertThat(p.getId()).isEqualTo("PRV-001");
        assertThat(p.getName()).isEqualTo("Dr. Patel");
        assertThat(p.getSpecialty()).isEqualTo(Specialty.PEDIATRIC);
        assertThat(p.getStatus()).isEqualTo(ProviderStatus.BUSY);
        assertThat(p.getClinic()).isSameAs(clinic);
        assertThat(p.getMaxDailyAppointments()).isEqualTo(6);
    }

    /** Every move is allowed; the return value is true only on a transition INTO offline. */
    @ParameterizedTest
    @CsvSource({
            "AVAILABLE,AVAILABLE,false", "AVAILABLE,BUSY,false", "AVAILABLE,OFFLINE,true",
            "BUSY,AVAILABLE,false", "BUSY,BUSY,false", "BUSY,OFFLINE,true",
            "OFFLINE,AVAILABLE,false", "OFFLINE,BUSY,false", "OFFLINE,OFFLINE,false"})
    void changeStatus(ProviderStatus from, ProviderStatus to, boolean wentOffline) {
        Provider p = provider(from);
        assertThat(p.changeStatus(to)).isEqualTo(wentOffline);
        assertThat(p.getStatus()).isEqualTo(to);
    }

    @Test
    void changeStatusRejectsNull() {
        Provider p = provider(ProviderStatus.AVAILABLE);
        assertThatThrownBy(() -> p.changeStatus(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.getStatus()).isEqualTo(ProviderStatus.AVAILABLE);
    }

    @Test
    void isOnlineUnlessOffline() {
        assertThat(provider(ProviderStatus.AVAILABLE).isOnline()).isTrue();
        assertThat(provider(ProviderStatus.BUSY).isOnline()).isTrue();
        assertThat(provider(ProviderStatus.OFFLINE).isOnline()).isFalse();
    }
}
