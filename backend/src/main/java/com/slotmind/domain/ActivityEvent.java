package com.slotmind.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "activity_event")
public class ActivityEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private LocalDateTime at;
    @Enumerated(EnumType.STRING)
    private ActivityKind kind;
    @Column(length = 2000)
    private String message;

    protected ActivityEvent() {}
    public ActivityEvent(ActivityKind kind, String message) {
        this.at = LocalDateTime.now(); this.kind = kind;
        this.message = message != null && message.length() > 2000 ? message.substring(0, 1997) + "..." : message;
    }

    public Long getId() { return id; }
    public LocalDateTime getAt() { return at; }
    public ActivityKind getKind() { return kind; }
    public String getMessage() { return message; }
}
