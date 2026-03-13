package com.llexsimulator.aeron;

import com.llexsimulator.metrics.MetricsRegistry;
import com.llexsimulator.sbe.MessageHeaderDecoder;
import com.llexsimulator.sbe.MetricsSnapshotDecoder;
import com.llexsimulator.web.WebSocketBroadcaster;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.vertx.core.Vertx;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscribes to the Aeron IPC metrics stream and forwards decoded snapshots
 * as JSON to all WebSocket clients via {@link WebSocketBroadcaster}.
 * Cancels are appended from {@link MetricsRegistry} since they are not in the SBE schema.
 */
public final class MetricsSubscriber implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MetricsSubscriber.class);
    private static final int    STREAM_ID = 1002;

    private final Subscription           subscription;
    private final WebSocketBroadcaster   broadcaster;
    private final Vertx                  vertx;
    private final MetricsRegistry        metricsRegistry;
    private final MessageHeaderDecoder   headerDecoder  = new MessageHeaderDecoder();
    private final MetricsSnapshotDecoder snapshotDecoder = new MetricsSnapshotDecoder();
    private final StringBuilder          jsonBuf        = new StringBuilder(512);

    private volatile boolean running = true;

    public MetricsSubscriber(AeronContext ctx, WebSocketBroadcaster broadcaster, Vertx vertx,
                              MetricsRegistry metricsRegistry, String channel) {
        this.subscription    = ctx.getAeron().addSubscription(channel, STREAM_ID);
        this.broadcaster     = broadcaster;
        this.vertx           = vertx;
        this.metricsRegistry = metricsRegistry;
        log.info("MetricsSubscriber ready on {} stream {}", channel, STREAM_ID);
    }

    @Override
    public void run() {
        FragmentHandler handler = this::onFragment;
        while (running && !Thread.currentThread().isInterrupted()) {
            int fragments = subscription.poll(handler, 10);
            if (fragments == 0) {
                Thread.onSpinWait();
            }
        }
    }

    private void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
        snapshotDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        jsonBuf.setLength(0);
        jsonBuf.append("{\"type\":\"metrics\"")
               .append(",\"snapshotTimeNs\":").append(snapshotDecoder.snapshotTimeNs())
               .append(",\"ordersReceived\":").append(snapshotDecoder.ordersReceivedCount())
               .append(",\"execReportsSent\":").append(snapshotDecoder.executionReportsSent())
               .append(",\"fills\":").append(snapshotDecoder.fillsCount())
               .append(",\"rejects\":").append(snapshotDecoder.rejectsCount())
               .append(",\"cancels\":").append(metricsRegistry.getCancelsSent())
               .append(",\"p80Us\":").append(metricsRegistry.getP80Ns() / 1000)
               .append(",\"p90Us\":").append(metricsRegistry.getP90Ns() / 1000)
               .append(",\"p99Us\":").append(metricsRegistry.getP99Ns() / 1000)
               .append(",\"throughputPerSec\":").append(snapshotDecoder.throughputPerSec())
               .append("}");

        String json = jsonBuf.toString();
        vertx.runOnContext(v -> broadcaster.broadcast(json));
    }

    public void stop() { running = false; }
}
