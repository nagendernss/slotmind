package com.slotmind.scheduling;

import com.slotmind.domain.PreferredWindow;
import com.slotmind.domain.Slot;
import com.slotmind.domain.Specialty;
import com.slotmind.domain.TriggerReason;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small helpers for patient-friendly template text (no ids, plain language). */
public final class Texts {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH);

    private Texts() {}

    public static String when(LocalDateTime t, LocalDateTime now) {
        LocalDate d = t.toLocalDate();
        LocalDate today = now.toLocalDate();
        String day = d.equals(today) ? "today" : d.equals(today.plusDays(1)) ? "tomorrow" : "on " + DAY.format(t);
        return TIME.format(t) + " " + day;
    }

    public static String time(LocalDateTime t) { return TIME.format(t); }

    public static boolean inWindow(Slot s, PreferredWindow w) {
        if (w == null || w == PreferredWindow.ANY) return true;
        boolean morning = s.getStartTime().getHour() < 12;
        return w == PreferredWindow.MORNING ? morning : !morning;
    }

    public static String specialtyNoun(Specialty s) {
        return switch (s) {
            case GENERAL -> "general practitioner";
            case SPECIALIST -> "specialist";
            case PEDIATRIC -> "children's doctor";
        };
    }

    public static String triggerOpening(TriggerReason trigger, String originalDoctor) {
        return switch (trigger) {
            case PROVIDER_OFFLINE -> originalDoctor + " is unavailable today, so we looked for another time for you.";
            case SLOT_CANCELLED -> "Your original appointment time had to be cancelled, so we looked for a new one.";
            case INITIAL -> "You asked for a new appointment time, so we looked at what is open.";
        };
    }

    public static String windowPhrase(PreferredWindow w) {
        return w == PreferredWindow.MORNING ? "mornings" : w == PreferredWindow.AFTERNOON ? "afternoons" : "any time";
    }
}
