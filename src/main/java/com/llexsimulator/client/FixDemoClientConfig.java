package com.llexsimulator.client;

import com.llexsimulator.fix.ArtioDictionaryResolver;
import uk.co.real_logic.artio.library.SessionConfiguration;

/**
 * Runtime configuration for the Artio-based demo FIX initiator client.
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
        String artioLogDir,
        boolean rawMessageLoggingEnabled
) {

    public static FixDemoClientConfig from(String[] args) {
        String argRate = args.length > 0 ? args[0] : null;

        return new FixDemoClientConfig(
                stringProp("fix.demo.host", "localhost"),
                intProp("fix.demo.port", 9880),
                stringProp("fix.demo.beginString", "FIX.4.4"),
                stringProp("fix.demo.senderCompId", "CLIENT1"),
                stringProp("fix.demo.targetCompId", "LLEXSIM"),
                stringProp("fix.demo.defaultApplVerId", "FIX.4.4"),
                intProp("fix.demo.heartBtInt", 30),
                intProp("fix.demo.reconnectIntervalSec", 5),
                positiveInt(argRate != null ? argRate : System.getProperty("fix.demo.rate", "100"), "fix.demo.rate"),
                stringProp("fix.demo.symbol", "AAPL"),
                parseSide(stringProp("fix.demo.side", "BUY")),
                positiveDouble(System.getProperty("fix.demo.orderQty", "100"), "fix.demo.orderQty"),
                positiveDouble(System.getProperty("fix.demo.price", "100.25"), "fix.demo.price"),
                stringProp("fix.demo.logDir", "logs/fix-demo-client/artio"),
                Boolean.parseBoolean(System.getProperty("fix.demo.rawLoggingEnabled", "false"))
        );
    }

    public SessionConfiguration toSessionConfiguration() {
        validateSupportedBeginString();
        return SessionConfiguration.builder()
                .address(host, port)
                .senderCompId(senderCompId)
                .targetCompId(targetCompId)
                .fixDictionary(ArtioDictionaryResolver.resolve())
                .sequenceNumbersPersistent(false)
                .resetSeqNum(true)
                .closedResendInterval(true)
                .sendRedundantResendRequests(false)
                .enableLastMsgSeqNumProcessed(false)
                .disconnectOnFirstMessageNotLogon(true)
                .timeoutInMs(Math.max(heartBtIntSec * 2_000L, 5_000L))
                .build();
    }

    private void validateSupportedBeginString() {
        if (!"FIX.4.4".equals(beginString)) {
            throw new IllegalArgumentException(
                    "The Artio demo client currently supports fix.demo.beginString=FIX.4.4 only (was '" + beginString + "')");
        }
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
            case "BUY", "1" -> '1';
            case "SELL", "2" -> '2';
            default -> throw new IllegalArgumentException(
                    "Unsupported fix.demo.side='" + raw + "' (supported: BUY, SELL)");
        };
    }
}

