package com.slotmind.domain;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Simple human-readable id generator ("SUG-001"; widens past 999). In-memory DB, so a process-wide counter is enough. */
public final class IdGen {
    private static final ConcurrentHashMap<String, AtomicInteger> COUNTERS = new ConcurrentHashMap<>();
    private IdGen() {}
    public static String next(String prefix, int start) {
        int n = COUNTERS.computeIfAbsent(prefix, p -> new AtomicInteger(start)).incrementAndGet();
        return String.format("%s-%03d", prefix, n);
    }
    public static String next(String prefix) { return next(prefix, 0); }
}
