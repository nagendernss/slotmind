package com.slotmind.scheduling;

import com.slotmind.domain.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FiltersTest {
    private final Fx f = new Fx();

    @Test
    void namesAreDistinctAndOrderIsStable() {
        List<CandidateFilter> all = new ArrayList<>(List.of(new Filters.ClinicScopeFilter(), new Filters.ProviderDailyCapFilter(),
                new Filters.RoomEquipmentFilter(), new Filters.DurationFitFilter(), new Filters.SpecialtyMatchFilter(),
                new Filters.ProviderOnlineFilter(), new Filters.OpenFutureSlotFilter()));
        AnnotationAwareOrderComparator.sort(all);
        assertThat(all).extracting(CandidateFilter::name).containsExactly(
                "OpenFutureSlot", "ProviderOnline", "SpecialtyMatch", "DurationFit", "RoomEquipment", "ProviderDailyCap", "ClinicScope");
    }

    @Nested
    class OpenFutureSlot {
        private final CandidateFilter filter = new Filters.OpenFutureSlotFilter();

        @Test
        void acceptsOpenFutureSlot() {
            assertThat(filter.test(Fx.open("S", f.chen, null, 9, 0), f.fc())).isTrue();
        }

        @Test
        void rejectsBookedOrCancelled() {
            assertThat(filter.test(Fx.slot("S", f.chen, null, 9, 0, SlotStatus.BOOKED), f.fc())).isFalse();
            assertThat(filter.test(Fx.slot("S", f.chen, null, 9, 0, SlotStatus.CANCELLED), f.fc())).isFalse();
        }

        @Test
        void rejectsPastAndNowStartingSlots() {
            assertThat(filter.test(Fx.open("S", f.chen, null, 7, 59), f.fc())).isFalse();
            assertThat(filter.test(Fx.open("S", f.chen, null, 8, 0), f.fc())).as("starts exactly now").isFalse();
            assertThat(filter.test(Fx.open("S", f.chen, null, 8, 1), f.fc())).isTrue();
        }
    }

    @Nested
    class ProviderOnline {
        private final CandidateFilter filter = new Filters.ProviderOnlineFilter();

        @Test
        void acceptsAvailableAndBusy() {
            assertThat(filter.test(Fx.open("S", f.chen, null, 9, 0), f.fc())).isTrue();
            f.chen.changeStatus(ProviderStatus.BUSY);
            assertThat(filter.test(Fx.open("S", f.chen, null, 9, 0), f.fc())).isTrue();
        }

        @Test
        void rejectsOffline() {
            f.chen.changeStatus(ProviderStatus.OFFLINE);
            assertThat(filter.test(Fx.open("S", f.chen, null, 9, 0), f.fc())).isFalse();
        }
    }

    @Nested
    class SpecialtyMatch {
        private final CandidateFilter filter = new Filters.SpecialtyMatchFilter();

        @Test
        void acceptsSameSpecialty() {
            assertThat(filter.test(Fx.open("S", f.chen, null, 9, 0), f.fc())).isTrue();
            assertThat(filter.test(Fx.open("S", f.patel, null, 9, 0), f.fc())).isTrue();
        }

        @Test
        void rejectsOtherSpecialty() {
            assertThat(filter.test(Fx.open("S", f.kim, null, 9, 0), f.fc())).isFalse();
        }
    }

    @Nested
    class DurationFit {
        private final CandidateFilter filter = new Filters.DurationFitFilter();
        private final Slot thirty = Fx.open("S", f.chen, null, 9, 0);

        @Test
        void acceptsLongerOrEqual() {
            assertThat(filter.test(thirty, f.fc(20, Set.of(), Map.of(), null))).isTrue();
            assertThat(filter.test(thirty, f.fc(30, Set.of(), Map.of(), null))).isTrue();
            assertThat(filter.test(thirty, f.fc(0, Set.of(), Map.of(), null))).as("no rule").isTrue();
        }

        @Test
        void rejectsShorter() {
            assertThat(filter.test(thirty, f.fc(45, Set.of(), Map.of(), null))).isFalse();
            assertThat(filter.test(thirty, f.fc(31, Set.of(), Map.of(), null))).isFalse();
        }
    }

    @Nested
    class RoomEquipment {
        private final CandidateFilter filter = new Filters.RoomEquipmentFilter();

        @Test
        void noRequirementAcceptsAnyRoomEvenNone() {
            assertThat(filter.test(Fx.open("S", f.chen, null, 9, 0), f.fc())).isTrue();
            assertThat(filter.test(Fx.open("S", f.chen, f.exam2, 9, 0), f.fc())).isTrue();
        }

        @Test
        void acceptsRoomWithAllRequiredEquipment() {
            assertThat(filter.test(Fx.open("S", f.chen, f.exam1, 9, 0), f.fc(0, Set.of("TRIAGE"), Map.of(), null))).isTrue();
            assertThat(filter.test(Fx.open("S", f.chen, f.bigRoom, 9, 0), f.fc(0, Set.of("TRIAGE", "XRAY"), Map.of(), null))).isTrue();
        }

        @Test
        void rejectsMissingRoomOrEquipment() {
            FilterContext triage = f.fc(0, Set.of("TRIAGE"), Map.of(), null);
            assertThat(filter.test(Fx.open("S", f.chen, null, 9, 0), triage)).as("no room").isFalse();
            assertThat(filter.test(Fx.open("S", f.chen, f.exam2, 9, 0), triage)).as("bare room").isFalse();
            assertThat(filter.test(Fx.open("S", f.chen, f.exam1, 9, 0), f.fc(0, Set.of("TRIAGE", "XRAY"), Map.of(), null)))
                    .as("partially equipped").isFalse();
        }
    }

    @Nested
    class ProviderDailyCap {
        private final CandidateFilter filter = new Filters.ProviderDailyCapFilter();

        @Test
        void sameProviderAlwaysPassesEvenAtCap() {
            assertThat(filter.test(Fx.open("S", f.patel, null, 9, 0), f.fc(0, Set.of(), Map.of("PRV-001", 99), null))).isTrue();
        }

        @Test
        void otherProviderBelowCapPasses() {
            assertThat(filter.test(Fx.open("S", f.chen, null, 9, 0), f.fc(0, Set.of(), Map.of("PRV-002", 7), null))).isTrue();
            assertThat(filter.test(Fx.open("S", f.chen, null, 9, 0), f.fc())).as("no load entry = 0").isTrue();
        }

        @Test
        void otherProviderAtOrAboveCapRejected() {
            assertThat(filter.test(Fx.open("S", f.chen, null, 9, 0), f.fc(0, Set.of(), Map.of("PRV-002", 8), null))).isFalse();
            assertThat(filter.test(Fx.open("S", f.chen, null, 9, 0), f.fc(0, Set.of(), Map.of("PRV-002", 9), null))).isFalse();
            Provider zeroCap = new Provider("PRV-099", "Dr. Zero", Specialty.GENERAL, ProviderStatus.AVAILABLE, f.downtown, 0);
            assertThat(filter.test(Fx.open("S", zeroCap, null, 9, 0), f.fc())).isFalse();
        }

        @Test
        void todaysFullLoadDoesNotBlockAnotherDay() {
            Slot tomorrow = new Slot("S", f.chen, null, Fx.NOW.plusDays(1).withHour(9), 30, SlotStatus.OPEN);
            assertThat(filter.test(tomorrow, f.fc(0, Set.of(), Map.of("PRV-002", 8), null)))
                    .as("today's count must not apply to tomorrow").isTrue();
        }

        @Test
        void capIsCheckedAgainstTheSlotsOwnDay() {
            LocalDate today = Fx.NOW.toLocalDate(), tomorrow = today.plusDays(1);
            var byDay = Map.of(today, Map.of("PRV-002", 1), tomorrow, Map.of("PRV-002", 8));
            FilterContext fc = new FilterContext(f.appt, 0, Set.of(), Map.of("PRV-002", 1), null, Fx.NOW, byDay);
            assertThat(filter.test(Fx.open("S1", f.chen, null, 9, 0), fc)).as("today: 1 of 8").isTrue();
            Slot tomorrowSlot = new Slot("S2", f.chen, null, Fx.NOW.plusDays(1).withHour(9), 30, SlotStatus.OPEN);
            assertThat(filter.test(tomorrowSlot, fc)).as("tomorrow: 8 of 8").isFalse();
            Slot dayAfter = new Slot("S3", f.chen, null, Fx.NOW.plusDays(2).withHour(9), 30, SlotStatus.OPEN);
            assertThat(filter.test(dayAfter, fc)).as("no bookings that day").isTrue();
        }
    }

    @Nested
    class ClinicScope {
        private final CandidateFilter filter = new Filters.ClinicScopeFilter();

        @Test
        void widenedContextAcceptsEverything() {
            assertThat(filter.test(Fx.open("S", f.east, null, 9, 0), f.fc())).isTrue();
            Provider noClinic = new Provider("PRV-098", "Dr. Nowhere", Specialty.GENERAL, ProviderStatus.AVAILABLE, null, 8);
            assertThat(filter.test(Fx.open("S", noClinic, null, 9, 0), f.fc())).isTrue();
        }

        @Test
        void acceptsHomeClinic() {
            assertThat(filter.test(Fx.open("S", f.chen, null, 9, 0), f.fc(0, Set.of(), Map.of(), "CLN-001"))).isTrue();
        }

        @Test
        void rejectsOtherClinicOrUnknownClinic() {
            assertThat(filter.test(Fx.open("S", f.east, null, 9, 0), f.fc(0, Set.of(), Map.of(), "CLN-001"))).isFalse();
            Provider noClinic = new Provider("PRV-098", "Dr. Nowhere", Specialty.GENERAL, ProviderStatus.AVAILABLE, null, 8);
            assertThat(filter.test(Fx.open("S", noClinic, null, 9, 0), f.fc(0, Set.of(), Map.of(), "CLN-001"))).isFalse();
        }
    }
}
