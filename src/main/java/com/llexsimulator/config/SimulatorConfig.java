package com.llexsimulator.config;

import com.llexsimulator.aeron.AeronRuntimeTuning;

/**
 * Immutable configuration record loaded once at startup.
 * All values are primitives or Strings — no heap churn after construction.
 */
public record SimulatorConfig(
        String fixHost,
        int    fixPort,
        String fixLogDir,
        boolean fixRawMessageLoggingEnabled,
        int    webPort,
        String aeronDir,
        String artioLibraryAeronChannel,
        String metricsAeronChannel,
        int    ringBufferSize,
        String waitStrategy,
        int    orderPoolSize,
        int    metricsPublishInterval,
        boolean benchmarkModeEnabled
) {
    /** Default configuration with sensible low-latency values. */
    public static SimulatorConfig defaults() {
        return new SimulatorConfig(
                "0.0.0.0", 9880, "logs/quickfixj", false,
                8080, "/tmp/aeron-llexsim",
                AeronRuntimeTuning.DEFAULT_ARTIO_LIBRARY_CHANNEL,
                AeronRuntimeTuning.DEFAULT_METRICS_CHANNEL,
                131072, "BUSY_SPIN", 131072, 500, false
        );
    }

    public SimulatorConfig withAeronSettings(
            String aeronDir,
            String artioLibraryAeronChannel,
            String metricsAeronChannel) {
        return new SimulatorConfig(
                fixHost,
                fixPort,
                fixLogDir,
                fixRawMessageLoggingEnabled,
                webPort,
                aeronDir,
                artioLibraryAeronChannel,
                metricsAeronChannel,
                ringBufferSize,
                waitStrategy,
                orderPoolSize,
                metricsPublishInterval,
                benchmarkModeEnabled);
    }
}

