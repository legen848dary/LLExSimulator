package com.llexsimulator.engine;

import com.llexsimulator.config.SimulatorConfig;
import com.llexsimulator.fix.ArtioDictionaryResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.agrona.concurrent.SleepingIdleStrategy;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner;
import uk.co.real_logic.artio.validation.MessageValidationStrategy;
import uk.co.real_logic.artio.validation.SessionPersistenceStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the Artio acceptor engine + library lifecycle.
 */
public final class FixEngineManager {

    private static final Logger log = LoggerFactory.getLogger(FixEngineManager.class);
    private static final int POLL_FRAGMENT_LIMIT = 10;
    private static final String ARCHIVE_CONTROL_REQUEST_CHANNEL = "aeron:ipc?term-length=65536";
    private static final String ARCHIVE_CONTROL_RESPONSE_CHANNEL = "aeron:ipc?term-length=65536";
    private static final String ARCHIVE_RECORDING_EVENTS_CHANNEL = "aeron:ipc?term-length=65536";

    private final FixSessionApplication app;
    private final SimulatorConfig config;

    private FixEngine engine;
    private FixLibrary library;
    private Thread pollerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean started;

    public FixEngineManager(FixSessionApplication app, SimulatorConfig config) {
        this.app = app;
        this.config = config;
    }

    public void start() throws Exception {
        Files.createDirectories(Path.of(config.fixLogDir()));
        String libraryAeronChannel = config.artioLibraryAeronChannel();

        EngineConfiguration engineConfiguration = new EngineConfiguration()
                .bindTo(config.fixHost(), config.fixPort())
                .bindAtStartup(true)
                .libraryAeronChannel(libraryAeronChannel)
                .logFileDir(config.fixLogDir())
                // alwaysTransient + container-local logFileDir: always start clean so stale
                // sequence-number index files from a previous run don't get fsynced needlessly.
                .deleteLogFileDirOnStart(true)
                .logInboundMessages(config.fixRawMessageLoggingEnabled())
                .logOutboundMessages(config.fixRawMessageLoggingEnabled())
                .sessionPersistenceStrategy(SessionPersistenceStrategy.alwaysTransient())
                .initialAcceptedSessionOwner(InitialAcceptedSessionOwner.SOLE_LIBRARY)
                .messageValidationStrategy(MessageValidationStrategy.none())
                .acceptorfixDictionary(ArtioDictionaryResolver.resolve())
                .printStartupWarnings(true)
                .printErrorMessages(true);
        engineConfiguration.aeronContext().aeronDirectoryName(config.aeronDir());
        engineConfiguration.aeronArchiveContext()
                .aeronDirectoryName(config.aeronDir())
                .controlRequestChannel(ARCHIVE_CONTROL_REQUEST_CHANNEL)
                .controlResponseChannel(ARCHIVE_CONTROL_RESPONSE_CHANNEL)
                .recordingEventsChannel(ARCHIVE_RECORDING_EVENTS_CHANNEL);

        LibraryConfiguration libraryConfiguration = new LibraryConfiguration()
                .sessionAcquireHandler(app)
                .libraryAeronChannels(java.util.List.of(libraryAeronChannel));
        libraryConfiguration.aeronContext().aeronDirectoryName(config.aeronDir());

        engine = FixEngine.launch(engineConfiguration);
        library = FixLibrary.connect(libraryConfiguration);
        app.attachLibrary(library);

        waitForLibraryConnect();

        running.set(true);
        pollerThread = Thread.ofPlatform()
                .name("artio-library-poller")
                .daemon(true)
                .unstarted(this::pollLibrary);
        pollerThread.setPriority(Thread.MAX_PRIORITY);
        pollerThread.start();

        started = true;
        log.info("Artio FIX acceptor started: host={} port={} logDir={} aeronDir={} libraryChannel={}",
                config.fixHost(), config.fixPort(), config.fixLogDir(), config.aeronDir(), libraryAeronChannel);
    }

    public void stop() {
        if (!started) {
            log.info("FIX acceptor was not started; skipping stop");
            return;
        }
        running.set(false);
        if (pollerThread != null) {
            pollerThread.interrupt();
            try {
                pollerThread.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (library != null) {
            library.close();
        }
        if (engine != null) {
            engine.close();
        }
        started = false;
        log.info("Artio FIX acceptor stopped");
    }

    public boolean isLoggedOn() {
        return started && library != null && !library.sessions().isEmpty();
    }

    public int getSessionCount() {
        return library == null ? 0 : library.sessions().size();
    }

    private void waitForLibraryConnect() {
        long deadlineNs = System.nanoTime() + 5_000_000_000L;
        SleepingIdleStrategy idleStrategy = new SleepingIdleStrategy();
        while (!library.isConnected()) {
            library.poll(POLL_FRAGMENT_LIMIT);
            if (System.nanoTime() >= deadlineNs) {
                throw new IllegalStateException("Timed out waiting for Artio library to connect");
            }
            idleStrategy.idle();
        }
    }

    private void pollLibrary() {
        while (running.get()) {
            int workCount = library.poll(POLL_FRAGMENT_LIMIT);
            if (workCount == 0) {
                if ("BUSY_SPIN".equalsIgnoreCase(config.waitStrategy())) {
                    Thread.onSpinWait();
                } else {
                    try {
                        Thread.sleep(1L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
}

