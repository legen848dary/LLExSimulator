package com.llexsimulator.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import quickfix.SessionID;
import quickfix.SessionSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixDemoClientConfigTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("fix.demo.beginString");
        System.clearProperty("fix.demo.logDir");
        System.clearProperty("fix.demo.host");
        System.clearProperty("fix.demo.port");
        System.clearProperty("fix.demo.senderCompId");
        System.clearProperty("fix.demo.targetCompId");
    }

    @Test
    void buildsQuickFixSessionSettingsAndDirectories() throws Exception {
        System.setProperty("fix.demo.host", "127.0.0.1");
        System.setProperty("fix.demo.port", "12345");
        System.setProperty("fix.demo.senderCompId", "BUY1");
        System.setProperty("fix.demo.targetCompId", "SELL1");
        System.setProperty("fix.demo.logDir", "logs/fix-demo-client/test-qfj");

        FixDemoClientConfig config = FixDemoClientConfig.from(new String[]{"250"});
        SessionID sessionId = config.toSessionId();
        SessionSettings settings = config.toSessionSettings();

        assertEquals("FIX.4.4", sessionId.getBeginString());
        assertEquals("BUY1", sessionId.getSenderCompID());
        assertEquals("SELL1", sessionId.getTargetCompID());
        assertEquals("127.0.0.1", settings.getString(sessionId, "SocketConnectHost"));
        assertEquals("12345", settings.getString(sessionId, "SocketConnectPort"));
        assertEquals("250", Integer.toString(config.ratePerSecond()));
        assertEquals("logs/fix-demo-client/test-qfj/store", config.storeDir().toString());
        assertEquals("logs/fix-demo-client/test-qfj/messages", config.rawLogDir().toString());
        assertFalse(config.rawMessageLoggingEnabled());
    }

    @Test
    void resolvesRateUsingCliThenPropertyThenEnvThenDefault() {
        assertEquals(700, FixDemoClientConfig.resolveRatePerSecond("700", "600", "500"));
        assertEquals(600, FixDemoClientConfig.resolveRatePerSecond(null, "600", "500"));
        assertEquals(500, FixDemoClientConfig.resolveRatePerSecond(null, null, "500"));
        assertEquals(100, FixDemoClientConfig.resolveRatePerSecond(null, null, null));
    }

    @Test
    void rejectsUnsupportedBeginString() {
        System.setProperty("fix.demo.beginString", "FIX.4.2");

        FixDemoClientConfig config = FixDemoClientConfig.from(new String[0]);

        assertThrows(IllegalArgumentException.class, config::toSessionId);
    }
}

