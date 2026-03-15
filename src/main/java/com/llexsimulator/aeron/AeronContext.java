package com.llexsimulator.aeron;

import io.aeron.Aeron;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Manages the embedded Aeron {@link MediaDriver} and {@link Aeron} client.
 *
 * <p>The media driver defaults to {@code DEDICATED} threading mode with
 * aggressive idle strategies for sender/receiver, but now honors Aeron-related
 * system properties (for example {@code -Daeron.threading.mode=SHARED} and
 * {@code -Daeron.shared.idle.strategy=backoff}) so constrained Docker hosts can
 * trade a little latency for much lower CPU pressure.
 */
public final class AeronContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AeronContext.class);
    private static final String ARCHIVE_CONTROL_CHANNEL = "aeron:udp?endpoint=localhost:8010";
    private static final String ARCHIVE_REPLICATION_CHANNEL = "aeron:udp?endpoint=localhost:0";
    private static final String ARCHIVE_CONTROL_REQUEST_CHANNEL = "aeron:ipc?term-length=65536";
    private static final String ARCHIVE_RECORDING_EVENTS_CHANNEL = "aeron:ipc?term-length=65536";

    private final MediaDriver mediaDriver;
    private final ArchivingMediaDriver archivingMediaDriver;
    private final Aeron aeron;

    public AeronContext(String aeronDir, boolean archiveEnabled) {
        ThreadingMode threadingMode = resolveThreadingMode(
                System.getProperty("aeron.threading.mode"), ThreadingMode.DEDICATED);

        MediaDriver.Context mdCtx = new MediaDriver.Context()
                .aeronDirectoryName(aeronDir)
                .threadingMode(threadingMode)
                .ipcTermBufferLength(AeronRuntimeTuning.DEFAULT_ARTIO_IPC_TERM_LENGTH_BYTES)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);

        applyIdleStrategies(mdCtx, threadingMode);

        if (archiveEnabled) {
            String archiveDir = Path.of(aeronDir).resolveSibling(Path.of(aeronDir).getFileName() + "-archive").toString();
            Archive.Context archiveCtx = new Archive.Context()
                    .aeronDirectoryName(aeronDir)
                    .archiveDirectoryName(archiveDir)
                    .deleteArchiveOnStart(true)
                    .controlChannel(ARCHIVE_CONTROL_CHANNEL)
                    .localControlChannel(ARCHIVE_CONTROL_REQUEST_CHANNEL)
                    .replicationChannel(ARCHIVE_REPLICATION_CHANNEL)
                    .recordingEventsChannel(ARCHIVE_RECORDING_EVENTS_CHANNEL)
                    .threadingMode(ArchiveThreadingMode.SHARED);

            this.archivingMediaDriver = ArchivingMediaDriver.launch(mdCtx, archiveCtx);
            this.mediaDriver = archivingMediaDriver.mediaDriver();
            log.info("Aeron ArchivingMediaDriver launched: dir={} archiveDir={} threadingMode={} driverIdle={}",
                    mediaDriver.aeronDirectoryName(), archiveDir, threadingMode, describeIdleStrategies(threadingMode));
        } else {
            this.archivingMediaDriver = null;
            this.mediaDriver = MediaDriver.launchEmbedded(mdCtx);
            log.info("Aeron MediaDriver launched: dir={} threadingMode={} driverIdle={}",
                    mediaDriver.aeronDirectoryName(), threadingMode, describeIdleStrategies(threadingMode));
        }

        Aeron.Context aeronCtx = new Aeron.Context()
                .aeronDirectoryName(mediaDriver.aeronDirectoryName());
        this.aeron = Aeron.connect(aeronCtx);
        log.info("Aeron client connected");
    }

    public Aeron getAeron() { return aeron; }

    static ThreadingMode resolveThreadingMode(String raw, ThreadingMode defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        try {
            return ThreadingMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unsupported aeron.threading.mode='{}' — falling back to {}", raw, defaultValue);
            return defaultValue;
        }
    }

    static IdleStrategy resolveIdleStrategy(String raw, IdleStrategy defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "noop", "no_op" -> new NoOpIdleStrategy();
            case "busyspin", "busy_spin" -> new BusySpinIdleStrategy();
            case "backoff", "back_off" -> new BackoffIdleStrategy();
            case "sleep", "sleeping" -> new SleepingIdleStrategy();
            case "yield", "yielding" -> new YieldingIdleStrategy();
            default -> {
                log.warn("Unsupported Aeron idle strategy '{}' — falling back to {}", raw,
                        defaultValue.getClass().getSimpleName());
                yield defaultValue;
            }
        };
    }

    private static void applyIdleStrategies(MediaDriver.Context context, ThreadingMode threadingMode) {
        if (threadingMode == ThreadingMode.DEDICATED) {
            context.conductorIdleStrategy(resolveIdleStrategy(
                    System.getProperty("aeron.conductor.idle.strategy"), new BusySpinIdleStrategy()));
            context.senderIdleStrategy(resolveIdleStrategy(
                    System.getProperty("aeron.sender.idle.strategy"), new NoOpIdleStrategy()));
            context.receiverIdleStrategy(resolveIdleStrategy(
                    System.getProperty("aeron.receiver.idle.strategy"), new NoOpIdleStrategy()));
            return;
        }

        IdleStrategy sharedIdle = resolveIdleStrategy(
                System.getProperty("aeron.shared.idle.strategy"), new BackoffIdleStrategy());
        context.sharedIdleStrategy(sharedIdle);
        context.sharedNetworkIdleStrategy(resolveIdleStrategy(
                System.getProperty("aeron.shared.network.idle.strategy", System.getProperty("aeron.shared.idle.strategy")),
                new BackoffIdleStrategy()));
        context.conductorIdleStrategy(resolveIdleStrategy(
                System.getProperty("aeron.conductor.idle.strategy"), new BackoffIdleStrategy()));
    }

    private static String describeIdleStrategies(ThreadingMode threadingMode) {
        if (threadingMode == ThreadingMode.DEDICATED) {
            return "conductor=" + resolveIdleStrategy(System.getProperty("aeron.conductor.idle.strategy"), new BusySpinIdleStrategy()).getClass().getSimpleName()
                    + ",sender=" + resolveIdleStrategy(System.getProperty("aeron.sender.idle.strategy"), new NoOpIdleStrategy()).getClass().getSimpleName()
                    + ",receiver=" + resolveIdleStrategy(System.getProperty("aeron.receiver.idle.strategy"), new NoOpIdleStrategy()).getClass().getSimpleName();
        }

        return "shared=" + resolveIdleStrategy(System.getProperty("aeron.shared.idle.strategy"), new BackoffIdleStrategy()).getClass().getSimpleName()
                + ",sharedNetwork=" + resolveIdleStrategy(
                System.getProperty("aeron.shared.network.idle.strategy", System.getProperty("aeron.shared.idle.strategy")),
                new BackoffIdleStrategy()).getClass().getSimpleName()
                + ",conductor=" + resolveIdleStrategy(System.getProperty("aeron.conductor.idle.strategy"), new BackoffIdleStrategy()).getClass().getSimpleName();
    }

    @Override
    public void close() {
        log.info("Shutting down Aeron context");
        aeron.close();
        if (archivingMediaDriver != null) {
            archivingMediaDriver.close();
        } else {
            mediaDriver.close();
        }
    }
}

