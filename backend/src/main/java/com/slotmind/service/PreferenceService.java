package com.slotmind.service;

import com.slotmind.domain.AppointmentStatus;
import com.slotmind.domain.PatientPreference;
import com.slotmind.domain.PreferenceSource;
import com.slotmind.domain.PreferredWindow;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.PatientPreferenceRepository;
import com.slotmind.view.Views.PreferenceView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sprint 2: EXPLICIT preference, else INFERRED from completed visits (majority before/after noon), else ANY. */
@Service
@Transactional(readOnly = true)
public class PreferenceService {
    private final PatientPreferenceRepository prefs;
    private final AppointmentRepository appointments;

    public PreferenceService(PatientPreferenceRepository prefs, AppointmentRepository appointments) {
        this.prefs = prefs; this.appointments = appointments;
    }

    public PreferenceView resolve(String patientName) {
        var explicit = prefs.findById(patientName);
        if (explicit.isPresent()) {
            return new PreferenceView(patientName, explicit.get().getPreferredWindow(), explicit.get().getSource().name());
        }
        var past = appointments.findByPatientNameAndStatus(patientName, AppointmentStatus.COMPLETED);
        if (!past.isEmpty()) {
            long morning = past.stream().filter(a -> a.getSlot().getStartTime().getHour() < 12).count();
            long afternoon = past.size() - morning;
            if (morning != afternoon) {
                return new PreferenceView(patientName, morning > afternoon ? PreferredWindow.MORNING : PreferredWindow.AFTERNOON,
                        PreferenceSource.INFERRED.name());
            }
        }
        return new PreferenceView(patientName, PreferredWindow.ANY, "DEFAULT");
    }

    public PreferredWindow windowFor(String patientName) { return resolve(patientName).preferredWindow(); }

    @Transactional
    public PreferenceView setExplicit(String patientName, PreferredWindow window) {
        if (window == null) throw new IllegalArgumentException("preferredWindow is required");
        PatientPreference p = prefs.findById(patientName)
                .orElseGet(() -> new PatientPreference(patientName, window, PreferenceSource.EXPLICIT));
        p.update(window);
        prefs.save(p);
        return new PreferenceView(patientName, window, PreferenceSource.EXPLICIT.name());
    }
}
