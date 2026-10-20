package com.slotmind.service;

import com.slotmind.domain.ActivityKind;
import com.slotmind.domain.IllegalStateTransitionException;
import com.slotmind.domain.Slot;
import com.slotmind.domain.SlotStatus;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.SlotRepository;
import com.slotmind.view.Views.SlotView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class SlotService {
    private final SlotRepository slots;
    private final AppointmentRepository appointments;
    private final ApplicationEventPublisher events;
    private final ActivityService activity;

    public SlotService(SlotRepository slots, AppointmentRepository appointments, ApplicationEventPublisher events,
                       ActivityService activity) {
        this.slots = slots; this.appointments = appointments; this.events = events; this.activity = activity;
    }

    public List<SlotView> list(String providerId, SlotStatus status) {
        return slots.search(providerId, status).stream().map(SlotView::of).toList();
    }

    /** CANCELLED fires the agentic loop (after commit); OPEN frees an unreferenced slot and fires waitlist auto-fill. */
    @Transactional
    public SlotView changeStatus(String id, SlotStatus status) {
        if (status == null) throw new IllegalArgumentException("status is required (OPEN|CANCELLED)");
        Slot slot = slots.findDetailed(id).orElseThrow(() -> new NotFoundException("Slot", id));
        switch (status) {
            case CANCELLED -> {
                slot.cancel();
                slots.flush();
                activity.log(ActivityKind.INFO, "PATCH /slots/" + id + "/status → CANCELLED (" + slot.getProvider().getName() + ")");
                events.publishEvent(new DomainEvents.SlotCancelled(id));
            }
            case OPEN -> {
                if (slot.getStatus() == SlotStatus.OPEN) return SlotView.of(slot);
                appointments.findBySlotId(id).ifPresent(a -> {
                    throw new IllegalStateTransitionException("Slot " + id + " is held by appointment " + a.getId()
                            + "; cancel the slot or reschedule the appointment instead");
                });
                slot.free();
                slots.flush();
                activity.log(ActivityKind.INFO, "PATCH /slots/" + id + "/status → OPEN (" + slot.getProvider().getName() + ")");
                events.publishEvent(new DomainEvents.SlotFreed(id));
            }
            case BOOKED -> throw new IllegalArgumentException("Book a slot via POST /appointments");
        }
        return SlotView.of(slot);
    }
}
