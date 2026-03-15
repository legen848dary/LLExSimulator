package com.llexsimulator.disruptor;

import com.llexsimulator.config.SimulatorConfig;
import com.llexsimulator.disruptor.handler.CompositeOrderEventHandler;
import com.llexsimulator.disruptor.handler.ExecutionReportHandler;
import com.llexsimulator.disruptor.handler.FillStrategyHandler;
import com.llexsimulator.disruptor.handler.MetricsPublishHandler;
import com.llexsimulator.disruptor.handler.ValidationHandler;
import com.llexsimulator.engine.FixConnection;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.real_logic.artio.decoder.NewOrderSingleDecoder;
import uk.co.real_logic.artio.decoder.OrderCancelRequestDecoder;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires up the order-processing Disruptor pipeline:
 * <pre>
 *   ValidationHandler → FillStrategyHandler → ExecutionReportHandler → MetricsPublishHandler
 * </pre>
 *
 * <p>A single dedicated platform thread runs all four handlers sequentially on
 * the same CPU core — minimal context switching, maximum cache locality.
 */
public final class DisruptorPipeline {

    private static final Logger log = LoggerFactory.getLogger(DisruptorPipeline.class);

    private final Disruptor<OrderEvent>  disruptor;
    private final RingBuffer<OrderEvent> ringBuffer;
    private final OrderEventTranslator   translator = new OrderEventTranslator();

    public DisruptorPipeline(
            SimulatorConfig config,
            CompositeOrderEventHandler compositeOrderEventHandler
    ) {
        WaitStrategy waitStrategy = "BUSY_SPIN".equalsIgnoreCase(config.waitStrategy())
                ? new BusySpinWaitStrategy()
                : new SleepingWaitStrategy(0, 100_000L);

        ThreadFactory tf = new NamedDaemonThreadFactory("disruptor-handler");

        this.disruptor = new Disruptor<>(
                new OrderEventFactory(),
                config.ringBufferSize(),
                tf,
                ProducerType.MULTI,
                waitStrategy
        );

        // Single consumer thread — preserve stage ordering without cross-thread handoff.
        disruptor.handleEventsWith(compositeOrderEventHandler);

        this.ringBuffer = disruptor.getRingBuffer();
        log.info("Disruptor pipeline configured: ringBufferSize={} waitStrategy={}",
                config.ringBufferSize(), config.waitStrategy());
    }

    public void start() {
        disruptor.start();
        log.info("Disruptor pipeline started");
    }

    public void shutdown() {
        disruptor.shutdown();
        log.info("Disruptor pipeline stopped");
    }

    public void publish(NewOrderSingleDecoder decoder, FixConnection connection, long arrivalNs) {
        ringBuffer.publishEvent(translator, decoder, connection, arrivalNs);
    }

    public void publish(OrderCancelRequestDecoder decoder, FixConnection connection, long arrivalNs) {
        ringBuffer.publishEvent(translator, decoder, connection, arrivalNs);
    }

    public RingBuffer<OrderEvent> getRingBuffer() { return ringBuffer; }

    public long getRemainingCapacity() { return ringBuffer.remainingCapacity(); }

    // ── Thread factory ───────────────────────────────────────────────────────

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String                 prefix;
        private final AtomicInteger          counter = new AtomicInteger(0);

        NamedDaemonThreadFactory(String prefix) { this.prefix = prefix; }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = Thread.ofPlatform()
                             .name(prefix + "-" + counter.getAndIncrement())
                             .daemon(true)
                             .unstarted(r);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        }
    }
}

