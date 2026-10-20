package com.slotmind.ai;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.AppointmentType;
import com.slotmind.domain.PreferredWindow;
import com.slotmind.domain.Provider;
import com.slotmind.domain.Slot;
import com.slotmind.domain.TriggerReason;
import com.slotmind.scheduling.SchedulingContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/** Builds the system + user prompts. Two distinct user prompts: PROVIDER_OFFLINE vs SLOT_CANCELLED (INITIAL reuses the latter). */
@Component
public class PromptFactory {

    public record Prompts(String system, String user) {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);

    static final String SYSTEM_PROMPT = """
            You are a clinic scheduling assistant for CareBridge clinics. A patient's appointment must move to a new time.
            Rules:
            - Choose exactly ONE slot, and ONLY from the numbered candidate list in the request. Never invent a slot.
            - Output ONLY a single JSON object, no markdown, no code fences, no extra text:
              {"slotId":"<candidate slotId>","alternateProviderId":"<provider id>|null","confidence":<0.0-1.0>,"reasoning":"<text>"}
            - alternateProviderId = the chosen slot's provider id if it differs from the original provider, otherwise null.
            - confidence: how good a fit the slot is (1.0 = ideal, 0.5 = acceptable compromise).
            - reasoning: 2-3 short sentences, second person ("your appointment"), plain everyday language. Front-desk staff
              will READ IT ALOUD to the patient. Never mention slot ids, provider ids, room codes, "candidate", "load",
              "capacity" or any system jargon. Doctor names and times are fine. Refer to doctors by name; never guess
              their gender (no he/she).
              It is an OFFER the patient can still decline, not a done booking: phrase it as "we can offer you",
              "we'd like to move you to", "would ... work for you?" - never "your appointment is now" or "has been moved".
            - When choices are otherwise similar, avoid providers who are nearly fully booked today.
            Example output:
            {"slotId":"SLT-007","alternateProviderId":null,"confidence":0.86,"reasoning":"We can keep you with Dr. Patel, who already knows your history, at 2:30 PM today. That's the earliest afternoon opening, which matches when you like to come in. Would that time work for you?"}
            """;

    public Prompts build(SchedulingContext ctx) {
        return new Prompts(SYSTEM_PROMPT, userPrompt(ctx));
    }

    String userPrompt(SchedulingContext ctx) {
        Appointment a = ctx.appointment();
        Provider orig = a.getProvider();
        Slot origSlot = a.getSlot();
        LocalDateTime now = ctx.now() != null ? ctx.now() : LocalDateTime.now();
        TriggerReason trigger = ctx.trigger() == null ? TriggerReason.INITIAL : ctx.trigger();

        StringBuilder sb = new StringBuilder();
        sb.append("SITUATION\n");
        sb.append(switch (trigger) {
            case PROVIDER_OFFLINE -> """
                    The patient's booked doctor has become unavailable for today, so this appointment cannot happen as planned.
                    Find the best replacement. An alternate provider with the same specialty is expected and fine.
                    Weigh continuity of care (for a FOLLOW_UP, prefer a doctor of the same specialty, ideally the same clinic) and
                    urgency (for an URGENT visit the earliest suitable time matters most). Explain kindly that their doctor is unavailable.
                    """;
            case SLOT_CANCELLED -> """
                    The patient's booked time slot was cancelled by the clinic. Their doctor is still working.
                    Strongly prefer the SAME doctor's next suitable opening. Only switch to a different doctor if the same
                    doctor has no candidate slot, and then say so gently.
                    """;
            case INITIAL -> """
                    Staff requested a new time for this appointment (for example the patient asked to move it).
                    Strongly prefer the SAME doctor's next suitable opening. Only switch to a different doctor if the same
                    doctor has no candidate slot, and then say so gently.
                    """;
        });

        sb.append("\nAPPOINTMENT\n");
        sb.append("- Patient first name: ").append(firstName(a.getPatientName())).append('\n');
        sb.append("- Visit type: ").append(a.getType()).append(" (").append(typeHint(a.getType())).append(")\n");
        sb.append("- Urgency: ").append(a.getType() == AppointmentType.URGENT ? "HIGH - see as soon as possible" : "normal").append('\n');
        if (origSlot != null && origSlot.getStartTime() != null) {
            sb.append("- Original time: ").append(human(origSlot.getStartTime(), now)).append('\n');
        }
        if (orig != null) {
            sb.append("- Original provider: ").append(orig.getName()).append(" (").append(orig.getId()).append("), ")
                    .append(orig.getSpecialty()).append(", status ").append(orig.getStatus()).append('\n');
            if (orig.getClinic() != null) sb.append("- Patient's clinic: ").append(orig.getClinic().getName()).append('\n');
        }
        PreferredWindow pw = ctx.preferredWindow() == null ? PreferredWindow.ANY : ctx.preferredWindow();
        sb.append("- Patient preferred time of day: ").append(switch (pw) {
            case MORNING -> "MORNING (before noon) - prefer it when other factors are similar";
            case AFTERNOON -> "AFTERNOON (noon or later) - prefer it when other factors are similar";
            case ANY -> "no preference";
        }).append('\n');
        if (ctx.crossClinic()) {
            sb.append("- NOTE: no suitable time exists at the patient's own clinic, so these options are at another clinic. ")
                    .append("Mention the clinic name in the reasoning so the patient knows where to go.\n");
        }

        List<Slot> ordered = order(ctx);
        sb.append("\nCANDIDATE SLOTS (already checked: open, legal, room and duration fit)\n");
        int i = 1;
        for (Slot s : ordered) {
            Provider p = s.getProvider();
            sb.append(i++).append(". slotId=").append(s.getId())
                    .append(" | ").append(human(s.getStartTime(), now))
                    .append(" | ").append(s.getDurationMinutes()).append(" min");
            if (p != null) {
                sb.append(" | ").append(p.getName()).append(" (").append(p.getId()).append(")")
                        .append(", ").append(p.getSpecialty());
                if (orig != null && Objects.equals(p.getId(), orig.getId())) sb.append(", SAME DOCTOR");
            }
            sb.append(" | room ").append(s.getRoom() != null ? s.getRoom().getName() : "n/a");
            String clinic = clinicName(s);
            if (clinic != null) sb.append(" | clinic ").append(clinic);
            if (p != null) sb.append(" | ").append(load(ctx, p)).append('\n');
            else sb.append('\n');
        }

        sb.append("\nReply with ONLY the JSON object.\n\n");
        // machine-readable footer (used by MockLlmGateway; harmless for real models)
        if (orig != null) sb.append("ORIGINAL_PROVIDER_ID: ").append(orig.getId()).append('\n');
        for (Slot s : ordered) {
            if (s.getProvider() != null) sb.append("SLOT_PROVIDER: ").append(s.getId()).append('=').append(s.getProvider().getId()).append('\n');
        }
        sb.append("CANDIDATE_IDS: ").append(ordered.stream().map(Slot::getId).collect(Collectors.joining(","))).append('\n');
        return sb.toString();
    }

