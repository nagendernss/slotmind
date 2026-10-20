package com.slotmind.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "slot")
public class Slot {
    @Id
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id")
    private Provider provider;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;
    private LocalDateTime startTime;
    private int durationMinutes;
    @Enumerated(EnumType.STRING)
    private SlotStatus status;
    @Version
    private Long version;

    protected Slot() {}
    public Slot(String id, Provider provider, Room room, LocalDateTime startTime, int durationMinutes, SlotStatus status) {
        this.id = id; this.provider = provider; this.room = room; this.startTime = startTime;
        this.durationMinutes = durationMinutes; this.status = status;
    }

    /** OPEN -> BOOKED */
    public void book() {
        if (status != SlotStatus.OPEN) throw new IllegalStateTransitionException("Slot " + id + " is " + status + ", cannot book");
        status = SlotStatus.BOOKED;
    }

    /** BOOKED -> OPEN */
    public void free() {
        if (status != SlotStatus.BOOKED) throw new IllegalStateTransitionException("Slot " + id + " is " + status + ", cannot free");
        status = SlotStatus.OPEN;
    }

    /** OPEN|BOOKED -> CANCELLED (terminal) */
    public void cancel() {
        if (status == SlotStatus.CANCELLED) throw new IllegalStateTransitionException("Slot " + id + " is already CANCELLED");
        status = SlotStatus.CANCELLED;
    }

    public LocalDateTime getEndTime() { return startTime.plusMinutes(durationMinutes); }

    public String getId() { return id; }
    public Provider getProvider() { return provider; }
    public Room getRoom() { return room; }
    public LocalDateTime getStartTime() { return startTime; }
    public int getDurationMinutes() { return durationMinutes; }
    public SlotStatus getStatus() { return status; }
    public Long getVersion() { return version; }
}
