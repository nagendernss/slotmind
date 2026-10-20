package com.slotmind.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "notification")
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String appointmentId;
    @Enumerated(EnumType.STRING)
    private NotificationChannel channel;
    private String recipient;
    @Column(length = 2000)
    private String message;
    @Enumerated(EnumType.STRING)
    private NotificationStatus status;
    private LocalDateTime createdAt;

    protected Notification() {}
    public Notification(String appointmentId, NotificationChannel channel, String recipient, String message) {
        this.appointmentId = appointmentId; this.channel = channel; this.recipient = recipient; this.message = message;
        this.status = NotificationStatus.SENT; this.createdAt = LocalDateTime.now();
    }

    public void markFailed() { status = NotificationStatus.FAILED; }

    public Long getId() { return id; }
    public String getAppointmentId() { return appointmentId; }
    public NotificationChannel getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getMessage() { return message; }
    public NotificationStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
