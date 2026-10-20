package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.repository.*;
import com.slotmind.scheduling.Texts;
import com.slotmind.view.Views.WaitlistView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** Sprint 3 waitlist: auto-fill offers on SlotFreed, staff confirms (human checkpoint) -> transactional booking. */
@Service
@Transactional(readOnly = true)
public class WaitlistService {
    private final WaitlistRepository waitlist;
    private final SlotRepository slots;
    private final ClinicRepository clinics;
    private final AppointmentRepository appointments;
    private final AppointmentTypeRuleRepository typeRules;
    private final ApplicationEventPublisher events;
    private final ActivityService activity;

    public WaitlistService(WaitlistRepository waitlist, SlotRepository slots, ClinicRepository clinics,
                           AppointmentRepository appointments, AppointmentTypeRuleRepository typeRules,
                           ApplicationEventPublisher events, ActivityService activity) {
        this.waitlist = waitlist; this.slots = slots; this.clinics = clinics; this.appointments = appointments;
        this.typeRules = typeRules; this.events = events; this.activity = activity;
    }

    public record WaitlistRequest(String patientName, String contact, AppointmentType type, Specialty specialty,
                                  PreferredWindow preferredWindow, String clinicId) {}

    public List<WaitlistView> list() {
        return waitlist.findAllDetailed().stream().map(WaitlistView::of).toList();
    }

    @Transactional
    public WaitlistView add(WaitlistRequest req) {
        if (req == null || req.patientName() == null || req.patientName().isBlank() || req.type() == null || req.specialty() == null)
            throw new IllegalArgumentException("patientName, type and specialty are required");
        Clinic clinic = req.clinicId() == null || req.clinicId().isBlank() ? null
                : clinics.findById(req.clinicId()).orElseThrow(() -> new NotFoundException("Clinic", req.clinicId()));
        WaitlistEntry e = new WaitlistEntry(IdGen.next("WL", 100), req.patientName().trim(), req.contact(), req.type(),
                req.specialty(), req.preferredWindow(), clinic, LocalDateTime.now());
        waitlist.saveAndFlush(e);
        activity.log(ActivityKind.INFO, "POST /waitlist → " + e.getPatientName() + " added (" + e.getType() + ", " + e.getSpecialty() + ")");
        return WaitlistView.of(e);
    }

    /** Called after commit of a SlotFreed event: offer the slot to the best WAITING entry. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoFill(String slotId) {
        Slot slot = slots.findDetailed(slotId).orElse(null);
        if (slot == null || slot.getStatus() != SlotStatus.OPEN || !slot.getStartTime().isAfter(LocalDateTime.now())
                || !slot.getProvider().isOnline()) return;
        if (waitlist.existsByOfferedSlotIdAndStatus(slotId, WaitlistStatus.OFFERED)) return;
        WaitlistEntry best = waitlist.findByStatusOrderByCreatedAtAsc(WaitlistStatus.WAITING).stream()
                .filter(w -> fits(w, slot))
                .sorted(Comparator.comparingInt((WaitlistEntry w) -> w.getType() == AppointmentType.URGENT ? 0 : 1)
                        .thenComparing(WaitlistEntry::getCreatedAt))
                .findFirst().orElse(null);
        if (best == null) {
            activity.log(ActivityKind.INFO, "Waitlist: slot " + slotId + " opened — no matching waiting patient");
            return;
        }
        best.offer(slot);
        waitlist.flush();
        activity.log(ActivityKind.AGENT, "Waitlist: offered " + Texts.when(slot.getStartTime(), LocalDateTime.now()) + " with "
                + slot.getProvider().getName() + " to " + best.getPatientName() + " (" + best.getId() + ") — awaiting staff confirmation");
    }

    private boolean fits(WaitlistEntry w, Slot slot) {
        if (w.getSpecialty() != slot.getProvider().getSpecialty()) return false;
        if (w.getClinic() != null && slot.getProvider().getClinic() != null
                && !w.getClinic().getId().equals(slot.getProvider().getClinic().getId())) return false;
        if (!Texts.inWindow(slot, w.getPreferredWindow())) return false;
        AppointmentTypeRule rule = typeRules.findById(w.getType()).orElse(null);
        if (rule == null) return true;
        if (slot.getDurationMinutes() < rule.getDurationMinutes()) return false;
        return rule.getRequiredEquipment().isEmpty()
                || (slot.getRoom() != null && slot.getRoom().getEquipment().containsAll(rule.getRequiredEquipment()));
    }

    /** Staff confirms the offer: transactional booking with the same slot guards as accept. */
    @Transactional
    public WaitlistView accept(String id) {
        WaitlistEntry e = waitlist.findById(id).orElseThrow(() -> new NotFoundException("Waitlist entry", id));
        if (e.getStatus() != WaitlistStatus.OFFERED || e.getOfferedSlot() == null)
            throw new IllegalStateTransitionException("Waitlist entry " + id + " is " + e.getStatus() + ", no offer to accept");
        Slot slot = slots.findDetailed(e.getOfferedSlot().getId()).orElseThrow();
        if (slot.getStatus() != SlotStatus.OPEN || !slot.getProvider().isOnline())
            throw new SlotUnavailableException("Slot " + slot.getId() + " is no longer free");
        if (!slot.getStartTime().isAfter(LocalDateTime.now()))
            throw new SlotUnavailableException("Slot " + slot.getId() + " has already started — the offer expired");
        slot.book();
        Appointment a = new Appointment(IdGen.next("APT", 100), e.getPatientName(), e.getContact(), e.getType(),
                slot.getProvider(), slot, AppointmentStatus.CONFIRMED);
        appointments.save(a);
        e.booked(a.getId());
        appointments.flush();
        events.publishEvent(new DomainEvents.WaitlistBooked(e.getId(), a.getId()));
        activity.log(ActivityKind.OK, "POST /waitlist/" + id + "/accept → " + e.getPatientName() + " booked ("
                + a.getId() + ") at " + Texts.when(slot.getStartTime(), LocalDateTime.now()) + " with " + slot.getProvider().getName());
        return WaitlistView.of(e);
    }

    @Transactional
    public WaitlistView decline(String id) {
        WaitlistEntry e = waitlist.findById(id).orElseThrow(() -> new NotFoundException("Waitlist entry", id));
        String slotId = e.getOfferedSlot() == null ? null : e.getOfferedSlot().getId();
        e.decline();
        waitlist.flush();
        activity.log(ActivityKind.INFO, "POST /waitlist/" + id + "/decline → " + e.getPatientName() + " declined the offer");
        if (slotId != null) events.publishEvent(new DomainEvents.SlotFreed(slotId)); // offer to the next patient
        return WaitlistView.of(e);
    }
}
