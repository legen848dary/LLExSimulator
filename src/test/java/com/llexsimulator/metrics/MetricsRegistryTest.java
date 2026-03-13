package com.llexsimulator.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricsRegistryTest {

    @Test
    void recordsCountersLatencySnapshotsAndReset() {
        MetricsRegistry registry = new MetricsRegistry();

        registry.incrementOrdersReceived();
        registry.incrementOrdersReceived();
        registry.incrementFills();
        registry.incrementRejects();
        registry.recordLatency(123_000L);
        registry.recordLatency(20_000_000_000L);

        long[] snapshot = registry.snapshot();

        assertEquals(2L, registry.getOrdersReceived());
        assertEquals(2L, registry.getExecReportsSent());
        assertEquals(1L, registry.getFillsSent());
        assertEquals(1L, registry.getRejectsSent());
        assertEquals(2L, snapshot[0]);
        assertEquals(2L, snapshot[1]);
        assertEquals(1L, snapshot[2]);
        assertEquals(1L, snapshot[3]);
        assertEquals(registry.getMaxLatencyNs(), snapshot[7]);
        org.junit.jupiter.api.Assertions.assertTrue(registry.getMaxLatencyNs() >= 10_000_000_000L);

        registry.reset();

        assertEquals(0L, registry.getOrdersReceived());
        assertEquals(0L, registry.getExecReportsSent());
        assertEquals(0L, registry.getFillsSent());
        assertEquals(0L, registry.getRejectsSent());
        assertEquals(0L, registry.getMaxLatencyNs());
        assertEquals(0L, registry.snapshot()[0]);
    }
}

