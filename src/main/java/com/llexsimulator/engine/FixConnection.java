package com.llexsimulator.engine;

import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionWriter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Artio-backed FIX session metadata used by the simulator hot path and web/API layer.
 */
public final class FixConnection {

    private static final int RECENT_EVENT_LIMIT = 32;
    private static final long MESSAGE_TYPE_NONE = Long.MIN_VALUE;

    private final long connectionId;
    private final long sessionId;
    private final String sessionKey;
    private final String beginString;
    private final String senderCompId;
    private final String targetCompId;
    private final long connectedAtEpochMs;
    private final boolean benchmarkModeEnabled;
    private final Object diagnosticsLock = new Object();
    private final Deque<String> recentEvents = new ArrayDeque<>(RECENT_EVENT_LIMIT);

    private volatile Session session;
    private volatile SessionWriter writer;
    private volatile int sequenceIndex;
    private volatile int lastReceivedMsgSeqNum;
    private volatile int lastSentMsgSeqNum;
    private volatile boolean loggedOn;
    private volatile boolean slowConsumer;
    private volatile long lastLibraryPosition;
    private volatile long lastLogonAtEpochMs;
    private volatile long lastInboundAtEpochMs;
    private volatile long lastOutboundAtEpochMs;
    private volatile long lastTimeoutAtEpochMs;
    private volatile long lastDisconnectAtEpochMs;
    private final AtomicLong inboundMessageCount = new AtomicLong();
    private final AtomicLong outboundSendSuccessCount = new AtomicLong();
    private final AtomicLong outboundBackpressureCount = new AtomicLong();
    private final AtomicLong outboundSendFailureCount = new AtomicLong();
    private final AtomicLong timeoutCount = new AtomicLong();
    private final AtomicLong disconnectCount = new AtomicLong();
    private final AtomicLong slowStatusChangeCount = new AtomicLong();
    private volatile long lastInboundMsgTypeCode;
    private volatile String lastOutboundEvent;
    private volatile String lastDisconnectReason;

    FixConnection(long connectionId, Session session, SessionWriter writer, boolean benchmarkModeEnabled) {
        long now = System.currentTimeMillis();
        CompositeKey key = session.compositeKey();
        this.connectionId = connectionId;
        this.sessionId = session.id();
        this.beginString = session.beginString();
        this.senderCompId = key != null ? key.localCompId() : "UNKNOWN";
        this.targetCompId = key != null ? key.remoteCompId() : "UNKNOWN";
        this.sessionKey = beginString + ':' + senderCompId + "->" + targetCompId + '#' + sessionId;
        this.connectedAtEpochMs = now;
        this.benchmarkModeEnabled = benchmarkModeEnabled;
        this.session = session;
        this.writer = writer;
        this.sequenceIndex = session.sequenceIndex();
        this.lastReceivedMsgSeqNum = session.lastReceivedMsgSeqNum();
        this.lastSentMsgSeqNum = session.lastSentMsgSeqNum();
        this.loggedOn = true;
        this.lastLogonAtEpochMs = now;
        this.lastInboundMsgTypeCode = MESSAGE_TYPE_NONE;
        this.lastOutboundEvent = "NONE";
        this.lastDisconnectReason = "NONE";
        recordEvent("SESSION_ACQUIRED connId=" + connectionId + " seqIdx=" + sequenceIndex +
                " rxSeq=" + lastReceivedMsgSeqNum + " txSeq=" + lastSentMsgSeqNum);
    }

    public long connectionId() {
        return connectionId;
    }

    public long sessionId() {
        return sessionId;
    }

    public String sessionKey() {
        return sessionKey;
    }

    public String beginString() {
        return beginString;
    }

    public String senderCompId() {
        return senderCompId;
    }

    public String targetCompId() {
        return targetCompId;
    }

    public Session session() {
        return session;
    }

    public SessionWriter writer() {
        return writer;
    }

    public int sequenceIndex() {
        return sequenceIndex;
    }

    public int lastReceivedMsgSeqNum() {
        return lastReceivedMsgSeqNum;
    }

    public int lastSentMsgSeqNum() {
        return lastSentMsgSeqNum;
    }

    public boolean loggedOn() {
        return loggedOn;
    }

