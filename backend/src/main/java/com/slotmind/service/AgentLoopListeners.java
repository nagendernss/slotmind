package com.slotmind.service;

import com.slotmind.domain.ActivityKind;
import com.slotmind.domain.AppointmentStatus;
import com.slotmind.domain.TriggerReason;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.service.DomainEvents.ProviderWentOffline;
import com.slotmind.service.DomainEvents.SlotCancelled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.List;

/**
 * Agentic loop (decision.md §7): two small async AFTER_COMMIT handlers whose only job is finding the affected
 * appointments; the shared RescheduleOrchestrator does the work, one REQUIRES_NEW transaction per appointment.
 */
@Component
public class AgentLoopListeners {
    private static final Logger log = LoggerFactory.getLogger(AgentLoopListeners.class);

    private final AppointmentRepository appointments;
    private final RescheduleOrchestrator orchestrator;
    private final ActivityService activity;

    public AgentLoopListeners(AppointmentRepository appointments, RescheduleOrchestrator orchestrator, ActivityService activity) {
        this.appointments = appointments; this.orchestrator = orchestrator; this.activity = activity;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProviderOffline(ProviderWentOffline e) {
        List<String> ids = appointments.findIdsByProviderIdAndStatusIn(e.providerId(),
                EnumSet.of(AppointmentStatus.CONFIRMED, AppointmentStatus.RESCHEDULED, AppointmentStatus.RESCHEDULE_PENDING));
        activity.log(ActivityKind.AGENT, "Agent: " + e.providerName() + " went offline → " + ids.size()
                + " affected appointment(s) found" + (ids.isEmpty() ? "" : ": " + String.join(", ", ids)));
        runAll(ids, TriggerReason.PROVIDER_OFFLINE);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSlotCancelled(SlotCancelled e) {
        appointments.findBySlotId(e.slotId())
                .filter(a -> a.getStatus() != AppointmentStatus.COMPLETED)
                .ifPresentOrElse(a -> {
                    activity.log(ActivityKind.AGENT, "Agent: slot " + e.slotId() + " was cancelled → appointment " + a.getId()
                            + " needs a new time");
                    runAll(List.of(a.getId()), TriggerReason.SLOT_CANCELLED);
                }, () -> activity.log(ActivityKind.INFO, "Agent: slot " + e.slotId() + " cancelled — no booked appointment affected"));
    }

    private void runAll(List<String> ids, TriggerReason trigger) {
        int ok = 0;
        for (String id : ids) {
            try {
                if (orchestrator.process(id, trigger).isPresent()) ok++;
            } catch (Exception ex) {
                log.error("Reschedule of {} failed", id, ex);
                activity.log(ActivityKind.ERROR, "Agent: could not process " + id + ": " + ex.getMessage());
            }
        }
        if (!ids.isEmpty()) {
            activity.log(ActivityKind.OK, "Agent: " + ok + " new suggestion(s) waiting for front-desk review");
        }
    }
}
