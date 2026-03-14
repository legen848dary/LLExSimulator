package com.llexsimulator.aeron;

import io.aeron.Aeron;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Manages the embedded Aeron {@link MediaDriver} and {@link Aeron} client.
 *
 * <p>The media driver is launched in {@code DEDICATED} threading mode with
 * no-op idle strategies for sender and receiver — maximum throughput and
 * minimum latency at the cost of higher CPU utilisation.
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
        MediaDriver.Context mdCtx = new MediaDriver.Context()
                .aeronDirectoryName(aeronDir)
                .threadingMode(ThreadingMode.DEDICATED)
                .ipcTermBufferLength(AeronRuntimeTuning.DEFAULT_ARTIO_IPC_TERM_LENGTH_BYTES)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .conductorIdleStrategy(new BusySpinIdleStrategy())
                .senderIdleStrategy(new NoOpIdleStrategy())
                .receiverIdleStrategy(new NoOpIdleStrategy());

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
            log.info("Aeron ArchivingMediaDriver launched: dir={} archiveDir={}", mediaDriver.aeronDirectoryName(), archiveDir);
        } else {
            this.archivingMediaDriver = null;
            this.mediaDriver = MediaDriver.launchEmbedded(mdCtx);
            log.info("Aeron MediaDriver launched: dir={}", mediaDriver.aeronDirectoryName());
        }

        Aeron.Context aeronCtx = new Aeron.Context()
                .aeronDirectoryName(mediaDriver.aeronDirectoryName());
        this.aeron = Aeron.connect(aeronCtx);
        log.info("Aeron client connected");
    }

    public Aeron getAeron() { return aeron; }

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

