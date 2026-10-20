package com.slotmind.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reschedule_suggestion",
        uniqueConstraints = @UniqueConstraint(name = "uk_suggestion_pending_key", columnNames = "pending_key"))
public class RescheduleSuggestion {
    @Id
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_slot_id")
    private Slot originalSlot;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommended_slot_id")
    private Slot recommendedSlot;
    private String alternateProviderId;
    private double confidence;
    @Column(length = 4000)
    private String reasoning;
    @Enumerated(EnumType.STRING)
    private SuggestionStatus status;
    @Enumerated(EnumType.STRING)
    private TriggerReason triggerReason;
    private String strategy;
    private boolean fallbackUsed;
    private boolean crossClinic;
    /** "appointmentId:trigger" while PENDING, null otherwise — unique => DB-level idempotency (NULLs don't collide). */
    @Column(name = "pending_key")
    private String pendingKey;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
    private String resolvedBy;

    protected RescheduleSuggestion() {}
    public RescheduleSuggestion(Appointment appointment, Slot recommendedSlot, String alternateProviderId, double confidence,
                                String reasoning, TriggerReason triggerReason, String strategy, boolean fallbackUsed,
                                boolean crossClinic) {
        this.id = IdGen.next("SUG");
        this.appointment = appointment;
        this.originalSlot = appointment.getSlot();
        this.recommendedSlot = recommendedSlot;
        this.alternateProviderId = alternateProviderId;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.triggerReason = triggerReason;
        this.strategy = strategy;
        this.fallbackUsed = fallbackUsed;
        this.crossClinic = crossClinic;
        this.status = SuggestionStatus.PENDING;
        this.pendingKey = key(appointment.getId(), triggerReason);
        this.createdAt = LocalDateTime.now();
    }

    public static String key(String appointmentId, TriggerReason trigger) { return appointmentId + ":" + trigger; }

    private void resolve(SuggestionStatus next) {
        if (status != SuggestionStatus.PENDING)
            throw new IllegalStateTransitionException("Suggestion " + id + " is " + status + ", not PENDING");
        status = next;
        pendingKey = null;
        resolvedAt = LocalDateTime.now();
    }

    /** PENDING -> ACCEPTED */
    public void accept(String actor) {
        if (recommendedSlot == null) throw new IllegalStateTransitionException("Suggestion " + id + " has no slot to accept");
        resolve(SuggestionStatus.ACCEPTED);
        resolvedBy = actor;
    }
    /** PENDING -> REJECTED */
    public void reject(String actor) { resolve(SuggestionStatus.REJECTED); resolvedBy = actor; }
    /** PENDING -> SUPERSEDED (a newer suggestion replaced it) */
    public void supersede() { resolve(SuggestionStatus.SUPERSEDED); }

    public String getId() { return id; }
    public Appointment getAppointment() { return appointment; }
    public Slot getOriginalSlot() { return originalSlot; }
    public Slot getRecommendedSlot() { return recommendedSlot; }
    public String getAlternateProviderId() { return alternateProviderId; }
    public double getConfidence() { return confidence; }
    public String getReasoning() { return reasoning; }
    public SuggestionStatus getStatus() { return status; }
    public TriggerReason getTriggerReason() { return triggerReason; }
    public String getStrategy() { return strategy; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public boolean isCrossClinic() { return crossClinic; }
    public String getPendingKey() { return pendingKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
}
