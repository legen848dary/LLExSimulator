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
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the Artio acceptor engine + library lifecycle.
 */
public final class FixEngineManager {

    private static final Logger log = LoggerFactory.getLogger(FixEngineManager.class);
    private static final int POLL_FRAGMENT_LIMIT = 256;
    private static final int MAX_POLL_BATCHES_PER_CYCLE = 8;
    private static final int EMPTY_POLLS_BEFORE_YIELD = 100;
    private static final int EMPTY_POLLS_BEFORE_PARK = 1_000;
    private static final long PARK_NANOS = 50_000L;
    private static final String ARCHIVE_CONTROL_REQUEST_CHANNEL = "aeron:ipc?term-length=65536";
    private static final String ARCHIVE_CONTROL_RESPONSE_CHANNEL = "aeron:ipc?term-length=65536";
    private static final String ARCHIVE_RECORDING_EVENTS_CHANNEL = "aeron:ipc?term-length=65536";

    private final FixSessionApplication app;
    private final SimulatorConfig config;
    private final FixOutboundSender outboundSender;

    private FixEngine engine;
    private FixLibrary library;
    private Thread pollerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean started;

    public FixEngineManager(FixSessionApplication app, SimulatorConfig config, FixOutboundSender outboundSender) {
        this.app = app;
        this.config = config;
        this.outboundSender = outboundSender;
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
        int consecutiveEmptyPolls = 0;
        while (running.get()) {
            int totalWorkCount = 0;
            for (int batch = 0; batch < MAX_POLL_BATCHES_PER_CYCLE; batch++) {
                int workCount = library.poll(POLL_FRAGMENT_LIMIT);
                totalWorkCount += workCount;
                if (workCount < POLL_FRAGMENT_LIMIT) {
                    break;
                }
            }

            totalWorkCount += outboundSender.drain();

            if (totalWorkCount > 0) {
                consecutiveEmptyPolls = 0;
                continue;
            }

            consecutiveEmptyPolls++;
            if (consecutiveEmptyPolls <= EMPTY_POLLS_BEFORE_YIELD) {
                Thread.onSpinWait();
            } else if (consecutiveEmptyPolls <= EMPTY_POLLS_BEFORE_PARK) {
                Thread.yield();
            } else {
                LockSupport.parkNanos(PARK_NANOS);
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}

