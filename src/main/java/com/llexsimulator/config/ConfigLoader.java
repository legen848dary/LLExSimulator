package com.llexsimulator.config;

import com.llexsimulator.aeron.AeronRuntimeTuning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads {@link SimulatorConfig} from {@code /app/config/simulator.properties}
 * when present, otherwise falls back to {@code simulator.properties} on the
 * classpath. Missing properties fall back to built-in defaults.
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final Path EXTERNAL_CONFIG_PATH = Path.of("/app/config/simulator.properties");
    private static final Path LOCAL_CONFIG_PATH = Path.of("config/simulator.properties");

    private ConfigLoader() {}

    public static SimulatorConfig load() {
        return load(EXTERNAL_CONFIG_PATH, LOCAL_CONFIG_PATH);
    }

    static SimulatorConfig load(Path externalConfigPath, Path localConfigPath) {
        Properties props = new Properties();
        if (Files.isRegularFile(externalConfigPath)) {
            loadFrom(props, externalConfigPath, "external container mount");
        } else if (Files.isRegularFile(localConfigPath)) {
            loadFrom(props, localConfigPath, "local workspace config");
        } else {
            try (InputStream in = ConfigLoader.class.getResourceAsStream("/simulator.properties")) {
                if (in != null) {
                    props.load(in);
                    log.info("Loaded simulator.properties from classpath");
                } else {
                    log.warn("simulator.properties not found on classpath — using defaults");
                }
            } catch (IOException e) {
                log.error("Failed to load simulator.properties", e);
            }
        }

        return new SimulatorConfig(
                property(props, "fix.host", "0.0.0.0"),
                Integer.parseInt(property(props, "fix.port", "9880")),
                property(props, "fix.log.dir", "logs/quickfixj"),
                Boolean.parseBoolean(property(props, "fix.raw.message.logging.enabled", "false")),
                Integer.parseInt(property(props, "web.port", "8080")),
                property(props, "aeron.dir", "/tmp/aeron-llexsim"),
                property(props, "artio.library.aeron.channel", AeronRuntimeTuning.DEFAULT_ARTIO_LIBRARY_CHANNEL),
                property(props, "metrics.aeron.channel", AeronRuntimeTuning.DEFAULT_METRICS_CHANNEL),
                Integer.parseInt(property(props, "ring.buffer.size", "131072")),
                property(props, "wait.strategy", "BUSY_SPIN"),
                Integer.parseInt(property(props, "order.pool.size", "131072")),
                Integer.parseInt(property(props, "metrics.publish.interval", "500")),
                Boolean.parseBoolean(property(props, "benchmark.mode.enabled", "false"))
        );
    }

    private static void loadFrom(Properties props, Path path, String sourceLabel) {
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            log.info("Loaded simulator.properties from {} ({})", path, sourceLabel);
        } catch (IOException e) {
            log.error("Failed to load simulator.properties from {}", path, e);
        }
    }

    private static String property(Properties props, String key, String defaultValue) {
        return System.getProperty(key, props.getProperty(key, defaultValue));
    }
}

