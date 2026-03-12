package com.llexsimulator.client;

import quickfix.ConfigError;
import quickfix.SessionSettings;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Runtime configuration for the demo FIX initiator client.
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
        String quickFixLogDir
) {

    public static FixDemoClientConfig from(String[] args) {
        String argRate = args.length > 0 ? args[0] : null;

        return new FixDemoClientConfig(
                stringProp("fix.demo.host", "localhost"),
                intProp("fix.demo.port", 9880),
                stringProp("fix.demo.beginString", "FIX.4.2"),
                stringProp("fix.demo.senderCompId", "CLIENT1"),
                stringProp("fix.demo.targetCompId", "LLEXSIM"),
                stringProp("fix.demo.defaultApplVerId", "FIX.5.0"),
                intProp("fix.demo.heartBtInt", 30),
                intProp("fix.demo.reconnectIntervalSec", 5),
                positiveInt(argRate != null ? argRate : System.getProperty("fix.demo.rate", "100"), "fix.demo.rate"),
                stringProp("fix.demo.symbol", "AAPL"),
                parseSide(stringProp("fix.demo.side", "BUY")),
                positiveDouble(System.getProperty("fix.demo.orderQty", "100"), "fix.demo.orderQty"),
                positiveDouble(System.getProperty("fix.demo.price", "100.25"), "fix.demo.price"),
                stringProp("fix.demo.logDir", "logs/fix-demo-client/quickfixj")
        );
    }

    public SessionSettings toSessionSettings() throws ConfigError {
        StringBuilder cfg = new StringBuilder(512)
                .append("[DEFAULT]\n")
                .append("ConnectionType=initiator\n")
                .append("HeartBtInt=").append(heartBtIntSec).append('\n')
                .append("ReconnectInterval=").append(reconnectIntervalSec).append('\n')
                .append("StartTime=00:00:00\n")
                .append("EndTime=00:00:00\n")
                .append("TimeZone=UTC\n")
                .append("ResetOnLogon=Y\n")
                .append("ResetOnLogout=Y\n")
                .append("ResetOnDisconnect=Y\n")
                .append("UseDataDictionary=Y\n")
                .append("ValidateUserDefinedFields=N\n")
                .append("ValidateIncomingMessage=N\n")
                .append("SocketNodelay=Y\n")
                .append("FileLogPath=").append(quickFixLogDir).append("/session\n\n")
                .append("[SESSION]\n")
                .append("BeginString=").append(beginString).append('\n')
                .append("SenderCompID=").append(senderCompId).append('\n')
                .append("TargetCompID=").append(targetCompId).append('\n')
                .append("SocketConnectHost=").append(host).append('\n')
                .append("SocketConnectPort=").append(port).append('\n');

        switch (beginString) {
            case "FIX.4.2" -> cfg.append("DataDictionary=FIX42.xml\n");
            case "FIX.4.4" -> cfg.append("DataDictionary=FIX44.xml\n");
            case "FIXT.1.1" -> cfg.append("DefaultApplVerID=").append(defaultApplVerId).append('\n')
                    .append("TransportDataDictionary=FIXT11.xml\n")
                    .append("AppDataDictionary=").append(appDataDictionary()).append('\n');
            default -> throw new IllegalArgumentException(
                    "Unsupported fix.demo.beginString='" + beginString + "' (supported: FIX.4.2, FIX.4.4, FIXT.1.1)");
        }

        return new SessionSettings(new ByteArrayInputStream(cfg.toString().getBytes(StandardCharsets.US_ASCII)));
    }

    private String appDataDictionary() {
        return switch (defaultApplVerId) {
            case "FIX.5.0", "7" -> "FIX50.xml";
            case "FIX.5.0SP2", "9" -> "FIX50SP2.xml";
            default -> throw new IllegalArgumentException(
                    "Unsupported fix.demo.defaultApplVerId='" + defaultApplVerId + "' for FIXT.1.1");
        };
    }

    private static String stringProp(String key, String defaultValue) {
        String value = System.getProperty(key, defaultValue).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Property '" + key + "' must not be blank");
        }
        return value;
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
            case "BUY", "1" -> quickfix.field.Side.BUY;
            case "SELL", "2" -> quickfix.field.Side.SELL;
            default -> throw new IllegalArgumentException(
                    "Unsupported fix.demo.side='" + raw + "' (supported: BUY, SELL)");
        };
    }
}