    public boolean slowConsumer() {
        return slowConsumer;
    }

    public long connectedAtEpochMs() {
        return connectedAtEpochMs;
    }

    public long lastLogonAtEpochMs() {
        return lastLogonAtEpochMs;
    }

    public long lastInboundAtEpochMs() {
        return lastInboundAtEpochMs;
    }

    public long lastOutboundAtEpochMs() {
        return lastOutboundAtEpochMs;
    }

    public long lastTimeoutAtEpochMs() {
        return lastTimeoutAtEpochMs;
    }

    public long lastDisconnectAtEpochMs() {
        return lastDisconnectAtEpochMs;
    }

    public long inboundMessageCount() {
        return inboundMessageCount.get();
    }

    public long outboundSendSuccessCount() {
        return outboundSendSuccessCount.get();
    }

    public long outboundBackpressureCount() {
        return outboundBackpressureCount.get();
    }

    public long outboundSendFailureCount() {
        return outboundSendFailureCount.get();
    }

    public long timeoutCount() {
        return timeoutCount.get();
    }

    public long disconnectCount() {
        return disconnectCount.get();
    }

    public long slowStatusChangeCount() {
        return slowStatusChangeCount.get();
    }

    public long lastLibraryPosition() {
        return lastLibraryPosition;
    }

    public String lastInboundMsgType() {
        return printableMessageType(lastInboundMsgTypeCode);
    }

    public String lastOutboundEvent() {
        return lastOutboundEvent;
    }

    public String lastDisconnectReason() {
        return lastDisconnectReason;
    }

    public List<String> recentEventsSnapshot() {
        synchronized (diagnosticsLock) {
            return new ArrayList<>(recentEvents);
        }
    }

    public SessionDiagnosticsSnapshot snapshot() {
        Session currentSession = session;
        return new SessionDiagnosticsSnapshot(
                connectionId,
                sessionId,
                sessionKey,
                beginString,
                senderCompId,
                targetCompId,
                loggedOn && currentSession != null && currentSession.isConnected(),
                currentSession != null ? currentSession.lastReceivedMsgSeqNum() : lastReceivedMsgSeqNum,
                sequenceIndex,
                lastReceivedMsgSeqNum,
                lastSentMsgSeqNum,
                inboundMessageCount.get(),
                outboundSendSuccessCount.get(),
                outboundBackpressureCount.get(),
                outboundSendFailureCount.get(),
                timeoutCount.get(),
                disconnectCount.get(),
                slowStatusChangeCount.get(),
                slowConsumer,
                printableMessageType(lastInboundMsgTypeCode),
                lastOutboundEvent,
                lastDisconnectReason,
                lastLibraryPosition,
                connectedAtEpochMs,
                lastLogonAtEpochMs,
                lastInboundAtEpochMs,
                lastOutboundAtEpochMs,
                lastTimeoutAtEpochMs,
                lastDisconnectAtEpochMs,
                recentEventsSnapshot()
        );
    }

    public synchronized int claimNextOutboundMsgSeqNum() {
        return ++lastSentMsgSeqNum;
    }

    public synchronized void rollbackOutboundMsgSeqNum(int claimedMsgSeqNum) {
        if (lastSentMsgSeqNum == claimedMsgSeqNum) {
            lastSentMsgSeqNum--;
        }
    }

    public void updateSession(Session session, SessionWriter writer) {
        long now = System.currentTimeMillis();
        this.session = session;
        this.writer = writer;
        this.sequenceIndex = session.sequenceIndex();
        this.lastReceivedMsgSeqNum = session.lastReceivedMsgSeqNum();
        this.lastSentMsgSeqNum = session.lastSentMsgSeqNum();
        this.loggedOn = true;
        this.lastLogonAtEpochMs = now;
        recordEvent("SESSION_STARTED seqIdx=" + sequenceIndex + " rxSeq=" + lastReceivedMsgSeqNum + " txSeq=" + lastSentMsgSeqNum);
    }

    public void sequenceIndex(int sequenceIndex) {
        this.sequenceIndex = sequenceIndex;
        SessionWriter currentWriter = writer;
        if (currentWriter != null) {
            currentWriter.sequenceIndex(sequenceIndex);
        }
    }

