package com.llexsimulator.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;

import java.io.InputStream;

/**
 * Manages the QuickFIX/J {@link SocketAcceptor} lifecycle.
 *
 * <p>Reads session configuration from {@code quickfixj.cfg} on the classpath.
 * Supports FIX 4.2, 4.4, 5.0, 5.0SP2, and FIXT 1.1.
 */
public final class FixEngineManager {

    private static final Logger log = LoggerFactory.getLogger(FixEngineManager.class);

    private final SocketAcceptor acceptor;

    public FixEngineManager(FixSessionApplication app) throws Exception {
        SessionSettings settings = loadSettings();
        MessageStoreFactory storeFactory = new FileStoreFactory(settings);
        LogFactory          logFactory   = new FileLogFactory(settings);
        MessageFactory      msgFactory   = new DefaultMessageFactory();

        this.acceptor = new SocketAcceptor(app, storeFactory, settings, logFactory, msgFactory);
        log.info("QuickFIX/J SocketAcceptor created");
    }

    private static SessionSettings loadSettings() throws ConfigError {
        try (InputStream in = FixEngineManager.class.getResourceAsStream("/quickfixj.cfg")) {
            if (in == null) {
                throw new ConfigError("quickfixj.cfg not found on classpath");
            }
            return new SessionSettings(in);
        } catch (java.io.IOException e) {
            throw new ConfigError("Failed to read quickfixj.cfg: " + e.getMessage());
        }
    }

    public void start() throws RuntimeError, ConfigError {
        acceptor.start();
        log.info("FIX acceptor started");
    }

    public void stop() {
        acceptor.stop();
        log.info("FIX acceptor stopped");
    }

    public boolean isLoggedOn() {
        return acceptor.isLoggedOn();
    }

    public int getSessionCount() {
        return acceptor.getManagedSessions().size();
    }
}

