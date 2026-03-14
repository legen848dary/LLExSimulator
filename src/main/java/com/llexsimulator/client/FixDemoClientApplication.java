package com.llexsimulator.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.UnsupportedMessageType;
import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.MsgType;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * QuickFIX/J initiator application that continuously sends {@code NewOrderSingle}
 * messages at a fixed rate while logged on.
 */
public final class FixDemoClientApplication implements Application, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FixDemoClientApplication.class);

    // Markers — used for semantic tagging of key lifecycle events.
    // They appear in the log pattern as "%marker" and can be used
    // by log4j2 MarkerFilter to route/suppress specific event types.
    private static final Marker CONNECT    = MarkerFactory.getMarker("CONNECT");
    private static final Marker DISCONNECT = MarkerFactory.getMarker("DISCONNECT");
    private static final Marker PROGRESS   = MarkerFactory.getMarker("PROGRESS");
    private static final Marker ADMIN_OUT  = MarkerFactory.getMarker("FIX_ADMIN_OUT");
    private static final Marker ADMIN_IN   = MarkerFactory.getMarker("FIX_ADMIN_IN");
    private static final Marker APP_OUT    = MarkerFactory.getMarker("FIX_APP_OUT");
    private static final Marker APP_IN     = MarkerFactory.getMarker("FIX_APP_IN");

    /** Pool of symbols chosen randomly per order — pre-allocated, zero GC on hot path. */
    private static final String[] SYMBOLS = {
        "AAPL", "MSFT", "GOOGL", "AMZN", "TSLA",
        "NVDA", "META", "JPM",   "V",    "BAC"
    };

    private final FixDemoClientConfig config;
    private final ScheduledExecutorService senderExecutor;
    private final ScheduledExecutorService statsExecutor;
    private final AtomicReference<SessionID> activeSessionId = new AtomicReference<>();
    private final AtomicBoolean loggedOn = new AtomicBoolean(false);
    private final AtomicLong clOrdCounter = new AtomicLong();
    private final AtomicLong logonCount = new AtomicLong();
    private final AtomicLong logoutCount = new AtomicLong();
    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong execReportCount = new AtomicLong();
    private final AtomicLong rejectCount = new AtomicLong();
    private final AtomicLong sendFailureCount = new AtomicLong();
    private final CountDownLatch firstLogonLatch = new CountDownLatch(1);

    private volatile long lastSentSnapshot;
    private volatile long lastExecReportSnapshot;
    private volatile long lastRejectSnapshot;

    public FixDemoClientApplication(FixDemoClientConfig config) {
        this.config = config;
        this.senderExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fix-demo-sender");
            t.setDaemon(true);
            return t;
        });
        this.statsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fix-demo-stats");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        long periodNanos = Math.max(1L, 1_000_000_000L / config.ratePerSecond());
        senderExecutor.scheduleAtFixedRate(this::sendOneIfLoggedOn, 0L, periodNanos, TimeUnit.NANOSECONDS);
        statsExecutor.scheduleAtFixedRate(this::logProgress, 5L, 5L, TimeUnit.SECONDS);
        log.info("Demo client ready: beginString={} senderCompId={} targetCompId={} host={} port={} rate={} msg/s symbol=RANDOM{} side={} qty={} price={}",
                config.beginString(), config.senderCompId(), config.targetCompId(),
                config.host(), config.port(), config.ratePerSecond(),
                java.util.Arrays.toString(SYMBOLS), sideName(config.side()), config.orderQty(), config.price());
    }

    @Override
    public void onCreate(SessionID sessionId) {
        log.info("QuickFIX/J session created: {}", sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        activeSessionId.set(sessionId);
        loggedOn.set(true);
        logonCount.incrementAndGet();
        firstLogonLatch.countDown();
        log.info(CONNECT, "*** CONNECTED *** session={} rate={} msg/s (logons={})",
                sessionId, config.ratePerSecond(), logonCount.get());
    }

    @Override
    public void onLogout(SessionID sessionId) {
        loggedOn.set(false);
        activeSessionId.compareAndSet(sessionId, null);
        logoutCount.incrementAndGet();
        log.info(DISCONNECT, "*** DISCONNECTED *** session={} sent={} execReports={} sendFailures={} (logouts={})",
                sessionId, sentCount.get(), execReportCount.get(), sendFailureCount.get(), logoutCount.get());
    }

    public boolean awaitFirstLogon(Duration timeout) throws InterruptedException {
        return firstLogonLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isLoggedOn() {
        return loggedOn.get();
    }

    public long logonCount() {
        return logonCount.get();
    }

    public long logoutCount() {
        return logoutCount.get();
    }

    public long sentCount() {
        return sentCount.get();
    }

    public long execReportCount() {
        return execReportCount.get();
    }

    public long rejectCount() {
        return rejectCount.get();
    }

    public long sendFailureCount() {
        return sendFailureCount.get();
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        log.debug(ADMIN_OUT, "toAdmin session={} msgType={} raw={}",
                sessionId, messageType(message), printable(message));
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) {
        log.debug(ADMIN_IN, "fromAdmin session={} msgType={} raw={}",
                sessionId, messageType(message), printable(message));
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        log.debug(APP_OUT, "toApp session={} msgType={} raw={}",
                sessionId, messageType(message), printable(message));
    }

    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        String msgType = message.getHeader().getString(MsgType.FIELD);
        log.debug(APP_IN, "fromApp session={} msgType={} raw={}",
                sessionId, msgType, printable(message));
        switch (msgType) {
            case MsgType.EXECUTION_REPORT -> execReportCount.incrementAndGet();
            case MsgType.REJECT, MsgType.BUSINESS_MESSAGE_REJECT -> rejectCount.incrementAndGet();
            default -> {
                // ignore non-fill/reject application traffic
            }
        }
    }

    private void sendOneIfLoggedOn() {
        SessionID sessionId = activeSessionId.get();
        if (!loggedOn.get() || sessionId == null) {
            return;
        }

        Session session = Session.lookupSession(sessionId);
        if (session == null || !session.isLoggedOn()) {
            return;
        }

        String clOrdId = nextClOrdId();
        try {
            boolean sent = Session.sendToTarget(buildNewOrderSingle(clOrdId), sessionId);
            if (!sent) {
                sendFailureCount.incrementAndGet();
                return;
            }
            long total = sentCount.incrementAndGet();
            if (total == 1 || total % Math.max(1_000L, config.ratePerSecond() * 10L) == 0L) {
                log.debug("Orders sent={} latestClOrdId={} session={}", total, clOrdId, sessionId);
            }
        } catch (SessionNotFound e) {
            sendFailureCount.incrementAndGet();
            log.warn("Session not found while sending order {}", clOrdId, e);
        } catch (Exception e) {
            sendFailureCount.incrementAndGet();
            log.warn("Unexpected send failure for order {}", clOrdId, e);
        }
    }

    private NewOrderSingle buildNewOrderSingle(String clOrdId) {
        String symbol = SYMBOLS[ThreadLocalRandom.current().nextInt(SYMBOLS.length)];
        NewOrderSingle order = new NewOrderSingle(
                new ClOrdID(clOrdId),
                new Side(config.side()),
                new TransactTime(LocalDateTime.now(ZoneOffset.UTC)),
                new OrdType(OrdType.LIMIT));
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_NO_INTERVENTION));
        order.set(new Symbol(symbol));
        order.set(new OrderQty(config.orderQty()));
        order.set(new Price(config.price()));
        order.set(new TimeInForce(TimeInForce.DAY));
        return order;
    }

    private String nextClOrdId() {
        long seq = clOrdCounter.incrementAndGet();
        return "DEMO-" + System.currentTimeMillis() + '-' + seq;
    }

    private void logProgress() {
        long sent = sentCount.get();
        long execReports = execReportCount.get();
        long rejects = rejectCount.get();

        long sentDelta = sent - lastSentSnapshot;
        long execDelta = execReports - lastExecReportSnapshot;
        long rejectDelta = rejects - lastRejectSnapshot;

        lastSentSnapshot = sent;
        lastExecReportSnapshot = execReports;
        lastRejectSnapshot = rejects;

        long sentPerSecond = Math.round(sentDelta / 5.0d);
        long execPerSecond = Math.round(execDelta / 5.0d);
        long rejectPerSecond = Math.round(rejectDelta / 5.0d);

        log.info(PROGRESS,
                "--- PROGRESS --- loggedOn={} session={} | sent={} (+{}/5s ~= {}/s) | execReports={} (+{}/5s ~= {}/s) | rejects={} (+{}/5s ~= {}/s) | sendFailures={}",
                loggedOn.get(), activeSessionId.get(),
                sent, sentDelta, sentPerSecond,
                execReports, execDelta, execPerSecond,
                rejects, rejectDelta, rejectPerSecond,
                sendFailureCount.get());
    }

    private static String sideName(char side) {
        return side == Side.SELL ? "SELL" : "BUY";
    }

    private static String messageType(Message message) {
        try {
            return message.getHeader().getString(MsgType.FIELD);
        } catch (FieldNotFound e) {
            return "UNKNOWN";
        }
    }

    private static String printable(Message message) {
        return message.toString().replace('\u0001', '|');
    }

    @Override
    public void close() {
        senderExecutor.shutdownNow();
        statsExecutor.shutdownNow();
    }
}

