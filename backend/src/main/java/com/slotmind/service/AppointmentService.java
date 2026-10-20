package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.AppointmentTypeRuleRepository;
import com.slotmind.repository.ProviderRepository;
import com.slotmind.repository.SlotRepository;
import com.slotmind.view.Views.AppointmentView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class AppointmentService {
    private final AppointmentRepository appointments;
    private final SlotRepository slots;
    private final ProviderRepository providers;
    private final AppointmentTypeRuleRepository typeRules;
    private final ActivityService activity;

    public AppointmentService(AppointmentRepository appointments, SlotRepository slots, ProviderRepository providers,
                              AppointmentTypeRuleRepository typeRules, ActivityService activity) {
        this.appointments = appointments; this.slots = slots; this.providers = providers;
        this.typeRules = typeRules; this.activity = activity;
    }

    public record BookRequest(String patientName, AppointmentType type, String providerId, String slotId, String contact) {}

    /** Books a slot in one transaction: entity guard + @Version + unique(slot_id) prevent double booking. */
    @Transactional
    public AppointmentView book(BookRequest req) {
        if (req == null || req.patientName() == null || req.patientName().isBlank() || req.type() == null
                || req.providerId() == null || req.slotId() == null) {
            throw new IllegalArgumentException("patientName, type, providerId and slotId are required");
        }
        Provider provider = providers.findById(req.providerId()).orElseThrow(() -> new NotFoundException("Provider", req.providerId()));
        Slot slot = slots.findDetailed(req.slotId()).orElseThrow(() -> new NotFoundException("Slot", req.slotId()));
        if (!slot.getProvider().getId().equals(provider.getId()))
            throw new IllegalArgumentException("Slot " + slot.getId() + " belongs to " + slot.getProvider().getName() + ", not " + provider.getName());
        if (slot.getStatus() != SlotStatus.OPEN)
            throw new SlotUnavailableException("Slot " + slot.getId() + " is " + slot.getStatus() + ", not OPEN");
        if (!provider.isOnline())
            throw new SlotUnavailableException(provider.getName() + " is offline");
        AppointmentTypeRule rule = typeRules.findById(req.type()).orElse(null);
        if (rule != null) {
            if (slot.getDurationMinutes() < rule.getDurationMinutes())
                throw new IllegalArgumentException(req.type() + " needs " + rule.getDurationMinutes() + " minutes; slot has " + slot.getDurationMinutes());
            if (!rule.getRequiredEquipment().isEmpty()
                    && (slot.getRoom() == null || !slot.getRoom().getEquipment().containsAll(rule.getRequiredEquipment())))
                throw new IllegalArgumentException(req.type() + " needs a room with " + rule.getRequiredEquipment());
        }
        slot.book();
        Appointment a = new Appointment(IdGen.next("APT", 100), req.patientName().trim(),
                req.contact() == null || req.contact().isBlank() ? null : req.contact().trim(), req.type(), provider, slot,
                AppointmentStatus.CONFIRMED);
        appointments.saveAndFlush(a);
        activity.log(ActivityKind.OK, "POST /appointments → booked " + a.getPatientName() + " (" + a.getId() + ", " + a.getType()
                + ") with " + provider.getName() + " in " + slot.getId());
        return AppointmentView.of(a);
    }

    public List<AppointmentView> list(AppointmentStatus status) {
        return appointments.search(status).stream().map(AppointmentView::of).toList();
    }

    public AppointmentView get(String id) {
        return AppointmentView.of(appointments.findDetailed(id).orElseThrow(() -> new NotFoundException("Appointment", id)));
    }

    @Transactional
    public AppointmentView complete(String id) {
        Appointment a = appointments.findDetailed(id).orElseThrow(() -> new NotFoundException("Appointment", id));
        a.complete();
        appointments.flush();
        activity.log(ActivityKind.INFO, "PATCH /appointments/" + id + "/complete → " + a.getPatientName() + " completed");
        return AppointmentView.of(a);
    }
}
