package com.llexsimulator.aeron;

import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the embedded Aeron {@link MediaDriver} and {@link Aeron} client.
 *
 * <p>The media driver is launched in {@code DEDICATED} threading mode with
 * no-op idle strategies for sender and receiver — maximum throughput and
 * minimum latency at the cost of higher CPU utilisation.
 */
public final class AeronContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AeronContext.class);

    private final MediaDriver mediaDriver;
    private final Aeron       aeron;

    public AeronContext(String aeronDir) {
        MediaDriver.Context mdCtx = new MediaDriver.Context()
                .aeronDirectoryName(aeronDir)
                .threadingMode(ThreadingMode.DEDICATED)
                .ipcTermBufferLength(AeronRuntimeTuning.DEFAULT_ARTIO_IPC_TERM_LENGTH_BYTES)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .conductorIdleStrategy(new BusySpinIdleStrategy())
                .senderIdleStrategy(new NoOpIdleStrategy())
                .receiverIdleStrategy(new NoOpIdleStrategy());

        this.mediaDriver = MediaDriver.launchEmbedded(mdCtx);
        log.info("Aeron MediaDriver launched: dir={}", mediaDriver.aeronDirectoryName());

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
        mediaDriver.close();
    }
}

