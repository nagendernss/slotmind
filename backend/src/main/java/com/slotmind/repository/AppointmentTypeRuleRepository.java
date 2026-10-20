package com.slotmind.repository;

import com.slotmind.domain.AppointmentType;
import com.slotmind.domain.AppointmentTypeRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentTypeRuleRepository extends JpaRepository<AppointmentTypeRule, AppointmentType> {}
