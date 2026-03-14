package com.llexsimulator.e2e;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llexsimulator.SimulatorBootstrap;
import com.llexsimulator.client.FixDemoClientApplication;
import com.llexsimulator.client.FixDemoClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.LogFactory;
import quickfix.MemoryStoreFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

import java.io.IOException;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoClientEndToEndTest {

    private static final Duration LOGON_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SOAK_DURATION = Duration.ofSeconds(60);
    private static final Duration DISCONNECT_TIMEOUT = Duration.ofSeconds(10);

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
        clearProperty("fix.raw.message.logging.enabled");
        clearProperty("fix.demo.rate");
    }

    @Test
    @Timeout(90)
    void demoClientStaysLoggedOnAndReceivesExecutionReportsForSixtySeconds() throws Exception {
        int fixPort = findFreePort();
        int webPort = findFreePort();
        Path clientLogDir = tempDir.resolve("quickfixj-client");
        Files.createDirectories(clientLogDir);

        System.setProperty("fix.port", Integer.toString(fixPort));
        System.setProperty("web.port", Integer.toString(webPort));
        System.setProperty("fix.demo.port", Integer.toString(fixPort));
        System.setProperty("fix.demo.logDir", clientLogDir.toString());
        System.setProperty("fix.demo.rawLoggingEnabled", "false");
        System.setProperty("fix.raw.message.logging.enabled", "false");
        System.setProperty("fix.demo.rate", "2000");

        bootstrap = new SimulatorBootstrap();
        bootstrap.start();

        FixDemoClientConfig config = FixDemoClientConfig.from(new String[0]);
        Files.createDirectories(config.storeDir());
        Files.createDirectories(config.rawLogDir());

        app = new FixDemoClientApplication(config);
        SessionSettings settings = config.toSessionSettings();
        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = new FileLogFactory(settings);
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

        assertTrue(app.sentCount() >= 5_000, "Expected at least 5,000 orders to be sent during the 60 second soak run");
        assertTrue(app.execReportCount() > 0, "Expected execution reports from the simulator during the soak run");
        assertEquals(0L, app.rejectCount(), "Did not expect business rejects during the happy-path soak run");
        assertEquals(0L, app.sendFailureCount(), "Did not expect send failures during the soak run");

        initiator.stop(true);

        Instant disconnectDeadline = Instant.now().plus(DISCONNECT_TIMEOUT);
        while (Instant.now().isBefore(disconnectDeadline) && app.logoutCount() == 0L) {
            Thread.sleep(100L);
        }

        assertTrue(app.logoutCount() > 0L, "Expected the client to observe a logout after initiator shutdown");

        List<Map<String, Object>> disconnects = fetchRecentDisconnects(webPort);
        assertFalse(disconnects.isEmpty(), "Expected at least one recent disconnect to be archived by the simulator");
        Map<String, Object> latestDisconnect = disconnects.getFirst();
        assertEquals("FIX.4.4:LLEXSIM->CLIENT1#1", latestDisconnect.get("sessionKey"));
        assertTrue(((Number)latestDisconnect.get("disconnectCount")).longValue() >= 1L,
                "Expected the archived disconnect snapshot to include disconnectCount >= 1");
        assertTrue(((String)latestDisconnect.get("lastDisconnectReason")).length() > 0,
                "Expected the archived disconnect snapshot to capture a disconnect reason");
        assertFalse("NONE".equals(latestDisconnect.get("lastDisconnectReason")),
                "Expected the archived disconnect snapshot to contain a real disconnect reason");
        assertTrue(((List<?>)latestDisconnect.get("recentEvents")).stream()
                        .map(String::valueOf)
                        .anyMatch(event -> event.contains("DISCONNECT reason=")),
                "Expected recentEvents to include the disconnect transition");

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

    private static List<Map<String, Object>> fetchRecentDisconnects(int webPort) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + webPort + "/api/sessions/recent-disconnects?limit=5"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Expected recent disconnect diagnostics endpoint to return HTTP 200");
        return new ObjectMapper().readValue(response.body(), new TypeReference<>() {});
    }

    private static void clearProperty(String key) {
        System.clearProperty(key);
    }
}

