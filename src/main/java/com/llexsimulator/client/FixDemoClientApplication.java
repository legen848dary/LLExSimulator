package com.llexsimulator.client;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.builder.NewOrderSingleEncoder;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.library.SessionAcquireHandler;
import uk.co.real_logic.artio.library.SessionAcquiredInfo;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionWriter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Artio initiator session handler that continuously sends {@code NewOrderSingle}
 * messages at a fixed rate once the session is acquired.
 */
public final class FixDemoClientApplication implements SessionAcquireHandler, SessionHandler, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FixDemoClientApplication.class);
    private static final char EXECUTION_REPORT_TYPE = '8';
    private static final char REJECT_TYPE = '3';
    private static final char BUSINESS_MESSAGE_REJECT_TYPE = 'j';

    private final FixDemoClientConfig config;
    private final ScheduledExecutorService senderExecutor;
    private final ScheduledExecutorService statsExecutor;
    private final ScheduledExecutorService reconnectExecutor;
    private final NewOrderSingleEncoder orderEncoder = new NewOrderSingleEncoder();
    private final UtcTimestampEncoder transactTimeEncoder = new UtcTimestampEncoder();

    private final AtomicReference<Session> activeSession = new AtomicReference<>();
    private final AtomicReference<SessionWriter> activeWriter = new AtomicReference<>();
    private final AtomicBoolean loggedOn = new AtomicBoolean(false);
    private final AtomicLong clOrdCounter = new AtomicLong();
    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong execReportCount = new AtomicLong();
    private final AtomicLong rejectCount = new AtomicLong();
    private final AtomicLong sendFailureCount = new AtomicLong();

    private volatile FixLibrary library;
    private volatile Reply<Session> pendingInitiate;
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
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fix-demo-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    public void attachLibrary(FixLibrary library) {
        this.library = library;
    }

    public void start() {
        long periodNanos = Math.max(1L, 1_000_000_000L / config.ratePerSecond());
        senderExecutor.scheduleAtFixedRate(this::sendOneIfLoggedOn, 0L, periodNanos, TimeUnit.NANOSECONDS);
        statsExecutor.scheduleAtFixedRate(this::logProgress, 5L, 5L, TimeUnit.SECONDS);
        reconnectExecutor.scheduleWithFixedDelay(this::ensureConnected, 0L,
                Math.max(1, config.reconnectIntervalSec()), TimeUnit.SECONDS);
        log.info("Demo client ready: beginString={} senderCompId={} targetCompId={} host={} port={} rate={} msg/s symbol={} side={} qty={} price={}",
                config.beginString(), config.senderCompId(), config.targetCompId(),
                config.host(), config.port(), config.ratePerSecond(),
                config.symbol(), sideName(config.side()), config.orderQty(), config.price());
    }

    @Override
    public SessionHandler onSessionAcquired(Session session, SessionAcquiredInfo acquiredInfo) {
        FixLibrary currentLibrary = library;
        if (currentLibrary == null) {
            throw new IllegalStateException("Artio library is not attached");
        }
        SessionWriter writer = currentLibrary.sessionWriter(session.id(), session.connectionId(), session.sequenceIndex());
        activeSession.set(session);
        activeWriter.set(writer);
        pendingInitiate = null;
        log.info("FIX session acquired: sessionId={} connectionId={} state={} rate={} msg/s slow={} metaDataStatus={}",
                session.id(), session.connectionId(), config.ratePerSecond(), acquiredInfo.isSlow(), acquiredInfo.metaDataStatus());
        return this;
    }

    @Override
    public void onSessionStart(Session session) {
        activeSession.set(session);
        loggedOn.set(true);
        SessionWriter writer = activeWriter.get();
        if (writer != null) {
            writer.sequenceIndex(session.sequenceIndex());
        }
        log.info("FIX logon: sessionId={} state={} — order flow active at {} msg/s", session.id(), session.state(), config.ratePerSecond());
    }

    @Override
    public Action onDisconnect(int libraryId, Session session, DisconnectReason reason) {
        loggedOn.set(false);
        activeSession.compareAndSet(session, null);
        activeWriter.set(null);
        pendingInitiate = null;
        log.info("FIX logout: sessionId={} reason={} — order flow paused until reconnection", session.id(), reason);
        return Action.CONTINUE;
    }

    @Override
    public void onTimeout(int libraryId, Session session) {
        log.warn("FIX timeout: sessionId={}", session.id());
    }

    @Override
    public void onSlowStatus(int libraryId, Session session, boolean hasBecomeSlow) {
        log.warn("FIX slow-consumer status changed: sessionId={} slow={}", session.id(), hasBecomeSlow);
    }

    @Override
    public Action onMessage(org.agrona.DirectBuffer buffer, int offset, int length, int libraryId,
                            Session session, int sequenceIndex, long messageType, long timestampInNs,
                            long position, OnMessageInfo messageInfo) {
        if (!messageInfo.isValid()) {
            return Action.CONTINUE;
        }

        SessionWriter writer = activeWriter.get();
        if (writer != null) {
            writer.sequenceIndex(sequenceIndex);
        }

        switch ((char) messageType) {
            case EXECUTION_REPORT_TYPE -> execReportCount.incrementAndGet();
            case REJECT_TYPE, BUSINESS_MESSAGE_REJECT_TYPE -> rejectCount.incrementAndGet();
            default -> {
                // ignore noise; this is just a spam client
            }
        }
        return Action.CONTINUE;
    }

    private void sendOneIfLoggedOn() {
        Session session = activeSession.get();
        SessionWriter writer = activeWriter.get();
        if (!loggedOn.get() || session == null || writer == null || !"ACTIVE".equals(session.state().name())) {
            return;
        }

        String clOrdId = nextClOrdId();
        try {
            buildNewOrderSingle(clOrdId);
            session.prepare(orderEncoder.header());
            long result = writer.send(orderEncoder, session.sequenceIndex());
            if (Pressure.isBackPressured(result) || result < 0L) {
                sendFailureCount.incrementAndGet();
                return;
            }
            long total = sentCount.incrementAndGet();
            if (total == 1 || total % Math.max(1_000L, config.ratePerSecond() * 10L) == 0L) {
                log.info("Orders sent={} latestClOrdId={} sessionId={}", total, clOrdId, session.id());
            }
        } catch (Exception e) {
            sendFailureCount.incrementAndGet();
            log.warn("Unexpected send failure for order {}", clOrdId, e);
        }
    }

    private void buildNewOrderSingle(String clOrdId) {
        orderEncoder.reset();
        orderEncoder.clOrdID(clOrdId)
                .side(config.side())
                .ordType('2')
                .price(toScaledLong(config.price(), 8), 8)
                .timeInForce('0');
        orderEncoder.instrument().symbol(config.symbol());
        orderEncoder.orderQtyData().orderQty(toScaledLong(config.orderQty(), 4), 4);
        int timestampLength = transactTimeEncoder.encode(System.currentTimeMillis());
        orderEncoder.transactTime(transactTimeEncoder.buffer(), timestampLength);
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
                loggedOn.get(), activeSession.get(),
                sent, sentDelta,
                execReports, execDelta,
                rejects, rejectDelta,
                sendFailureCount.get());
    }

    private void ensureConnected() {
        FixLibrary currentLibrary = library;
        if (currentLibrary == null || !currentLibrary.isConnected()) {
            return;
        }

        Session session = activeSession.get();
        if (session != null && session.isConnected()) {
            return;
        }

        Reply<Session> reply = pendingInitiate;
        if (reply != null) {
            if (reply.isExecuting()) {
                return;
            }
            if (reply.hasErrored()) {
                log.warn("Artio session initiate errored", reply.error());
            } else if (reply.hasTimedOut()) {
                log.warn("Artio session initiate timed out");
            }
            pendingInitiate = null;
        }

        try {
            pendingInitiate = currentLibrary.initiate(config.toSessionConfiguration());
            log.info("Initiating Artio FIX session to {}:{} ({}->{})",
                    config.host(), config.port(), config.senderCompId(), config.targetCompId());
        } catch (Exception e) {
            pendingInitiate = null;
            log.warn("Failed to initiate Artio FIX session", e);
        }
    }

    private static String sideName(char side) {
        return side == '2' ? "SELL" : "BUY";
    }

    private static long toScaledLong(double value, int scale) {
        return Math.round(value * Math.pow(10, scale));
    }

    @Override
    public void close() {
        senderExecutor.shutdownNow();
        statsExecutor.shutdownNow();
        reconnectExecutor.shutdownNow();
    }
}

