package com.llexsimulator.metrics;

import org.HdrHistogram.Histogram;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * Central metrics store — all operations are lock-free and zero-GC.
 *
 * <p>Latency is tracked with a <em>rolling-window</em> {@link Histogram}: each
 * time {@link #snapshot()} is called (once per metrics-publish cycle from the
 * Disruptor thread), percentiles are computed from the current window, stored
 * in {@code volatile} longs, and the histogram is reset for the next window.
 * This means p50/p99/p999/max always reflect <em>recent</em> activity only —
 * JVM warm-up spikes and one-off GC pauses do not permanently inflate the
 * reported numbers.
 *
 * <p>The {@code volatile} percentile longs can be read safely from any thread
 * (REST handler, WebSocket subscriber) without locking.
 */
public final class MetricsRegistry {

    private static final long MAX_TRACKABLE_NS = 10_000_000_000L; // 10 s

    // ── Rolling window histogram (disruptor thread only) ─────────────────────
    private final Histogram rollingWindow = new Histogram(MAX_TRACKABLE_NS, 3);

    // ── Last-computed percentiles — written by disruptor, read by any thread ─
    private volatile long lastP80ns  = 0L;
    private volatile long lastP90ns  = 0L;
    private volatile long lastP99ns  = 0L;

    // ── Counters ──────────────────────────────────────────────────────────────
    private final LongAdder ordersReceived  = new LongAdder();
    private final LongAdder fillsSent       = new LongAdder();
    private final LongAdder rejectsSent     = new LongAdder();
    private final LongAdder cancelsSent     = new LongAdder();
    private final LongAdder execReportsSent = new LongAdder();

    // Pre-allocated snapshot array: [orders, execReports, fills, rejects, cancels, p50, p99, p999, max, tps]
    private final long[] snapshotBuf = new long[10];

    private final ThroughputTracker throughputTracker;

    public MetricsRegistry() {
        this.throughputTracker = new ThroughputTracker();
    }

    // ── Hot-path recording (disruptor thread only) ────────────────────────────

    public void recordLatency(long nanos) {
        rollingWindow.recordValue(Math.min(nanos, MAX_TRACKABLE_NS));
        throughputTracker.increment();
    }

    public void incrementOrdersReceived()  { ordersReceived.increment(); }
    public void incrementFills()           { fillsSent.increment();   execReportsSent.increment(); }
    public void incrementRejects()         { rejectsSent.increment(); execReportsSent.increment(); }
    public void incrementCancels()         { cancelsSent.increment(); execReportsSent.increment(); }

    // ── Snapshot (called from disruptor thread in MetricsPublishHandler) ─────

    /**
     * Computes percentiles from the current rolling window, stores them in
     * volatile longs, then resets the window. Returns a pre-allocated
     * {@code long[10]} — no heap allocation.
     */
    public long[] snapshot() {
        // Flip the rolling window: compute then reset
        if (rollingWindow.getTotalCount() > 0) {
            lastP80ns  = rollingWindow.getValueAtPercentile(80.0);
            lastP90ns  = rollingWindow.getValueAtPercentile(90.0);
            lastP99ns  = rollingWindow.getValueAtPercentile(99.0);
            rollingWindow.reset();
        }

        snapshotBuf[0] = ordersReceived.sum();
        snapshotBuf[1] = execReportsSent.sum();
        snapshotBuf[2] = fillsSent.sum();
        snapshotBuf[3] = rejectsSent.sum();
        snapshotBuf[4] = cancelsSent.sum();
        snapshotBuf[5] = lastP80ns;
        snapshotBuf[6] = lastP90ns;
        snapshotBuf[7] = lastP99ns;
        snapshotBuf[8] = 0L; // reserved
        snapshotBuf[9] = throughputTracker.getPerSecond();
        return snapshotBuf;
    }

    // ── Reset (called from REST thread — minor race on rollingWindow is ───────
    //    acceptable: reset is user-triggered and infrequent) ──────────────────

    public void reset() {
        rollingWindow.reset();
        lastP80ns = lastP90ns = lastP99ns = 0L;
        ordersReceived.reset();
        fillsSent.reset();
        rejectsSent.reset();
        cancelsSent.reset();
        execReportsSent.reset();
        throughputTracker.reset();
        Arrays.fill(snapshotBuf, 0L);
    }

    // ── REST-safe accessors (volatile reads — any thread) ────────────────────
    public long getOrdersReceived()  { return ordersReceived.sum(); }
    public long getFillsSent()       { return fillsSent.sum(); }
    public long getRejectsSent()     { return rejectsSent.sum(); }
    public long getCancelsSent()     { return cancelsSent.sum(); }
    public long getExecReportsSent() { return execReportsSent.sum(); }
    public long getP80Ns()           { return lastP80ns; }
    public long getP90Ns()           { return lastP90ns; }
    public long getP99Ns()           { return lastP99ns; }
    public long getThroughputPerSec(){ return throughputTracker.getPerSecond(); }
}
