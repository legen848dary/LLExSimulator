package com.llexsimulator.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.LogFactory;
import quickfix.MemoryStoreFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

import java.nio.file.Files;
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

        FixDemoClientApplication app = new FixDemoClientApplication(config);
        SessionSettings sessionSettings = config.toSessionSettings();
        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = config.rawMessageLoggingEnabled()
                ? new FileLogFactory(sessionSettings)
                : new NoOpQuickFixLogFactory();
        MessageFactory messageFactory = new DefaultMessageFactory();
        SocketInitiator initiator = new SocketInitiator(app, storeFactory, sessionSettings, logFactory, messageFactory);
        CountDownLatch shutdownLatch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down demo FIX client");
            try {
                app.close();
            } catch (Exception e) {
                log.warn("Error while closing application", e);
            }
            try {
                initiator.stop(true);
            } catch (Exception e) {
                log.warn("Error while stopping QuickFIX/J initiator", e);
            }
            shutdownLatch.countDown();
        }, "fix-demo-client-shutdown"));

        initiator.start();
        app.start();

        log.info("Demo FIX raw message logging {}",
                config.rawMessageLoggingEnabled() ? "enabled (QuickFIX/J file logs)" : "disabled");
        log.info("Demo FIX client started — waiting for QuickFIX/J logon to {}:{} (session {} {}->{}, storeDir={}, rawLogDir={})",
                config.host(), config.port(), config.beginString(), config.senderCompId(), config.targetCompId(),
                config.storeDir(), config.rawLogDir());
        shutdownLatch.await();
    }

    private static void createLogDirectories(FixDemoClientConfig config) throws java.io.IOException {
        Files.createDirectories(java.nio.file.Path.of(System.getProperty("llexsim.log.dir", "logs/fix-demo-client")));
        Files.createDirectories(config.storeDir());
        Files.createDirectories(config.rawLogDir());
    }
}

