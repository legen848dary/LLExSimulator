package com.llexsimulator.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("fix.port");
        System.clearProperty("wait.strategy");
    }

    @Test
    void prefersExternalConfigOverLocalConfig() throws IOException {
        Path externalConfig = tempDir.resolve("external.properties");
        Path localConfig = tempDir.resolve("local.properties");

        Files.writeString(externalConfig, "fix.port=9991\nwait.strategy=SLEEPING\nbenchmark.mode.enabled=true\n");
        Files.writeString(localConfig, "fix.port=9992\nwait.strategy=BUSY_SPIN\nbenchmark.mode.enabled=false\n");

        SimulatorConfig config = ConfigLoader.load(externalConfig, localConfig);

        assertEquals(9991, config.fixPort());
        assertEquals("SLEEPING", config.waitStrategy());
        assertTrue(config.benchmarkModeEnabled());
    }

    @Test
    void systemPropertiesOverrideLoadedConfig() throws IOException {
        Path localConfig = tempDir.resolve("local.properties");
        Files.writeString(localConfig, "fix.host=127.0.0.1\nfix.port=1234\n");
        System.setProperty("fix.port", "9999");

        SimulatorConfig config = ConfigLoader.load(tempDir.resolve("missing-external.properties"), localConfig);

        assertEquals("127.0.0.1", config.fixHost());
        assertEquals(9999, config.fixPort());
    }

    @Test
    void fallsBackToClasspathResourceWhenFilesAreMissing() {
        SimulatorConfig config = ConfigLoader.load(
                tempDir.resolve("missing-external.properties"),
                tempDir.resolve("missing-local.properties"));

        assertEquals(9880, config.fixPort());
        assertFalse(config.fixRawMessageLoggingEnabled());
        assertEquals("BUSY_SPIN", config.waitStrategy());
        assertEquals("aeron:ipc?term-length=8388608", config.artioLibraryAeronChannel());
        assertEquals("aeron:ipc?term-length=65536", config.metricsAeronChannel());
        assertFalse(config.benchmarkModeEnabled());
    }
}

