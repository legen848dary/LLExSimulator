package com.llexsimulator.client;

import com.llexsimulator.aeron.AeronContext;
import com.llexsimulator.aeron.AeronRuntimeTuning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.validation.MessageValidationStrategy;
import uk.co.real_logic.artio.validation.SessionPersistenceStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Standalone demo FIX client entry-point.
 *
 * <p>Connects as an Artio initiator to the local simulator and continuously
 * sends {@code NewOrderSingle} messages at a fixed rate until the process is
 * stopped.
 */
public final class FixDemoClientMain {

    private static final Logger log = LoggerFactory.getLogger(FixDemoClientMain.class);

    private FixDemoClientMain() {}

    public static void main(String[] args) throws Exception {
        setSystemPropertyIfAbsent("aeron.ipc.term.buffer.length",
                Integer.toString(AeronRuntimeTuning.DEFAULT_ARTIO_IPC_TERM_LENGTH_BYTES));
        FixDemoClientConfig config = FixDemoClientConfig.from(args);
        createLogDirectories(config);
        String aeronDir = System.getProperty("fix.demo.aeron.dir",
                Path.of(config.artioLogDir(), "aeron").toString());
        String libraryChannel = System.getProperty(
                "fix.demo.artio.library.aeron.channel",
                AeronRuntimeTuning.DEFAULT_ARTIO_LIBRARY_CHANNEL);

        FixDemoClientApplication app = new FixDemoClientApplication(config);
        AeronContext aeronContext = new AeronContext(aeronDir);
        EngineConfiguration engineConfiguration = new EngineConfiguration()
                .bindAtStartup(false)
                .logFileDir(Path.of(config.artioLogDir(), "engine").toString())
                .deleteLogFileDirOnStart(false)
                .libraryAeronChannel(libraryChannel)
                .logInboundMessages(config.rawMessageLoggingEnabled())
                .logOutboundMessages(config.rawMessageLoggingEnabled())
                .sessionPersistenceStrategy(SessionPersistenceStrategy.alwaysTransient())
                .messageValidationStrategy(MessageValidationStrategy.none())
                .printStartupWarnings(true)
                .printErrorMessages(true);
        engineConfiguration.aeronContext().aeronDirectoryName(aeronDir);

        LibraryConfiguration libraryConfiguration = new LibraryConfiguration()
                .sessionAcquireHandler(app)
                .defaultHeartbeatIntervalInS(config.heartBtIntSec())
                .libraryAeronChannels(java.util.List.of(libraryChannel));
        libraryConfiguration.aeronContext().aeronDirectoryName(aeronDir);

        FixEngine engine = FixEngine.launch(engineConfiguration);
        FixLibrary library = FixLibrary.connect(libraryConfiguration);
        app.attachLibrary(library);
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread libraryPoller = Thread.ofPlatform()
                .name("fix-demo-artio-poller")
                .daemon(true)
                .unstarted(() -> {
                    while (running.get()) {
                        int work = library.poll(10);
                        if (work == 0) {
                            Thread.onSpinWait();
                        }
                    }
                });
        libraryPoller.setPriority(Thread.MAX_PRIORITY);
        libraryPoller.start();
        CountDownLatch shutdownLatch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down demo FIX client");
            running.set(false);
            libraryPoller.interrupt();
            try {
                library.close();
            } catch (Exception e) {
                log.warn("Error while closing Artio library", e);
            }
            try {
                engine.close();
            } catch (Exception e) {
                log.warn("Error while closing Artio engine", e);
            }
            try {
                aeronContext.close();
            } catch (Exception e) {
                log.warn("Error while closing Aeron context", e);
            }
            try {
                app.close();
            } catch (Exception e) {
                log.warn("Error while closing application", e);
            }
            shutdownLatch.countDown();
        }, "fix-demo-client-shutdown"));

        app.start();

        log.info("Demo FIX raw message logging {}",
                config.rawMessageLoggingEnabled() ? "enabled (Artio engine logs)" : "disabled");
        log.info("Demo FIX client started — waiting for Artio logon to {}:{} (session {} {}->{}, aeronDir={}, libraryChannel={})",
                config.host(), config.port(), config.beginString(), config.senderCompId(), config.targetCompId(),
                aeronDir, libraryChannel);
        shutdownLatch.await();
    }

    private static void createLogDirectories(FixDemoClientConfig config) throws java.io.IOException {
        Files.createDirectories(Path.of(System.getProperty("llexsim.log.dir", "logs/fix-demo-client")));
        Files.createDirectories(Path.of(config.artioLogDir()));
        Files.createDirectories(Path.of(config.artioLogDir(), "aeron"));
        Files.createDirectories(Path.of(config.artioLogDir(), "engine"));
    }

    private static void setSystemPropertyIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}

