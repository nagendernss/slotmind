package com.slotmind.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "waitlist_entry")
public class WaitlistEntry {
    @Id
    private String id;
    private String patientName;
    private String contact;
    @Enumerated(EnumType.STRING)
    private AppointmentType type;
    @Enumerated(EnumType.STRING)
    private Specialty specialty;
    @Enumerated(EnumType.STRING)
    private PreferredWindow preferredWindow;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id")
    private Clinic clinic;
    @Enumerated(EnumType.STRING)
    private WaitlistStatus status;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offered_slot_id")
    private Slot offeredSlot;
    private String bookedAppointmentId;
    private LocalDateTime createdAt;
    @Version
    private Long version;

    protected WaitlistEntry() {}
    public WaitlistEntry(String id, String patientName, String contact, AppointmentType type, Specialty specialty,
                         PreferredWindow preferredWindow, Clinic clinic, LocalDateTime createdAt) {
        this.id = id; this.patientName = patientName; this.contact = contact; this.type = type; this.specialty = specialty;
        this.preferredWindow = preferredWindow == null ? PreferredWindow.ANY : preferredWindow; this.clinic = clinic;
        this.status = WaitlistStatus.WAITING; this.createdAt = createdAt;
    }

    /** WAITING -> OFFERED */
    public void offer(Slot slot) {
        if (status != WaitlistStatus.WAITING) throw new IllegalStateTransitionException("Waitlist entry " + id + " is " + status);
        status = WaitlistStatus.OFFERED; offeredSlot = slot;
    }
    /** OFFERED -> BOOKED */
    public void booked(String appointmentId) {
        if (status != WaitlistStatus.OFFERED) throw new IllegalStateTransitionException("Waitlist entry " + id + " is " + status + ", no offer to accept");
        status = WaitlistStatus.BOOKED; bookedAppointmentId = appointmentId;
    }
    /** OFFERED -> DECLINED */
    public void decline() {
        if (status != WaitlistStatus.OFFERED) throw new IllegalStateTransitionException("Waitlist entry " + id + " is " + status + ", no offer to decline");
        status = WaitlistStatus.DECLINED;
    }

    public String getId() { return id; }
    public String getPatientName() { return patientName; }
    public String getContact() { return contact; }
    public AppointmentType getType() { return type; }
    public Specialty getSpecialty() { return specialty; }
    public PreferredWindow getPreferredWindow() { return preferredWindow; }
    public Clinic getClinic() { return clinic; }
    public WaitlistStatus getStatus() { return status; }
    public Slot getOfferedSlot() { return offeredSlot; }
    public String getBookedAppointmentId() { return bookedAppointmentId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
