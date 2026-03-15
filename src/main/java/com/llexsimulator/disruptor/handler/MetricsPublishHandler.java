package com.llexsimulator.disruptor.handler;

import com.llexsimulator.aeron.MetricsPublisher;
import com.llexsimulator.disruptor.OrderEvent;
import com.llexsimulator.metrics.MetricsRegistry;
import com.llexsimulator.sbe.FillBehaviorType;
import com.llexsimulator.sbe.OrderSide;
import com.lmax.disruptor.EventHandler;

import java.util.function.Consumer;

/**
 * Stage 4: records latency / counters and periodically publishes a
 * {@code MetricsSnapshot} via Aeron IPC to the Vert.x WebSocket broadcaster.
 * Also samples order events and sends them to WebSocket clients for the Order
 * Flow UI table.
 *
 * <p>All operations are zero-GC on the hot path. Order event sampling is
 * intentionally once per metrics-publish interval (1 String allocation per cycle).
 */
public final class MetricsPublishHandler implements EventHandler<OrderEvent> {

    private final MetricsRegistry  registry;
    private final MetricsPublisher publisher;
    private final int              publishInterval;
    private final boolean          livePublishingEnabled;

    // Pre-allocated buffer for order event JSON (disruptor thread only — no sync needed)
    private final StringBuilder orderEventBuf = new StringBuilder(512);

    /** Set once after web-server startup; null until then. */
    private volatile Consumer<String> orderEventCallback;

    private long eventCounter = 0L;

    public MetricsPublishHandler(MetricsRegistry registry,
                                 MetricsPublisher publisher,
                                 int publishInterval,
                                 boolean livePublishingEnabled) {
        this.registry        = registry;
        this.publisher       = publisher;
        this.publishInterval = publishInterval;
        this.livePublishingEnabled = livePublishingEnabled;
    }

    /** Wire in the WebSocket broadcast callback once the web server is ready. */
    public void setOrderEventCallback(Consumer<String> callback) {
        this.orderEventCallback = callback;
    }

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        long now = System.nanoTime();
        long latencyNs = now - event.arrivalTimeNs;

        registry.recordLatency(latencyNs);
        registry.incrementOrdersReceived();

        FillBehaviorType behavior = event.fillInstructionDecoder.fillBehavior();
        switch (behavior) {
            case REJECT                         -> registry.incrementRejects();
            case NO_FILL_IOC_CANCEL             -> registry.incrementCancels();
            case PARTIAL_THEN_CANCEL            -> { registry.incrementFills(); registry.incrementCancels(); }
            default                             -> registry.incrementFills();
        }

        if (++eventCounter % publishInterval == 0) {
            long[] snapshot = registry.snapshot();
            if (livePublishingEnabled) {
                publishSnapshot(now, snapshot);
                emitOrderEvent(event, latencyNs);
            }
        }
    }

    // ── Metrics snapshot ─────────────────────────────────────────────────────

    private void publishSnapshot(long snapshotTimeNs, long[] snap) {
        // snap[4] = cancels — not in Aeron/SBE path; the WebSocket subscriber reads it directly
        publisher.publish(
                snapshotTimeNs,
                snap[0], // ordersReceived
                snap[1], // execReportsSent
                snap[2], // fills
                snap[3], // rejects
                snap[5], // p50 ns
                snap[6], // p99 ns
                snap[7], // p999 ns
                snap[8], // max ns
                snap[9]  // throughput / sec
        );
    }

    // ── Order event broadcast ────────────────────────────────────────────────

    private void emitOrderEvent(OrderEvent event, long latencyNs) {
        Consumer<String> cb = orderEventCallback;
        if (cb == null) return;

        FillBehaviorType behavior = event.fillInstructionDecoder.fillBehavior();
        String execType  = mapExecTypeStr(behavior);
        String ordStatus = mapOrdStatusStr(behavior);
        String side      = event.nosDecoder.side() == OrderSide.BUY ? "BUY" : "SELL";

        // Trim symbol (bytes may be null/space padded)
        int symLen = 0;
        for (int i = 0; i < event.symbolBytes.length; i++) {
            byte b = event.symbolBytes[i];
            if (b == 0 || b == ' ') break;
            symLen++;
        }

        // Scaled longs → human-readable
        long qtyScaled   = event.nosDecoder.orderQty();   // 4 dp
        long priceScaled = event.nosDecoder.price();      // 8 dp
        long qtyWhole    = qtyScaled   / 10_000L;
        long pxWhole     = priceScaled / 100_000_000L;
        long pxFrac      = Math.abs(priceScaled % 100_000_000L);

        orderEventBuf.setLength(0);
        orderEventBuf.append("{\"type\":\"order\"")
                     .append(",\"correlationId\":").append(event.correlationId)
                     .append(",\"symbol\":\"");
        for (int i = 0; i < symLen; i++) orderEventBuf.append((char) event.symbolBytes[i]);
        orderEventBuf.append("\"")
                     .append(",\"side\":\"").append(side).append("\"")
                     .append(",\"orderQty\":").append(qtyWhole)
                     .append(",\"price\":").append(pxWhole).append('.').append(pxFrac / 1_000_000L)
                     .append(",\"execType\":\"").append(execType).append("\"")
                     .append(",\"ordStatus\":\"").append(ordStatus).append("\"")
                     .append(",\"fillBehavior\":\"").append(behavior.name()).append("\"")
                     .append(",\"latencyNs\":").append(latencyNs)
                     .append("}");

        cb.accept(orderEventBuf.toString()); // one String alloc per sample — acceptable
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String mapExecTypeStr(FillBehaviorType b) {
        return switch (b) {
            case REJECT             -> "REJECTED";
            case NO_FILL_IOC_CANCEL -> "CANCELED";
            case PARTIAL_THEN_CANCEL, PARTIAL_FILL -> "PARTIAL_FILL";
            default                 -> "FILL";
        };
    }

    private static String mapOrdStatusStr(FillBehaviorType b) {
        return switch (b) {
            case REJECT             -> "REJECTED";
            case NO_FILL_IOC_CANCEL -> "CANCELED";
            case PARTIAL_THEN_CANCEL, PARTIAL_FILL -> "PARTIALLY_FILLED";
            default                 -> "FILLED";
        };
    }
}
