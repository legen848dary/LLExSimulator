package com.llexsimulator.client;

import quickfix.SessionID;
import quickfix.SessionSettings;

import java.nio.file.Path;

/**
 * Runtime configuration for the QuickFIX/J-based demo FIX initiator client.
 */
public record FixDemoClientConfig(
        String host,
        int port,
        String beginString,
        String senderCompId,
        String targetCompId,
        String defaultApplVerId,
        int heartBtIntSec,
        int reconnectIntervalSec,
        int ratePerSecond,
        String symbol,
        char side,
        double orderQty,
        double price,
        String logDir,
        boolean rawMessageLoggingEnabled
) {

    private static final String DEFAULT_RATE_PER_SECOND = "100";

    public static FixDemoClientConfig from(String[] args) {
        return new FixDemoClientConfig(
                stringProp("fix.demo.host", "localhost"),
                intProp("fix.demo.port", 9880),
                stringProp("fix.demo.beginString", "FIX.4.4"),
                stringProp("fix.demo.senderCompId", "CLIENT1"),
                stringProp("fix.demo.targetCompId", "LLEXSIM"),
                stringProp("fix.demo.defaultApplVerId", "FIX.4.4"),
                intProp("fix.demo.heartBtInt", 30),
                intProp("fix.demo.reconnectIntervalSec", 5),
                resolveRatePerSecond(args),
                stringProp("fix.demo.symbol", "AAPL"),
                parseSide(stringProp("fix.demo.side", "BUY")),
                positiveDouble(System.getProperty("fix.demo.orderQty", "100"), "fix.demo.orderQty"),
                positiveDouble(System.getProperty("fix.demo.price", "100.25"), "fix.demo.price"),
                stringProp("fix.demo.logDir", "logs/fix-demo-client/quickfixj"),
                Boolean.parseBoolean(System.getProperty("fix.demo.rawLoggingEnabled", "false"))
        );
    }

    public SessionID toSessionId() {
        validateSupportedBeginString();
        return new SessionID(beginString, senderCompId, targetCompId);
    }

    public SessionSettings toSessionSettings() {
        SessionSettings settings = new SessionSettings();
        SessionID sessionId = toSessionId();

        settings.setString(sessionId, "ConnectionType", "initiator");
        settings.setString(sessionId, "BeginString", beginString);
        settings.setString(sessionId, "SenderCompID", senderCompId);
        settings.setString(sessionId, "TargetCompID", targetCompId);
        settings.setString(sessionId, "SocketConnectHost", host);
        settings.setString(sessionId, "SocketConnectPort", Integer.toString(port));
        settings.setString(sessionId, "HeartBtInt", Integer.toString(heartBtIntSec));
        settings.setString(sessionId, "ReconnectInterval", Integer.toString(reconnectIntervalSec));
        settings.setString(sessionId, "StartTime", "00:00:00");
        settings.setString(sessionId, "EndTime", "00:00:00");
        settings.setString(sessionId, "TimeZone", "UTC");
        settings.setString(sessionId, "SocketNodelay", "Y");
        settings.setString(sessionId, "ResetOnLogon", "Y");
        settings.setString(sessionId, "ResetOnLogout", "Y");
        settings.setString(sessionId, "ResetOnDisconnect", "Y");
        settings.setString(sessionId, "UseDataDictionary", "N");
        settings.setString(sessionId, "ValidateIncomingMessage", "N");
        settings.setString(sessionId, "ValidateUserDefinedFields", "N");
        settings.setString(sessionId, "PersistMessages", "N");
        settings.setString(sessionId, "FileStorePath", storeDir().toString());
        settings.setString(sessionId, "FileLogPath", rawLogDir().toString());

        return settings;
    }

    private void validateSupportedBeginString() {
        if (!"FIX.4.4".equals(beginString)) {
            throw new IllegalArgumentException(
                    "The QuickFIX/J demo client currently supports fix.demo.beginString=FIX.4.4 only (was '" + beginString + "')");
        }
    }

    public Path storeDir() {
        return Path.of(logDir, "store");
    }

    public Path rawLogDir() {
        return Path.of(logDir, "messages");
    }

    static int resolveRatePerSecond(String[] args) {
        String argRate = args.length > 0 ? args[0] : null;
        String propertyRate = System.getProperty("fix.demo.rate");
        String envRate = System.getenv("FIX_DEMO_RATE");
        return resolveRatePerSecond(argRate, propertyRate, envRate);
    }

    static int resolveRatePerSecond(String argRate, String propertyRate, String envRate) {
        return positiveInt(firstNonBlank(argRate, propertyRate, envRate, DEFAULT_RATE_PER_SECOND), "fix.demo.rate");
    }

    private static String stringProp(String key, String defaultValue) {
        String value = System.getProperty(key, defaultValue).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Property '" + key + "' must not be blank");
        }
        return value;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        throw new IllegalArgumentException("Expected at least one non-blank value");
    }

    private static int intProp(String key, int defaultValue) {
        return positiveInt(System.getProperty(key, Integer.toString(defaultValue)), key);
    }

    private static int positiveInt(String raw, String key) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                throw new IllegalArgumentException("Property '" + key + "' must be > 0");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property '" + key + "' must be an integer: " + raw, e);
        }
    }

    private static double positiveDouble(String raw, String key) {
        try {
            double value = Double.parseDouble(raw.trim());
            if (value <= 0.0d) {
                throw new IllegalArgumentException("Property '" + key + "' must be > 0");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property '" + key + "' must be a number: " + raw, e);
        }
    }

    private static char parseSide(String raw) {
        return switch (raw.trim().toUpperCase()) {
            case "BUY", "1" -> '1';
            case "SELL", "2" -> '2';
            default -> throw new IllegalArgumentException(
                    "Unsupported fix.demo.side='" + raw + "' (supported: BUY, SELL)");
        };
    }
}

