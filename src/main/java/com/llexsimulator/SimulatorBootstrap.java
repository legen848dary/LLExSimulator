package com.llexsimulator;

import com.llexsimulator.aeron.AeronContext;
import com.llexsimulator.aeron.AeronRuntimeTuning;
import com.llexsimulator.aeron.MetricsPublisher;
import com.llexsimulator.aeron.MetricsSubscriber;
import com.llexsimulator.config.ConfigLoader;
import com.llexsimulator.config.SimulatorConfig;
import com.llexsimulator.disruptor.DisruptorPipeline;
import com.llexsimulator.disruptor.handler.*;
import com.llexsimulator.engine.FixEngineManager;
import com.llexsimulator.engine.FixOutboundSender;
import com.llexsimulator.engine.FixSessionApplication;
import com.llexsimulator.engine.OrderSessionRegistry;
import com.llexsimulator.fill.FillProfileManager;
import com.llexsimulator.metrics.MetricsRegistry;
import com.llexsimulator.order.OrderRepository;
import com.llexsimulator.web.WebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the startup and shutdown of all LLExSimulator subsystems.
 *
 * <p>Startup order is critical:
 * <ol>
 *   <li>Config</li>
 *   <li>Aeron (IPC transport)</li>
 *   <li>Metrics</li>
 *   <li>Order Repository (pre-allocates off-heap pool)</li>
 *   <li>Fill Profile Manager (seeds built-in profiles)</li>
 *   <li>Disruptor Pipeline (pre-allocates ring buffer)</li>
 *   <li>Web Server (Vert.x)</li>
 *   <li>Metrics Subscriber (Aeron → WebSocket bridge)</li>
 *   <li>FIX Engine (QuickFIX/J acceptor — last, so everything is ready)</li>
 * </ol>
 */
public final class SimulatorBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SimulatorBootstrap.class);

    private SimulatorConfig    config;
    private AeronContext       aeronContext;
    private MetricsPublisher   metricsPublisher;
    private MetricsRegistry    metricsRegistry;
    private OrderRepository    orderRepository;
    private FillProfileManager profileManager;
    private DisruptorPipeline  disruptorPipeline;
    private WebServer          webServer;
    private MetricsSubscriber  metricsSubscriber;
    private FixEngineManager   fixEngineManager;

    public void start() throws Exception {
        log.info("=== LLExSimulator starting ===");

        // 1 — Config
        config = AeronRuntimeTuning.resolve(ConfigLoader.load());
        System.setProperty("aeron.dir", config.aeronDir());
        log.info("Config: fixPort={} webPort={} ringBufferSize={} aeronDir={} artioLibraryChannel={} metricsChannel={}",
                config.fixPort(), config.webPort(), config.ringBufferSize(),
                config.aeronDir(), config.artioLibraryAeronChannel(), config.metricsAeronChannel());

        // 2 — Aeron
        aeronContext   = new AeronContext(config.aeronDir(), config.fixRawMessageLoggingEnabled());
        metricsPublisher = new MetricsPublisher(aeronContext, config.metricsAeronChannel());

        // 3 — Metrics
        metricsRegistry = new MetricsRegistry();

        // 4 — Order repository (pre-allocated off-heap pool)
        orderRepository = new OrderRepository(config.orderPoolSize());

        // 5 — Fill profile manager
        profileManager = new FillProfileManager();

        // 6 — Disruptor pipeline
        OrderSessionRegistry sessionRegistry = new OrderSessionRegistry(config.benchmarkModeEnabled());
        FixOutboundSender outboundSender = new FixOutboundSender();

        ValidationHandler     validationHandler     = new ValidationHandler();
        FillStrategyHandler   fillStrategyHandler   = new FillStrategyHandler(profileManager, orderRepository);
        ExecutionReportHandler execReportHandler    = new ExecutionReportHandler(sessionRegistry, orderRepository, outboundSender);
        boolean liveMetricsEnabled = !config.benchmarkModeEnabled();
        MetricsPublishHandler  metricsPublishHandler = new MetricsPublishHandler(
                metricsRegistry, metricsPublisher, config.metricsPublishInterval(), liveMetricsEnabled);
        CompositeOrderEventHandler compositeOrderEventHandler = new CompositeOrderEventHandler(
                validationHandler, fillStrategyHandler, execReportHandler, metricsPublishHandler);

        disruptorPipeline = new DisruptorPipeline(config, compositeOrderEventHandler);
        disruptorPipeline.start();

        // 7 — Web server
        webServer = new WebServer(config, metricsRegistry, sessionRegistry, profileManager, disruptorPipeline);

        if (liveMetricsEnabled) {
            // 7.5 — Wire order event broadcast callback (before FIX engine starts)
            metricsPublishHandler.setOrderEventCallback(json ->
                    webServer.getVertx().runOnContext(v -> webServer.getBroadcaster().broadcast(json)));

            // 8 — Metrics subscriber (Aeron IPC → WebSocket broadcast)
            metricsSubscriber = new MetricsSubscriber(
                    aeronContext, webServer.getBroadcaster(), webServer.getVertx(),
                    metricsRegistry, config.metricsAeronChannel());
            Thread.ofVirtual().name("metrics-subscriber").start(metricsSubscriber);
        } else {
            log.info("Benchmark mode enabled — live Aeron/WebSocket metrics publishing is disabled");
        }

        // 9 — FIX engine (last — everything must be ready before accepting connections)
        FixSessionApplication fixApp = new FixSessionApplication(sessionRegistry, disruptorPipeline);
        fixEngineManager = new FixEngineManager(fixApp, config, outboundSender);
        fixEngineManager.start();

        log.info("=== LLExSimulator ready — FIX port {} | Web port {} ===",
                config.fixPort(), config.webPort());
    }

    public void stop() {
        log.info("=== LLExSimulator shutting down ===");
        try { if (fixEngineManager   != null) fixEngineManager.stop();      } catch (Exception e) { log.error("FIX engine stop error", e); }
        try { if (metricsSubscriber  != null) metricsSubscriber.stop();     } catch (Exception e) { log.error("MetricsSubscriber stop error", e); }
        try { if (webServer          != null) webServer.stop();             } catch (Exception e) { log.error("WebServer stop error", e); }
        try { if (disruptorPipeline  != null) disruptorPipeline.shutdown(); } catch (Exception e) { log.error("Disruptor stop error", e); }
        try { if (metricsPublisher   != null) metricsPublisher.close();     } catch (Exception e) { log.error("MetricsPublisher stop error", e); }
        try { if (aeronContext       != null) aeronContext.close();         } catch (Exception e) { log.error("Aeron stop error", e); }
        log.info("=== LLExSimulator stopped ===");
    }
}

