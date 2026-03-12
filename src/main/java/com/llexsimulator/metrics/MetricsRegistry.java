package com.llexsimulator.metrics;

import org.HdrHistogram.Histogram;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * Central metrics store — all operations are lock-free and zero-GC.
 *
 * <p>{@link LongAdder} is used for counters (striped, highly concurrent).
 * {@link Histogram} is pre-allocated with {@code autoResize=false} to prevent
 * any resizing allocation at runtime.
 *
 * <p>{@link #snapshot()} returns a pre-allocated primitive {@code long[9]} array
 * that is overwritten on each call — no heap allocation.
 */
public final class MetricsRegistry {

    // Pre-allocated: max value 10s, 3 significant digits
    private final Histogram latencyHistogram = new Histogram(10_000_000_000L, 3);

    private final LongAdder ordersReceived  = new LongAdder();
    private final LongAdder fillsSent       = new LongAdder();
    private final LongAdder rejectsSent     = new LongAdder();
    private final LongAdder execReportsSent = new LongAdder();

    // Pre-allocated snapshot array: [orders, execReports, fills, rejects, p50, p99, p999, max, tps]
    private final long[] snapshotBuf = new long[9];

    private final ThroughputTracker throughputTracker;

    public MetricsRegistry() {
        this.throughputTracker = new ThroughputTracker();
    }

    public void recordLatency(long nanos) {
        long clamped = Math.min(nanos, 10_000_000_000L);
        latencyHistogram.recordValue(clamped);
        throughputTracker.increment();
    }

    public void incrementOrdersReceived()  { ordersReceived.increment(); }
    public void incrementFills()           { fillsSent.increment(); execReportsSent.increment(); }
    public void incrementRejects()         { rejectsSent.increment(); execReportsSent.increment(); }

    public void reset() {
        latencyHistogram.reset();
        ordersReceived.reset();
        fillsSent.reset();
        rejectsSent.reset();
        execReportsSent.reset();
        throughputTracker.reset();
        Arrays.fill(snapshotBuf, 0L);
    }

    /**
     * Returns a pre-allocated {@code long[9]} snapshot.
     * Indices: [ordersReceived, execReports, fills, rejects, p50ns, p99ns, p999ns, maxNs, tps]
     */
    public long[] snapshot() {
        snapshotBuf[0] = ordersReceived.sum();
        snapshotBuf[1] = execReportsSent.sum();
        snapshotBuf[2] = fillsSent.sum();
        snapshotBuf[3] = rejectsSent.sum();
        snapshotBuf[4] = latencyHistogram.getValueAtPercentile(50.0);
        snapshotBuf[5] = latencyHistogram.getValueAtPercentile(99.0);
        snapshotBuf[6] = latencyHistogram.getValueAtPercentile(99.9);
        snapshotBuf[7] = latencyHistogram.getMaxValue();
        snapshotBuf[8] = throughputTracker.getPerSecond();
        return snapshotBuf;
    }

    // ── Convenience accessors for the REST handler ──────────────────────────
    public long getOrdersReceived()  { return ordersReceived.sum(); }
    public long getFillsSent()       { return fillsSent.sum(); }
    public long getRejectsSent()     { return rejectsSent.sum(); }
    public long getExecReportsSent() { return execReportsSent.sum(); }
    public long getP50Ns()           { return latencyHistogram.getValueAtPercentile(50.0); }
    public long getP99Ns()           { return latencyHistogram.getValueAtPercentile(99.0); }
    public long getP999Ns()          { return latencyHistogram.getValueAtPercentile(99.9); }
    public long getMaxLatencyNs()    { return latencyHistogram.getMaxValue(); }
    public long getThroughputPerSec(){ return throughputTracker.getPerSecond(); }
}