    public void onInboundMessage(Session session, int sequenceIndex, long messageType, long position) {
        this.sequenceIndex = sequenceIndex;
        if (!benchmarkModeEnabled) {
            this.lastInboundAtEpochMs = System.currentTimeMillis();
        }
        this.inboundMessageCount.incrementAndGet();
        this.lastLibraryPosition = position;
        this.lastInboundMsgTypeCode = messageType;
        if (!benchmarkModeEnabled) {
            refreshSessionSnapshot(session);
        }
    }

    public void onTimeout(Session session) {
        this.lastTimeoutAtEpochMs = System.currentTimeMillis();
        this.timeoutCount.incrementAndGet();
        refreshSessionSnapshot(session);
        recordEvent("TIMEOUT rxSeq=" + lastReceivedMsgSeqNum + " txSeq=" + lastSentMsgSeqNum + " seqIdx=" + sequenceIndex);
    }

    public void onSlowStatus(Session session, boolean hasBecomeSlow) {
        this.slowConsumer = hasBecomeSlow;
        this.slowStatusChangeCount.incrementAndGet();
        refreshSessionSnapshot(session);
        recordEvent("SLOW_STATUS slow=" + hasBecomeSlow + " rxSeq=" + lastReceivedMsgSeqNum + " txSeq=" + lastSentMsgSeqNum);
    }

    public void onDisconnect(Session session, String reason) {
        this.loggedOn = false;
        this.slowConsumer = false;
        this.lastDisconnectAtEpochMs = System.currentTimeMillis();
        this.lastDisconnectReason = reason;
        this.disconnectCount.incrementAndGet();
        refreshSessionSnapshot(session);
        recordEvent("DISCONNECT reason=" + reason + " rxSeq=" + lastReceivedMsgSeqNum + " txSeq=" + lastSentMsgSeqNum +
                " timeouts=" + timeoutCount.get() + " backpressure=" + outboundBackpressureCount.get() + " sendFailures=" + outboundSendFailureCount.get());
    }

    public void onOutboundSendSuccess(Session session, String eventName) {
        if (!benchmarkModeEnabled) {
            this.lastOutboundAtEpochMs = System.currentTimeMillis();
        }
        this.outboundSendSuccessCount.incrementAndGet();
        this.lastOutboundEvent = eventName;
        if (!benchmarkModeEnabled) {
            refreshSessionSnapshot(session);
        }
    }

    public void onOutboundBackpressure(Session session, String eventName, long result) {
        this.outboundBackpressureCount.incrementAndGet();
        this.lastOutboundEvent = eventName;
        refreshSessionSnapshot(session);
        recordEvent("SEND_BACKPRESSURE event=" + eventName + " result=" + result + " rxSeq=" + lastReceivedMsgSeqNum + " txSeq=" + lastSentMsgSeqNum);
    }

    public void onOutboundSendFailure(Session session, String eventName, long result) {
        this.outboundSendFailureCount.incrementAndGet();
        this.lastOutboundEvent = eventName;
        refreshSessionSnapshot(session);
        recordEvent("SEND_FAILURE event=" + eventName + " result=" + result + " rxSeq=" + lastReceivedMsgSeqNum + " txSeq=" + lastSentMsgSeqNum);
    }

    public void markDisconnected() {
        this.loggedOn = false;
    }

    private void refreshSessionSnapshot(Session session) {
        if (session == null) {
            return;
        }
        this.lastReceivedMsgSeqNum = session.lastReceivedMsgSeqNum();
        this.lastSentMsgSeqNum = session.lastSentMsgSeqNum();
        this.sequenceIndex = session.sequenceIndex();
    }

    private void recordEvent(String event) {
        synchronized (diagnosticsLock) {
            if (recentEvents.size() == RECENT_EVENT_LIMIT) {
                recentEvents.removeFirst();
            }
            recentEvents.addLast(System.currentTimeMillis() + " " + event);
        }
    }

    private static String printableMessageType(long messageType) {
        if (messageType == MESSAGE_TYPE_NONE) {
            return "NONE";
        }
        if (messageType >= 32 && messageType <= 126) {
            return Character.toString((char)messageType);
        }
        return Long.toString(messageType);
    }
}