    /** Sensible presentation order (also the mock's pick order): same doctor first unless offline, preferred window, earliest. */
    List<Slot> order(SchedulingContext ctx) {
        Provider orig = ctx.appointment().getProvider();
        String origId = orig == null ? null : orig.getId();
        boolean preferSame = ctx.trigger() != TriggerReason.PROVIDER_OFFLINE;
        PreferredWindow pw = ctx.preferredWindow() == null ? PreferredWindow.ANY : ctx.preferredWindow();
        Comparator<Slot> cmp = Comparator.comparingInt(s -> 0);
        if (preferSame) {
            cmp = cmp.thenComparingInt(s -> s.getProvider() != null && Objects.equals(s.getProvider().getId(), origId) ? 0 : 1);
        } else if (orig != null) {
            cmp = cmp.thenComparingInt(s -> s.getProvider() != null && s.getProvider().getSpecialty() == orig.getSpecialty() ? 0 : 1);
        }
        cmp = cmp.thenComparingInt(s -> windowMatch(s, pw) ? 0 : 1)
                .thenComparing(Slot::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()));
        List<Slot> list = new ArrayList<>(ctx.candidates());
        list.sort(cmp);
        return list;
    }

    private static boolean windowMatch(Slot s, PreferredWindow pw) {
        if (pw == PreferredWindow.ANY || s.getStartTime() == null) return true;
        boolean morning = s.getStartTime().getHour() < 12;
        return pw == PreferredWindow.MORNING == morning;
    }

    private static String load(SchedulingContext ctx, Provider p) {
        Integer booked = ctx.providerLoad() == null ? null : ctx.providerLoad().get(p.getId());
        int max = p.getMaxDailyAppointments();
        if (booked == null) return "load unknown";
        String s = booked + "/" + max + " booked today";
        if (max > 0 && booked >= max - 1) s += " (nearly full)";
        return s;
    }

    private static String clinicName(Slot s) {
        if (s.getRoom() != null && s.getRoom().getClinic() != null) return s.getRoom().getClinic().getName();
        if (s.getProvider() != null && s.getProvider().getClinic() != null) return s.getProvider().getClinic().getName();
        return null;
    }

    static String human(LocalDateTime t, LocalDateTime now) {
        if (t == null) return "unknown time";
        String day;
        if (t.toLocalDate().equals(now.toLocalDate())) day = "today";
        else if (t.toLocalDate().equals(now.toLocalDate().plusDays(1))) day = "tomorrow";
        else day = DAY.format(t);
        return TIME.format(t) + " " + day;
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) return "the patient";
        return name.trim().split("\\s+")[0];
    }

    private static String typeHint(AppointmentType t) {
        if (t == null) return "";
        return switch (t) {
            case ROUTINE -> "regular check-up";
            case URGENT -> "urgent problem";
            case FOLLOW_UP -> "follow-up on an earlier visit, continuity matters";
        };
    }
}
