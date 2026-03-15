package com.llexsimulator.aeron;

import com.llexsimulator.config.SimulatorConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AeronRuntimeTuningTest {

    @Test
    void fallsBackFromDevShmWhenUsableSpaceIsTooSmall() {
        SimulatorConfig base = new SimulatorConfig(
                "0.0.0.0",
                9880,
                "logs/quickfixj",
                false,
                8080,
                "/dev/shm/aeron-llexsim",
                "aeron:ipc",
                "aeron:ipc",
                131072,
                "BUSY_SPIN",
                131072,
                500,
                false);

        SimulatorConfig resolved = AeronRuntimeTuning.resolve(base, 64L * 1024L * 1024L);

        assertEquals("/tmp/aeron-llexsim", resolved.aeronDir());
        assertEquals("aeron:ipc?term-length=8388608", resolved.artioLibraryAeronChannel());
        assertEquals("aeron:ipc?term-length=65536", resolved.metricsAeronChannel());
    }

    @Test
    void preservesConfiguredDirWhenSharedMemoryIsLargeEnough() {
        SimulatorConfig base = new SimulatorConfig(
                "0.0.0.0",
                9880,
                "logs/quickfixj",
                false,
                8080,
                "/dev/shm/aeron-llexsim",
                "aeron:ipc?term-length=16777216",
                "aeron:ipc?term-length=131072",
                131072,
                "BUSY_SPIN",
                131072,
                500,
                false);

        SimulatorConfig resolved = AeronRuntimeTuning.resolve(base, 512L * 1024L * 1024L);

        String expectedDir = Files.isDirectory(Path.of("/dev/shm"))
                ? "/dev/shm/aeron-llexsim"
                : "/tmp/aeron-llexsim";
        assertEquals(expectedDir, resolved.aeronDir());
        assertEquals("aeron:ipc?term-length=16777216", resolved.artioLibraryAeronChannel());
        assertEquals("aeron:ipc?term-length=131072", resolved.metricsAeronChannel());
    }

    @Test
    void appendsTermLengthToExistingIpcUriParameters() {
        assertEquals(
                "aeron:ipc?alias=metrics|term-length=65536",
                AeronRuntimeTuning.ensureIpcTermLength("aeron:ipc?alias=metrics", 65536));
    }

    @Test
    void fallsBackWhenDevShmMountIsUnavailable() {
        String resolved = AeronRuntimeTuning.resolveAeronDir("/dev/shm/aeron-llexsim", Long.MAX_VALUE);
        String expected = Files.isDirectory(Path.of("/dev/shm"))
                ? "/dev/shm/aeron-llexsim"
                : "/tmp/aeron-llexsim";
        assertEquals(expected, resolved);
    }
}

