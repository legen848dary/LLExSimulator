package com.llexsimulator.e2e;

import com.llexsimulator.SimulatorBootstrap;
import com.llexsimulator.client.FixDemoClientApplication;
import com.llexsimulator.client.FixDemoClientConfig;
import com.llexsimulator.client.NoOpQuickFixLogFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import quickfix.DefaultMessageFactory;
import quickfix.LogFactory;
import quickfix.MemoryStoreFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoClientEndToEndTest {

    private static final Duration LOGON_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SOAK_DURATION = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    private SocketInitiator initiator;
    private FixDemoClientApplication app;
    private SimulatorBootstrap bootstrap;

    @AfterEach
    void cleanup() {
        if (app != null) {
            app.close();
        }
        if (initiator != null) {
            try {
                initiator.stop(true);
            } catch (Exception ignored) {
            }
        }
        if (bootstrap != null) {
            bootstrap.stop();
        }

        clearProperty("fix.port");
        clearProperty("web.port");
        clearProperty("fix.demo.port");
        clearProperty("fix.demo.logDir");
        clearProperty("fix.demo.rawLoggingEnabled");
        clearProperty("fix.demo.rate");
    }

    @Test
    @Timeout(60)
    void demoClientStaysLoggedOnAndReceivesExecutionReportsForTenSeconds() throws Exception {
        int fixPort = findFreePort();
        int webPort = findFreePort();
        Path clientLogDir = tempDir.resolve("quickfixj-client");
        Files.createDirectories(clientLogDir);

        System.setProperty("fix.port", Integer.toString(fixPort));
        System.setProperty("web.port", Integer.toString(webPort));
        System.setProperty("fix.demo.port", Integer.toString(fixPort));
        System.setProperty("fix.demo.logDir", clientLogDir.toString());
        System.setProperty("fix.demo.rawLoggingEnabled", "false");
        System.setProperty("fix.demo.rate", "100");

        bootstrap = new SimulatorBootstrap();
        bootstrap.start();

        FixDemoClientConfig config = FixDemoClientConfig.from(new String[0]);
        Files.createDirectories(config.storeDir());
        Files.createDirectories(config.rawLogDir());

        app = new FixDemoClientApplication(config);
        SessionSettings settings = config.toSessionSettings();
        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = new NoOpQuickFixLogFactory();
        MessageFactory messageFactory = new DefaultMessageFactory();
        initiator = new SocketInitiator(app, storeFactory, settings, logFactory, messageFactory);

        initiator.start();
        app.start();

        assertTrue(app.awaitFirstLogon(LOGON_TIMEOUT), "Demo client never logged on to simulator");

        long deadline = System.nanoTime() + SOAK_DURATION.toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(app.isLoggedOn(), "Demo client logged out unexpectedly during soak run");
            assertEquals(0L, app.logoutCount(), "Demo client observed an unexpected logout during soak run");
            Thread.sleep(250L);
        }

        assertTrue(app.sentCount() >= 500, "Expected at least 500 orders to be sent during the 10 second soak run");
        assertTrue(app.execReportCount() > 0, "Expected execution reports from the simulator during the soak run");
        assertEquals(0L, app.rejectCount(), "Did not expect business rejects during the happy-path soak run");
        assertEquals(0L, app.sendFailureCount(), "Did not expect send failures during the soak run");

        System.out.printf(
                "DemoClientEndToEndTest SUCCESS: soak=%ss rate=%dmsg/s fixPort=%d webPort=%d logons=%d logouts=%d sent=%d execReports=%d rejects=%d sendFailures=%d%n",
                SOAK_DURATION.toSeconds(),
                config.ratePerSecond(),
                fixPort,
                webPort,
                app.logonCount(),
                app.logoutCount(),
                app.sentCount(),
                app.execReportCount(),
                app.rejectCount(),
                app.sendFailureCount());
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static void clearProperty(String key) {
        System.clearProperty(key);
    }
}

