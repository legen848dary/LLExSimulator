package com.llexsimulator.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.field.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * QuickFIX/J initiator application that continuously sends {@code NewOrderSingle}
 * messages at a fixed rate once the session is logged on.
 */
public final class FixDemoClientApplication implements Application, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FixDemoClientApplication.class);

    private final FixDemoClientConfig config;
    private final ScheduledExecutorService senderExecutor;
    private final ScheduledExecutorService statsExecutor;

    private final AtomicReference<SessionID> activeSessionId = new AtomicReference<>();
    private final AtomicBoolean loggedOn = new AtomicBoolean(false);
    private final AtomicLong clOrdCounter = new AtomicLong();
    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong execReportCount = new AtomicLong();
    private final AtomicLong rejectCount = new AtomicLong();
    private final AtomicLong sendFailureCount = new AtomicLong();

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
        log.info("Demo client ready: beginString={} senderCompId={} targetCompId={} host={} port={} rate={} msg/s symbol={} side={} qty={} price={}",
                config.beginString(), config.senderCompId(), config.targetCompId(),
                config.host(), config.port(), config.ratePerSecond(),
                config.symbol(), sideName(config.side()), config.orderQty(), config.price());
    }

    @Override
    public void onCreate(SessionID sessionId) {
        log.info("Session created: {}", sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        activeSessionId.set(sessionId);
        loggedOn.set(true);
        log.info("FIX logon: {} — order flow active at {} msg/s", sessionId, config.ratePerSecond());
    }

    @Override
    public void onLogout(SessionID sessionId) {
        loggedOn.set(false);
        activeSessionId.compareAndSet(sessionId, null);
        log.info("FIX logout: {} — order flow paused until reconnection", sessionId);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        // no-op
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            if (MsgType.LOGOUT.equals(msgType) || MsgType.REJECT.equals(msgType)) {
                log.warn("Admin message from simulator: session={} type={} message={}", sessionId, msgType, message);
            }
        } catch (FieldNotFound e) {
            log.warn("Admin message missing MsgType for session {}", sessionId);
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionId) {
        // no-op
    }

    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        String msgType = message.getHeader().getString(MsgType.FIELD);
        switch (msgType) {
            case MsgType.EXECUTION_REPORT -> execReportCount.incrementAndGet();
            case MsgType.REJECT, MsgType.BUSINESS_MESSAGE_REJECT -> rejectCount.incrementAndGet();
            default -> {
                // ignore noise; this is just a spam client
            }
        }
    }

    private void sendOneIfLoggedOn() {
        SessionID sessionId = activeSessionId.get();
        if (!loggedOn.get() || sessionId == null) {
            return;
        }

        String clOrdId = nextClOrdId();
        try {
            Session.sendToTarget(buildNewOrderSingle(clOrdId), sessionId);
            long total = sentCount.incrementAndGet();
            if (total == 1 || total % Math.max(1_000L, config.ratePerSecond() * 10L) == 0L) {
                log.info("Orders sent={} latestClOrdId={} session={}", total, clOrdId, sessionId);
            }
        } catch (SessionNotFound e) {
            sendFailureCount.incrementAndGet();
            loggedOn.set(false);
            activeSessionId.compareAndSet(sessionId, null);
            log.warn("Failed to send order {} — session unavailable: {}", clOrdId, e.getMessage());
        } catch (Exception e) {
            sendFailureCount.incrementAndGet();
            log.warn("Unexpected send failure for order {}", clOrdId, e);
        }
    }

    private Message buildNewOrderSingle(String clOrdId) {
        Message order = new Message();
        order.getHeader().setString(MsgType.FIELD, MsgType.ORDER_SINGLE);
        order.setField(new ClOrdID(clOrdId));
        order.setField(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        order.setField(new Symbol(config.symbol()));
        order.setField(new Side(config.side()));
        order.setField(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
        order.setField(new OrdType(OrdType.LIMIT));
        order.setField(new OrderQty(config.orderQty()));
        order.setField(new Price(config.price()));
        order.setField(new TimeInForce(TimeInForce.DAY));
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

