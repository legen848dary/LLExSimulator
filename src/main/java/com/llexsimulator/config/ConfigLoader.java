package com.llexsimulator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads {@link SimulatorConfig} from {@code simulator.properties} on the classpath.
 * Falls back to defaults if any property is missing.
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private ConfigLoader() {}

    public static SimulatorConfig load() {
        Properties props = new Properties();
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

