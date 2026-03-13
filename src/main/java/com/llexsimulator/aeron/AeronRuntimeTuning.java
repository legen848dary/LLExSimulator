package com.llexsimulator.aeron;

import com.llexsimulator.config.SimulatorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Applies Aeron runtime defaults that keep startup reliable on constrained
 * shared-memory environments while preserving low-latency IPC transport.
 */
public final class AeronRuntimeTuning {

    public static final int DEFAULT_ARTIO_IPC_TERM_LENGTH_BYTES = 8 * 1024 * 1024;
    public static final int DEFAULT_METRICS_IPC_TERM_LENGTH_BYTES = 64 * 1024;
    public static final String DEFAULT_ARTIO_LIBRARY_CHANNEL =
            "aeron:ipc?term-length=" + DEFAULT_ARTIO_IPC_TERM_LENGTH_BYTES;
    public static final String DEFAULT_METRICS_CHANNEL =
            "aeron:ipc?term-length=" + DEFAULT_METRICS_IPC_TERM_LENGTH_BYTES;
    static final String DEV_SHM_PREFIX = "/dev/shm";
    static final String FALLBACK_AERON_DIR = "/tmp/aeron-llexsim";
    static final long MIN_DEV_SHM_USABLE_BYTES = 128L * 1024L * 1024L;

    private static final Logger log = LoggerFactory.getLogger(AeronRuntimeTuning.class);

    private AeronRuntimeTuning() {}

    public static SimulatorConfig resolve(SimulatorConfig config) {
        long usableSpace = detectUsableSpace(config.aeronDir());
        return resolve(config, usableSpace);
    }

    static SimulatorConfig resolve(SimulatorConfig config, long usableSpaceBytes) {
        String resolvedAeronDir = resolveAeronDir(config.aeronDir(), usableSpaceBytes);
        String resolvedArtioChannel = ensureIpcTermLength(
                config.artioLibraryAeronChannel(), DEFAULT_ARTIO_IPC_TERM_LENGTH_BYTES);
        String resolvedMetricsChannel = ensureIpcTermLength(
                config.metricsAeronChannel(), DEFAULT_METRICS_IPC_TERM_LENGTH_BYTES);

        if (!resolvedAeronDir.equals(config.aeronDir())) {
            log.warn(
                    "Configured Aeron directory '{}' has only {} bytes usable; falling back to '{}' for reliable startup",
                    config.aeronDir(), usableSpaceBytes, resolvedAeronDir);
        }

        if (!resolvedArtioChannel.equals(config.artioLibraryAeronChannel()) ||
                !resolvedMetricsChannel.equals(config.metricsAeronChannel())) {
            log.info("Resolved Aeron channels: artioLibraryChannel={} metricsChannel={}",
                    resolvedArtioChannel, resolvedMetricsChannel);
        }

        return config.withAeronSettings(resolvedAeronDir, resolvedArtioChannel, resolvedMetricsChannel);
    }

    static String resolveAeronDir(String configuredDir, long usableSpaceBytes) {
        if (configuredDir == null || configuredDir.isBlank()) {
            return FALLBACK_AERON_DIR;
        }

        if (configuredDir.startsWith(DEV_SHM_PREFIX) && !Files.isDirectory(Path.of(DEV_SHM_PREFIX))) {
            return FALLBACK_AERON_DIR;
        }

        if (configuredDir.startsWith(DEV_SHM_PREFIX) &&
                usableSpaceBytes >= 0 &&
                usableSpaceBytes < MIN_DEV_SHM_USABLE_BYTES) {
            return FALLBACK_AERON_DIR;
        }

        return configuredDir;
    }

    static String ensureIpcTermLength(String channel, int defaultTermLengthBytes) {
        if (channel == null || channel.isBlank()) {
            return "aeron:ipc?term-length=" + defaultTermLengthBytes;
        }

        if (!channel.startsWith("aeron:ipc") || channel.contains("term-length=")) {
            return channel;
        }

        return channel.contains("?")
                ? channel + "|term-length=" + defaultTermLengthBytes
                : channel + "?term-length=" + defaultTermLengthBytes;
    }

    private static long detectUsableSpace(String aeronDir) {
        try {
            Path probePath = resolveExistingProbePath(Path.of(aeronDir));
            FileStore fileStore = Files.getFileStore(probePath);
            return fileStore.getUsableSpace();
        } catch (IOException | RuntimeException e) {
            log.warn("Unable to determine usable space for Aeron directory '{}': {}", aeronDir, e.toString());
            return -1L;
        }
    }

    private static Path resolveExistingProbePath(Path path) {
        Path probe = path;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        return probe != null ? probe : Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
    }
}

