package com.llexsimulator.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        log.info("FIX logon: session={} — order flow active at {} msg/s", sessionId, config.ratePerSecond());
    }

    @Override
    public void onLogout(SessionID sessionId) {
        loggedOn.set(false);
        activeSessionId.compareAndSet(sessionId, null);
        logoutCount.incrementAndGet();
        log.info("FIX logout: session={} — order flow paused until reconnection", sessionId);
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
        // no-op
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) {
        // no-op
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        // outbound application messages are counted on send success instead
    }

    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        String msgType = message.getHeader().getString(MsgType.FIELD);
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
                log.info("Orders sent={} latestClOrdId={} session={}", total, clOrdId, sessionId);
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
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
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

        log.info("Progress: loggedOn={} session={} sent={} (+{}/5s) execReports={} (+{}/5s) rejects={} (+{}/5s) sendFailures={}",
                loggedOn.get(), activeSessionId.get(),
                sent, sentDelta,
                execReports, execDelta,
                rejects, rejectDelta,
                sendFailureCount.get());
    }

    private static String sideName(char side) {
        return side == Side.SELL ? "SELL" : "BUY";
    }

    @Override
    public void close() {
        senderExecutor.shutdownNow();
        statsExecutor.shutdownNow();
    }
}

