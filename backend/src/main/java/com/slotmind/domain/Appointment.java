package com.slotmind.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "appointment",
        uniqueConstraints = @UniqueConstraint(name = "uk_appointment_slot", columnNames = "slot_id"))
public class Appointment {
    @Id
    private String id;
    private String patientName;
    private String contact;
    @Enumerated(EnumType.STRING)
    private AppointmentType type;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id")
    private Provider provider;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id")
    private Slot slot;
    @Enumerated(EnumType.STRING)
    private AppointmentStatus status;
    private LocalDateTime createdAt;
    @Version
    private Long version;

    protected Appointment() {}
    public Appointment(String id, String patientName, String contact, AppointmentType type, Provider provider, Slot slot,
                       AppointmentStatus status) {
        this.id = id; this.patientName = patientName; this.contact = contact; this.type = type;
        this.provider = provider; this.slot = slot; this.status = status; this.createdAt = LocalDateTime.now();
    }

    /** CONFIRMED|RESCHEDULED -> RESCHEDULE_PENDING (already pending = no-op). */
    public void markReschedulePending() {
        switch (status) {
            case CONFIRMED, RESCHEDULED -> status = AppointmentStatus.RESCHEDULE_PENDING;
            case RESCHEDULE_PENDING -> { }
            default -> throw new IllegalStateTransitionException("Appointment " + id + " is " + status + ", cannot be rescheduled");
        }
    }

    /** RESCHEDULE_PENDING -> RESCHEDULED onto the new slot. */
    public void moveTo(Slot newSlot) {
        if (status != AppointmentStatus.RESCHEDULE_PENDING)
            throw new IllegalStateTransitionException("Appointment " + id + " is " + status + ", not awaiting a reschedule");
        this.slot = newSlot;
        this.provider = newSlot.getProvider();
        this.status = AppointmentStatus.RESCHEDULED;
    }

    /** CONFIRMED|RESCHEDULED -> COMPLETED. */
    public void complete() {
        if (status != AppointmentStatus.CONFIRMED && status != AppointmentStatus.RESCHEDULED)
            throw new IllegalStateTransitionException("Appointment " + id + " is " + status + ", cannot complete");
        status = AppointmentStatus.COMPLETED;
    }

    public String getId() { return id; }
    public String getPatientName() { return patientName; }
    public String getContact() { return contact; }
    public AppointmentType getType() { return type; }
    public Provider getProvider() { return provider; }
    public Slot getSlot() { return slot; }
    public AppointmentStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
