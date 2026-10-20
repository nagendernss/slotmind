package com.slotmind.scheduling;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** All SchedulingStrategy beans by key; holds the runtime-switchable active key (decision.md §5). */
@Component
public class StrategyRegistry {
    public static final String RULE_BASED = "RULE_BASED";

    private final Map<String, SchedulingStrategy> strategies = new LinkedHashMap<>();
    private volatile String activeKey;

    public StrategyRegistry(List<SchedulingStrategy> beans,
                            @Value("${slotmind.scheduling.strategy:AI}") String configured) {
        for (String k : List.of("AI", RULE_BASED, "ROOM_CONSTRAINT")) {
            beans.stream().filter(b -> b.key().equals(k)).findFirst().ifPresent(b -> strategies.put(k, b));
        }
        beans.forEach(b -> strategies.putIfAbsent(b.key(), b));
        // An empty property (slotmind.scheduling.strategy=) bypasses the @Value default, so treat blank like missing.
        this.activeKey = configured == null || configured.isBlank() ? "AI" : normalize(configured);
    }

    public String activeKey() { return activeKey; }

    public void setActive(String key) {
        String k = normalize(key);
        if (!strategies.containsKey(k)) throw new IllegalArgumentException("Unknown strategy '" + key + "'. Available: " + available());
        this.activeKey = k;
    }

    public Optional<SchedulingStrategy> find(String key) { return Optional.ofNullable(strategies.get(normalize(key))); }

    public SchedulingStrategy ruleBased() { return strategies.get(RULE_BASED); }

    public List<String> available() { return new ArrayList<>(strategies.keySet()); }

    public static String normalize(String key) { return key == null ? null : key.trim().toUpperCase(); }
}
