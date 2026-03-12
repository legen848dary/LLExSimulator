package com.llexsimulator.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Standalone demo FIX client entry-point.
 *
 * <p>Connects as a QuickFIX/J initiator to the local simulator and continuously
 * sends {@code NewOrderSingle} messages at a fixed rate until the process is
 * stopped.
 */
public final class FixDemoClientMain {

    private static final Logger log = LoggerFactory.getLogger(FixDemoClientMain.class);

    private FixDemoClientMain() {}

    public static void main(String[] args) throws Exception {
        FixDemoClientConfig config = FixDemoClientConfig.from(args);
        createLogDirectories(config);

        SessionSettings settings = config.toSessionSettings();
        FixDemoClientApplication app = new FixDemoClientApplication(config);
        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = new FileLogFactory(settings);
        MessageFactory msgFactory = new DefaultMessageFactory();
        SocketInitiator initiator = new SocketInitiator(app, storeFactory, settings, logFactory, msgFactory);
        CountDownLatch shutdownLatch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down demo FIX client");
            try {
                initiator.stop();
            } catch (Exception e) {
                log.warn("Error while stopping initiator", e);
            }
            try {
                app.close();
            } catch (Exception e) {
                log.warn("Error while closing application", e);
            }
            shutdownLatch.countDown();
        }, "fix-demo-client-shutdown"));

        app.start();
        initiator.start();

        log.info("Demo FIX client started — waiting for logon to {}:{} (session {} {}->{})",
                config.host(), config.port(), config.beginString(), config.senderCompId(), config.targetCompId());
        shutdownLatch.await();
    }

    private static void createLogDirectories(FixDemoClientConfig config) throws java.io.IOException {
        Files.createDirectories(Path.of(System.getProperty("llexsim.log.dir", "logs/fix-demo-client")));
        Files.createDirectories(Path.of(config.quickFixLogDir()));
        Files.createDirectories(Path.of(config.quickFixLogDir(), "session"));
    }
}

