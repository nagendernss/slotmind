package com.slotmind.scheduling;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.Slot;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic ranking: same provider nearest -> same specialty nearest -> anyone nearest.
 * Within a tier (nearest measured in 1-hour buckets from the original time), tie-break by the patient's
 * preferred window, then by lower provider load (queue pressure), then exact distance.
 */
@Component
public class RuleBasedStrategy implements SchedulingStrategy {

    @Override
    public String key() { return StrategyRegistry.RULE_BASED; }

    @Override
    public List<SlotRecommendation> recommend(SchedulingContext ctx) {
        Appointment a = ctx.appointment();
        return ctx.candidates().stream()
                .sorted(Comparator.<Slot>comparingInt(s -> tier(a, s))
                        .thenComparingLong(s -> distance(a, s) / 60)
                        .thenComparingInt(s -> Texts.inWindow(s, ctx.preferredWindow()) ? 0 : 1)
                        .thenComparingInt(s -> ctx.providerLoad().getOrDefault(s.getProvider().getId(), 0))
                        .thenComparingLong(s -> distance(a, s))
                        .thenComparing(Slot::getId))
                .map(s -> toRecommendation(ctx, s))
                .toList();
    }

    static int tier(Appointment a, Slot s) {
        if (s.getProvider().getId().equals(a.getProvider().getId())) return 0;
        if (s.getProvider().getSpecialty() == a.getProvider().getSpecialty()) return 1;
        return 2;
    }

    static long distance(Appointment a, Slot s) {
        return Math.abs(Duration.between(a.getSlot().getStartTime(), s.getStartTime()).toMinutes());
    }

    private SlotRecommendation toRecommendation(SchedulingContext ctx, Slot s) {
        Appointment a = ctx.appointment();
        int tier = tier(a, s);
        boolean windowOk = Texts.inWindow(s, ctx.preferredWindow());
        double confidence = (tier == 0 ? 0.9 : tier == 1 ? 0.78 : 0.55) - (windowOk ? 0 : 0.08) - (ctx.crossClinic() ? 0.05 : 0);

        StringBuilder r = new StringBuilder(Texts.triggerOpening(ctx.trigger(), a.getProvider().getName())).append(' ');
        if (tier == 0) {
            r.append("The closest opening with ").append(s.getProvider().getName()).append(" is at ")
                    .append(Texts.when(s.getStartTime(), ctx.now())).append(", so you keep the same doctor.");
        } else {
            r.append("The closest suitable time is ").append(Texts.when(s.getStartTime(), ctx.now())).append(" with ")
                    .append(s.getProvider().getName()).append(", ")
                    .append(tier == 1 ? "another " + Texts.specialtyNoun(s.getProvider().getSpecialty()) : "who can see you")
                    .append('.');
        }
        if (ctx.crossClinic() && s.getProvider().getClinic() != null) {
            r.append(" This visit would be at our ").append(s.getProvider().getClinic().getName())
                    .append(" clinic because your usual clinic has no suitable openings.");
        }
        if (ctx.preferredWindow() != null && ctx.preferredWindow() != com.slotmind.domain.PreferredWindow.ANY) {
            r.append(windowOk ? " It also matches your preference for " : " It is outside your usual preference for ")
                    .append(Texts.windowPhrase(ctx.preferredWindow())).append('.');
        }
        String alt = s.getProvider().getId().equals(a.getProvider().getId()) ? null : s.getProvider().getId();
        return new SlotRecommendation(s.getId(), alt, Math.max(0.1, confidence), r.toString());
    }
}
