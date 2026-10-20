package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.support.Fixture;
import com.slotmind.support.IntegrationTestBase;
import com.slotmind.view.Views.PreferenceView;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreferenceServiceTest extends IntegrationTestBase {

    private void visit(String patient, Provider p, int daysAgo, int hour, AppointmentStatus status) {
        Slot s = fx.slotAt(p, null, LocalDate.now().minusDays(daysAgo).atTime(hour, 0), 30, SlotStatus.BOOKED);
        appointments.save(new Appointment("APT-" + Fixture.uid(), patient, null, AppointmentType.ROUTINE, p, s, status));
    }

    private Provider provider() { return fx.provider(fx.clinic(), Specialty.GENERAL); }

    @Test
    void noHistoryMeansAny() {
        PreferenceView v = preferenceService.resolve("Nobody " + Fixture.uid());
        assertThat(v.preferredWindow()).isEqualTo(PreferredWindow.ANY);
        assertThat(v.source()).isEqualTo("DEFAULT");
    }

    @Test
    void inferredFromTheMajorityOfCompletedVisits() {
        Provider p = provider();
        String morningPerson = "Morning " + Fixture.uid();
        visit(morningPerson, p, 3, 9, AppointmentStatus.COMPLETED);
        visit(morningPerson, p, 4, 11, AppointmentStatus.COMPLETED);
        visit(morningPerson, p, 5, 15, AppointmentStatus.COMPLETED);
        assertThat(preferenceService.resolve(morningPerson))
                .isEqualTo(new PreferenceView(morningPerson, PreferredWindow.MORNING, "INFERRED"));
        assertThat(preferenceService.windowFor(morningPerson)).isEqualTo(PreferredWindow.MORNING);

        String afternoonPerson = "Afternoon " + Fixture.uid();
        visit(afternoonPerson, p, 3, 12, AppointmentStatus.COMPLETED); // noon counts as afternoon
        assertThat(preferenceService.resolve(afternoonPerson).preferredWindow()).isEqualTo(PreferredWindow.AFTERNOON);
    }

    @Test
    void tieOrOnlyNonCompletedVisitsMeansAny() {
        Provider p = provider();
        String tie = "Tie " + Fixture.uid();
        visit(tie, p, 3, 9, AppointmentStatus.COMPLETED);
        visit(tie, p, 4, 16, AppointmentStatus.COMPLETED);
        assertThat(preferenceService.resolve(tie).preferredWindow()).isEqualTo(PreferredWindow.ANY);

        String notCompleted = "Open " + Fixture.uid();
        visit(notCompleted, p, 3, 9, AppointmentStatus.CONFIRMED);
        assertThat(preferenceService.resolve(notCompleted).source()).isEqualTo("DEFAULT");
    }

    @Test
    void explicitBeatsInferredAndCanBeUpdated() {
        Provider p = provider();
        String name = "Explicit " + Fixture.uid();
        visit(name, p, 3, 9, AppointmentStatus.COMPLETED);
        assertThat(preferenceService.setExplicit(name, PreferredWindow.AFTERNOON))
                .isEqualTo(new PreferenceView(name, PreferredWindow.AFTERNOON, "EXPLICIT"));
        assertThat(preferenceService.resolve(name)).isEqualTo(new PreferenceView(name, PreferredWindow.AFTERNOON, "EXPLICIT"));

        preferenceService.setExplicit(name, PreferredWindow.ANY);
        assertThat(preferenceService.resolve(name)).isEqualTo(new PreferenceView(name, PreferredWindow.ANY, "EXPLICIT"));
        assertThat(preferenceService.windowFor(name)).isEqualTo(PreferredWindow.ANY);
    }

    @Test
    void explicitWindowIsRequired() {
        assertThatThrownBy(() -> preferenceService.setExplicit("X " + Fixture.uid(), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
