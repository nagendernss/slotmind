package com.slotmind.scheduling;

import com.slotmind.domain.PreferredWindow;
import com.slotmind.domain.Specialty;
import com.slotmind.domain.TriggerReason;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TextsTest {
    private final Fx f = new Fx();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 9, 0); // Tuesday

    @Test
    void whenSaysTodayTomorrowOrDate() {
        assertThat(Texts.when(LocalDateTime.of(2026, 10, 20, 14, 30), NOW)).isEqualTo("2:30 PM today");
        assertThat(Texts.when(LocalDateTime.of(2026, 10, 21, 9, 5), NOW)).isEqualTo("9:05 AM tomorrow");
        assertThat(Texts.when(LocalDateTime.of(2026, 10, 22, 0, 0), NOW)).isEqualTo("12:00 AM on Thursday, Oct 22");
        assertThat(Texts.when(LocalDateTime.of(2026, 10, 19, 12, 0), NOW)).as("past day").isEqualTo("12:00 PM on Monday, Oct 19");
    }

    @Test
    void timeFormatsTwelveHour() {
        assertThat(Texts.time(LocalDateTime.of(2026, 1, 1, 0, 7))).isEqualTo("12:07 AM");
        assertThat(Texts.time(LocalDateTime.of(2026, 1, 1, 17, 45))).isEqualTo("5:45 PM");
    }

    @Test
    void inWindow() {
        var morning = Fx.open("S1", f.chen, null, 11, 59);
        var noon = Fx.open("S2", f.chen, null, 12, 0);
        assertThat(Texts.inWindow(morning, null)).isTrue();
        assertThat(Texts.inWindow(noon, null)).isTrue();
        assertThat(Texts.inWindow(morning, PreferredWindow.ANY)).isTrue();
        assertThat(Texts.inWindow(noon, PreferredWindow.ANY)).isTrue();
        assertThat(Texts.inWindow(morning, PreferredWindow.MORNING)).isTrue();
        assertThat(Texts.inWindow(noon, PreferredWindow.MORNING)).isFalse();
        assertThat(Texts.inWindow(morning, PreferredWindow.AFTERNOON)).isFalse();
        assertThat(Texts.inWindow(noon, PreferredWindow.AFTERNOON)).isTrue();
    }

    @Test
    void specialtyNoun() {
        assertThat(Texts.specialtyNoun(Specialty.GENERAL)).isEqualTo("general practitioner");
        assertThat(Texts.specialtyNoun(Specialty.SPECIALIST)).isEqualTo("specialist");
        assertThat(Texts.specialtyNoun(Specialty.PEDIATRIC)).isEqualTo("children's doctor");
    }

    @Test
    void triggerOpeningPerTrigger() {
        assertThat(Texts.triggerOpening(TriggerReason.PROVIDER_OFFLINE, "Dr. Patel"))
                .isEqualTo("Dr. Patel is unavailable today, so we looked for another time for you.");
        assertThat(Texts.triggerOpening(TriggerReason.SLOT_CANCELLED, "Dr. Patel"))
                .startsWith("Your original appointment time had to be cancelled").doesNotContain("Dr. Patel");
        assertThat(Texts.triggerOpening(TriggerReason.INITIAL, "Dr. Patel"))
                .startsWith("You asked for a new appointment time").doesNotContain("Dr. Patel");
    }

    @Test
    void windowPhrase() {
        assertThat(Texts.windowPhrase(PreferredWindow.MORNING)).isEqualTo("mornings");
        assertThat(Texts.windowPhrase(PreferredWindow.AFTERNOON)).isEqualTo("afternoons");
        assertThat(Texts.windowPhrase(PreferredWindow.ANY)).isEqualTo("any time");
        assertThat(Texts.windowPhrase(null)).isEqualTo("any time");
    }
}
