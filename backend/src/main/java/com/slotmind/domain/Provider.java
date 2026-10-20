package com.slotmind.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "provider")
public class Provider {
    @Id
    private String id;
    private String name;
    @Enumerated(EnumType.STRING)
    private Specialty specialty;
    @Enumerated(EnumType.STRING)
    private ProviderStatus status;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id")
    private Clinic clinic;
    private int maxDailyAppointments;

    protected Provider() {}
    public Provider(String id, String name, Specialty specialty, ProviderStatus status, Clinic clinic, int maxDailyAppointments) {
        this.id = id; this.name = name; this.specialty = specialty; this.status = status;
        this.clinic = clinic; this.maxDailyAppointments = maxDailyAppointments;
    }

    /** AVAILABLE <-> BUSY <-> OFFLINE (all moves allowed). Returns true if the provider just went OFFLINE. */
    public boolean changeStatus(ProviderStatus next) {
        if (next == null) throw new IllegalArgumentException("status is required");
        boolean wentOffline = next == ProviderStatus.OFFLINE && status != ProviderStatus.OFFLINE;
        this.status = next;
        return wentOffline;
    }

    public boolean isOnline() { return status != ProviderStatus.OFFLINE; }

    public String getId() { return id; }
    public String getName() { return name; }
    public Specialty getSpecialty() { return specialty; }
    public ProviderStatus getStatus() { return status; }
    public Clinic getClinic() { return clinic; }
    public int getMaxDailyAppointments() { return maxDailyAppointments; }
}
