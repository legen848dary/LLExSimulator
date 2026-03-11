package com.llexsimulator.config;

/**
 * Immutable configuration record loaded once at startup.
 * All values are primitives or Strings — no heap churn after construction.
 */
public record SimulatorConfig(
        String fixHost,
        int    fixPort,
        String fixLogDir,
        int    webPort,
        String aeronDir,
        int    ringBufferSize,
        String waitStrategy,
        int    orderPoolSize,
        int    metricsPublishInterval
) {
    /** Default configuration with sensible low-latency values. */
    public static SimulatorConfig defaults() {
        return new SimulatorConfig(
                "0.0.0.0", 9880, "logs/quickfixj",
                8080, "/dev/shm/aeron-llexsim",
                131072, "BUSY_SPIN", 16384, 500
        );
    }
}

