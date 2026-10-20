package com.slotmind.scheduling;

public record SlotRecommendation(String slotId, String alternateProviderId, double confidence, String reasoning) {}
