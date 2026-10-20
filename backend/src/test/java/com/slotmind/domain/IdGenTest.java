package com.slotmind.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** IdGen counters are process-wide, so each test uses its own unique prefix. */
class IdGenTest {

    @Test
    void sequentialZeroPaddedIdsPerPrefix() {
        assertThat(IdGen.next("TA")).isEqualTo("TA-001");
        assertThat(IdGen.next("TA")).isEqualTo("TA-002");
        assertThat(IdGen.next("TB")).isEqualTo("TB-001");
        assertThat(IdGen.next("TA")).isEqualTo("TA-003");
    }

    @Test
    void startOffsetAppliesOnFirstUseOnly() {
        assertThat(IdGen.next("TC", 100)).isEqualTo("TC-101");
        assertThat(IdGen.next("TC", 100)).isEqualTo("TC-102");
        assertThat(IdGen.next("TC", 500)).as("counter already exists").isEqualTo("TC-103");
        assertThat(IdGen.next("TC")).isEqualTo("TC-104");
    }

    @Test
    void growsBeyondThreeDigits() {
        assertThat(IdGen.next("TD", 998)).isEqualTo("TD-999");
        assertThat(IdGen.next("TD")).isEqualTo("TD-1000");
    }

    @Test
    void uniqueUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Set<String> ids = ConcurrentHashMap.newKeySet();
            List<Future<?>> fs = IntStream.range(0, 8).<Future<?>>mapToObj(i -> pool.submit(() -> {
                for (int k = 0; k < 250; k++) ids.add(IdGen.next("TE"));
            })).toList();
            for (Future<?> f : fs) f.get(10, TimeUnit.SECONDS);
            assertThat(ids).hasSize(2000).contains("TE-001", "TE-2000");
        } finally {
            pool.shutdownNow();
        }
    }
}
