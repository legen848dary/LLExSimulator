package com.llexsimulator.disruptor.handler;

import com.llexsimulator.aeron.MetricsPublisher;
import com.llexsimulator.disruptor.OrderEvent;
import com.llexsimulator.metrics.MetricsRegistry;
import com.llexsimulator.sbe.FillBehaviorType;
import com.lmax.disruptor.EventHandler;

/**
 * Stage 4: records latency / counters and periodically publishes a
 * {@code MetricsSnapshot} via Aeron IPC to the Vert.x WebSocket broadcaster.
 *
 * <p>Publishing is best-effort: if Aeron is back-pressured the snapshot is
 * silently dropped. No locking, no allocation.
 */
public final class MetricsPublishHandler implements EventHandler<OrderEvent> {

    private final MetricsRegistry  registry;
    private final MetricsPublisher publisher;
    private final int              publishInterval;

    private long eventCounter = 0L;

    public MetricsPublishHandler(MetricsRegistry registry,
                                 MetricsPublisher publisher,
                                 int publishInterval) {
        this.registry        = registry;
        this.publisher       = publisher;
        this.publishInterval = publishInterval;
    }

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        long now = System.nanoTime();
        long latencyNs = now - event.arrivalTimeNs;

        registry.recordLatency(latencyNs);
        registry.incrementOrdersReceived();

        FillBehaviorType behavior = event.fillInstructionDecoder.fillBehavior();
        if (behavior == FillBehaviorType.REJECT || behavior == FillBehaviorType.NO_FILL_IOC_CANCEL) {
            registry.incrementRejects();
        } else {
            registry.incrementFills();
        }

        if (++eventCounter % publishInterval == 0) {
            publishSnapshot(now);
        }
    }

    private void publishSnapshot(long snapshotTimeNs) {
        long[] snap = registry.snapshot(); // returns primitive array — no allocation
        publisher.publish(
                snapshotTimeNs,
                snap[0], // ordersReceived
                snap[1], // execReportsSent
                snap[2], // fills
                snap[3], // rejects
                snap[4], // p50 ns
                snap[5], // p99 ns
                snap[6], // p999 ns
                snap[7], // max ns
                snap[8]  // throughput / sec
        );
    }
}

