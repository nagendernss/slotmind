package com.slotmind.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "patient_preference")
public class PatientPreference {
    @Id
    private String patientName;
    @Enumerated(EnumType.STRING)
    private PreferredWindow preferredWindow;
    @Enumerated(EnumType.STRING)
    private PreferenceSource source;
    private LocalDateTime updatedAt;

    protected PatientPreference() {}
    public PatientPreference(String patientName, PreferredWindow preferredWindow, PreferenceSource source) {
        this.patientName = patientName; this.preferredWindow = preferredWindow; this.source = source;
        this.updatedAt = LocalDateTime.now();
    }

    public void update(PreferredWindow window) {
        this.preferredWindow = window; this.source = PreferenceSource.EXPLICIT; this.updatedAt = LocalDateTime.now();
    }

    public String getPatientName() { return patientName; }
    public PreferredWindow getPreferredWindow() { return preferredWindow; }
    public PreferenceSource getSource() { return source; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
