package com.slotmind.repository;

import com.slotmind.domain.PatientPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientPreferenceRepository extends JpaRepository<PatientPreference, String> {}
