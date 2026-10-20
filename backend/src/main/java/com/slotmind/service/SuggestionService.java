package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.repository.SlotRepository;
import com.slotmind.repository.SuggestionRepository;
import com.slotmind.scheduling.ScheduleEngine;
import com.slotmind.scheduling.Texts;
import com.slotmind.view.Views.SuggestionView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class SuggestionService {
    private final SuggestionRepository suggestions;
    private final SlotRepository slots;
    private final ScheduleEngine engine;
    private final ApplicationEventPublisher events;
    private final ActivityService activity;

    public SuggestionService(SuggestionRepository suggestions, SlotRepository slots, ScheduleEngine engine,
                             ApplicationEventPublisher events, ActivityService activity) {
        this.suggestions = suggestions; this.slots = slots; this.engine = engine; this.events = events; this.activity = activity;
    }

    public List<SuggestionView> list(SuggestionStatus status) {
        return suggestions.search(status).stream().map(SuggestionView::of).toList();
    }

    @Transactional
    public SuggestionView resolve(String id, SuggestionStatus status, String actor) {
        String who = actor == null || actor.isBlank() ? "STAFF" : actor.trim().toUpperCase();
        if (status == SuggestionStatus.ACCEPTED) return accept(id, who);
        if (status == SuggestionStatus.REJECTED) return reject(id, who);
        throw new IllegalArgumentException("status must be ACCEPTED or REJECTED");
    }

    /**
     * decision.md §6 — one transaction: guards, free old slot, book new slot, move appointment, accept, supersede siblings.
     * A concurrent accept of the same slot fails on @Version at flush -> 409 SLOT_UNAVAILABLE, everything rolled back.
     */
    private SuggestionView accept(String id, String actor) {
        RescheduleSuggestion g = suggestions.findDetailed(id).orElseThrow(() -> new NotFoundException("Suggestion", id));
        if (g.getStatus() != SuggestionStatus.PENDING)
            throw new IllegalStateTransitionException("Suggestion " + id + " is " + g.getStatus() + ", not PENDING");
        Appointment appt = g.getAppointment();
        if (appt.getStatus() != AppointmentStatus.RESCHEDULE_PENDING)
            throw new IllegalStateTransitionException("Appointment " + appt.getId() + " is " + appt.getStatus() + ", not RESCHEDULE_PENDING");
        Slot newSlot = g.getRecommendedSlot();
        if (newSlot == null) throw new IllegalStateTransitionException("Suggestion " + id + " has no slot — use the waitlist");
        if (newSlot.getStatus() != SlotStatus.OPEN) {
            activity.log(ActivityKind.WARN, "PATCH /suggestions/" + id + " ACCEPTED → 409: slot " + newSlot.getId() + " is " + newSlot.getStatus());
            throw new SlotUnavailableException("Slot " + newSlot.getId() + " is no longer free — get a new suggestion");
        }
        String violated = engine.violatedConstraint(appt, newSlot);
        if (violated != null) {
            activity.log(ActivityKind.WARN, "PATCH /suggestions/" + id + " ACCEPTED → 409: slot " + newSlot.getId() + " fails " + violated);
            throw new SlotUnavailableException("Slot " + newSlot.getId() + " is no longer suitable (" + violated + ") — get a new suggestion");
        }

        Slot oldSlot = appt.getSlot();
        boolean freed = false;
        if (oldSlot.getStatus() == SlotStatus.BOOKED) { oldSlot.free(); freed = true; }
        newSlot.book();
        appt.moveTo(newSlot);
        g.accept(actor);
        suggestions.findByAppointmentIdAndStatus(appt.getId(), SuggestionStatus.PENDING).stream()
                .filter(o -> !o.getId().equals(g.getId())).forEach(RescheduleSuggestion::supersede);
        suggestions.flush(); // surfaces optimistic-lock / unique violations inside this method

        if (freed) events.publishEvent(new DomainEvents.SlotFreed(oldSlot.getId()));
        events.publishEvent(new DomainEvents.SuggestionAccepted(g.getId(), appt.getId(), actor));
        activity.log(ActivityKind.OK, "PATCH /suggestions/" + id + " → ACCEPTED by " + actor + ": " + appt.getPatientName()
                + " moved to " + Texts.when(newSlot.getStartTime(), LocalDateTime.now()) + " with " + newSlot.getProvider().getName());
        return SuggestionView.of(g);
    }

    private SuggestionView reject(String id, String actor) {
        RescheduleSuggestion g = suggestions.findDetailed(id).orElseThrow(() -> new NotFoundException("Suggestion", id));
        g.reject(actor);
        suggestions.flush();
        activity.log(ActivityKind.INFO, "PATCH /suggestions/" + id + " → REJECTED by " + actor + " (" + g.getAppointment().getPatientName()
                + " stays pending; request a new suggestion)");
        return SuggestionView.of(g);
    }
}
