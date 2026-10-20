package com.slotmind.domain;

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "appointment_type_rule")
public class AppointmentTypeRule {
    @Id
    @Enumerated(EnumType.STRING)
    private AppointmentType type;
    private int durationMinutes;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "type_rule_equipment", joinColumns = @JoinColumn(name = "type"))
    @Column(name = "equipment")
    private Set<String> requiredEquipment = new HashSet<>();

    protected AppointmentTypeRule() {}
    public AppointmentTypeRule(AppointmentType type, int durationMinutes, Set<String> requiredEquipment) {
        this.type = type; this.durationMinutes = durationMinutes; this.requiredEquipment = new HashSet<>(requiredEquipment);
    }

    public AppointmentType getType() { return type; }
    public int getDurationMinutes() { return durationMinutes; }
    public Set<String> getRequiredEquipment() { return requiredEquipment; }
}
