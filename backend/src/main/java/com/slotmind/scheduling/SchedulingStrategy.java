package com.slotmind.scheduling;

import java.util.List;

/** Ranks the (already legal) candidate slots. Implementations are Spring beans picked up by StrategyRegistry. */
public interface SchedulingStrategy {
    /** "AI", "RULE_BASED", "ROOM_CONSTRAINT" */
    String key();

    /** Ordered best-first; may throw on failure (the engine then falls back to rule-based). */
    List<SlotRecommendation> recommend(SchedulingContext ctx);
}
