package com.llexsimulator.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsRegistryTest {

    @Test
    void recordsCountersLatencySnapshotsAndReset() {
        MetricsRegistry registry = new MetricsRegistry();

        registry.incrementOrdersReceived();
        registry.incrementOrdersReceived();
        registry.incrementFills();
        registry.incrementRejects();
        registry.recordLatency(123_000L);
        registry.recordLatency(20_000_000_000L); // clamped to MAX_TRACKABLE_NS (10 s)

        long[] snapshot = registry.snapshot();

        // Counter assertions
        assertEquals(2L, registry.getOrdersReceived());
        assertEquals(2L, registry.getExecReportsSent());
        assertEquals(1L, registry.getFillsSent());
        assertEquals(1L, registry.getRejectsSent());

        // Snapshot buffer assertions
        assertEquals(2L, snapshot[0]); // ordersReceived
        assertEquals(2L, snapshot[1]); // execReportsSent
        assertEquals(1L, snapshot[2]); // fillsSent
        assertEquals(1L, snapshot[3]); // rejectsSent

        // snapshot[7] = p99ns; p99 of [123_000, 10_000_000_000] = 10_000_000_000
        assertEquals(registry.getP99Ns(), snapshot[7]);
        assertTrue(registry.getP99Ns() >= 10_000_000_000L,
                "p99 should be clamped max value; got " + registry.getP99Ns());

        // After reset everything zeroes out
        registry.reset();

        assertEquals(0L, registry.getOrdersReceived());
        assertEquals(0L, registry.getExecReportsSent());
        assertEquals(0L, registry.getFillsSent());
        assertEquals(0L, registry.getRejectsSent());
        assertEquals(0L, registry.getP99Ns());
        assertEquals(0L, registry.snapshot()[0]);
    }
}
