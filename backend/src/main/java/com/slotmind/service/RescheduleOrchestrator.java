package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.SuggestionRepository;
import com.slotmind.scheduling.ScheduleEngine;
import com.slotmind.scheduling.ScheduleEngine.EngineResult;
import com.slotmind.scheduling.Texts;
import com.slotmind.view.Views.SuggestionView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared processing for every trigger (agentic loop and manual requests): one appointment, one transaction.
 * Only proposes — the booking changes only when staff/patient accept (human checkpoint).
 */
@Service
public class RescheduleOrchestrator {
    private final AppointmentRepository appointments;
    private final SuggestionRepository suggestions;
    private final ScheduleEngine engine;
    private final ActivityService activity;

    public RescheduleOrchestrator(AppointmentRepository appointments, SuggestionRepository suggestions,
                                  ScheduleEngine engine, ActivityService activity) {
        this.appointments = appointments; this.suggestions = suggestions; this.engine = engine; this.activity = activity;
    }

    /** Agentic loop entry: own transaction per appointment so one failure doesn't block the others. Idempotent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> process(String appointmentId, TriggerReason trigger) {
        Appointment appt = appointments.findDetailed(appointmentId).orElseThrow(() -> new NotFoundException("Appointment", appointmentId));
        if (appt.getStatus() == AppointmentStatus.COMPLETED) return Optional.empty();
        if (suggestions.existsByPendingKey(RescheduleSuggestion.key(appointmentId, trigger))) {
            activity.log(ActivityKind.INFO, "Skipped " + appt.getPatientName() + " (" + appointmentId + "): a pending "
                    + label(trigger) + " suggestion already exists (idempotent)");
            return Optional.empty();
        }
        return Optional.of(create(appt, trigger, null).id());
    }

    /** Manual / portal request (INITIAL): always produces a fresh suggestion, superseding older pending ones. */
    @Transactional
    public SuggestionView requestNew(String appointmentId, String strategyOverride, String actor) {
        Appointment appt = appointments.findDetailed(appointmentId).orElseThrow(() -> new NotFoundException("Appointment", appointmentId));
        if (appt.getStatus() == AppointmentStatus.COMPLETED)
            throw new IllegalStateTransitionException("Appointment " + appointmentId + " is COMPLETED, cannot be rescheduled");
        activity.log(ActivityKind.INFO, "POST /appointments/" + appointmentId + "/suggest → new time requested by " + actor
                + " for " + appt.getPatientName());
        return create(appt, TriggerReason.INITIAL, strategyOverride);
    }

    private SuggestionView create(Appointment appt, TriggerReason trigger, String strategyOverride) {
        appt.markReschedulePending();

        // Supersede older pending suggestions first and flush, so the unique pendingKey never collides.
        List<RescheduleSuggestion> pending = suggestions.findByAppointmentIdAndStatus(appt.getId(), SuggestionStatus.PENDING);
        pending.forEach(RescheduleSuggestion::supersede);
        suggestions.saveAllAndFlush(pending);

        Set<String> exclude = new HashSet<>(suggestions.findRejectedSlotIds(appt.getId()));
        EngineResult r = engine.recommend(appt, trigger, exclude, strategyOverride);

        RescheduleSuggestion s = new RescheduleSuggestion(appt, r.slot(), r.alternateProviderId(), r.confidence(),
                r.reasoning(), trigger, r.strategy(), r.fallbackUsed(), r.crossClinic());
        suggestions.saveAndFlush(s);

        if (r.slot() == null) {
            activity.log(ActivityKind.WARN, "No legal slot for " + appt.getPatientName() + " (" + appt.getId()
                    + ") — stored a suggestion without a slot; offer the waitlist");
        } else {
            activity.log(ActivityKind.AGENT, "Suggested " + Texts.when(r.slot().getStartTime(), LocalDateTime.now()) + " with "
                    + r.slot().getProvider().getName() + " for " + appt.getPatientName() + " (" + appt.getId() + ", "
                    + label(trigger) + ") via " + r.strategy() + (r.fallbackUsed() ? " [fallback]" : "")
                    + (r.crossClinic() ? " [other clinic]" : "")
                    + String.format(", confidence %.2f", r.confidence()));
        }
        return SuggestionView.of(s);
    }

    static String label(TriggerReason t) {
        return switch (t) {
            case PROVIDER_OFFLINE -> "provider offline";
            case SLOT_CANCELLED -> "slot cancelled";
            case INITIAL -> "manual";
        };
    }
}
