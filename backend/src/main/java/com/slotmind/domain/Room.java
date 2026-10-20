package com.slotmind.domain;

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "room")
public class Room {
    @Id
    private String id;
    private String name;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id")
    private Clinic clinic;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "room_equipment", joinColumns = @JoinColumn(name = "room_id"))
    @Column(name = "equipment")
    private Set<String> equipment = new HashSet<>();

    protected Room() {}
    public Room(String id, String name, Clinic clinic, Set<String> equipment) {
        this.id = id; this.name = name; this.clinic = clinic; this.equipment = new HashSet<>(equipment);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Clinic getClinic() { return clinic; }
    public Set<String> getEquipment() { return equipment; }
}
