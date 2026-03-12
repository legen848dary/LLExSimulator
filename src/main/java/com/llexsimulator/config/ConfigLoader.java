package com.llexsimulator.config;

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

    private ConfigLoader() {}

    public static SimulatorConfig load() {
        Properties props = new Properties();
        if (Files.isRegularFile(EXTERNAL_CONFIG_PATH)) {
            try (InputStream in = Files.newInputStream(EXTERNAL_CONFIG_PATH)) {
                props.load(in);
                log.info("Loaded simulator.properties from {}", EXTERNAL_CONFIG_PATH);
            } catch (IOException e) {
                log.error("Failed to load simulator.properties from {}", EXTERNAL_CONFIG_PATH, e);
            }
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
                props.getProperty("fix.host",            "0.0.0.0"),
                Integer.parseInt(props.getProperty("fix.port",             "9880")),
                props.getProperty("fix.log.dir",         "logs/quickfixj"),
                Integer.parseInt(props.getProperty("web.port",             "8080")),
                props.getProperty("aeron.dir",           "/dev/shm/aeron-llexsim"),
                Integer.parseInt(props.getProperty("ring.buffer.size",     "131072")),
                props.getProperty("wait.strategy",       "BUSY_SPIN"),
                Integer.parseInt(props.getProperty("order.pool.size",      "16384")),
                Integer.parseInt(props.getProperty("metrics.publish.interval", "500"))
        );
    }
}

