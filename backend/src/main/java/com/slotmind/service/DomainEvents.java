package com.slotmind.service;

/** Domain events, published inside the mutating transaction and handled AFTER_COMMIT (decision.md §7, §11). */
public final class DomainEvents {
    private DomainEvents() {}

    public record ProviderWentOffline(String providerId, String providerName) {}
    public record SlotCancelled(String slotId) {}
    public record SlotFreed(String slotId) {}
    public record SuggestionAccepted(String suggestionId, String appointmentId, String actor) {}
    public record WaitlistBooked(String entryId, String appointmentId) {}
}
