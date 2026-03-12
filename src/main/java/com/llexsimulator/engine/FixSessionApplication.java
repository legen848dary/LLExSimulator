package com.llexsimulator.engine;

import com.llexsimulator.disruptor.DisruptorPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.field.MsgType;

/**
 * QuickFIX/J {@link Application} implementation.
 *
 * <p>On each incoming {@code NewOrderSingle} or {@code OrderCancelRequest}, it
 * records the arrival nanosecond timestamp and publishes the raw {@link Message}
 * to the Disruptor ring buffer. The FIX thread is released as fast as possible.
 *
 * <p>One instance is shared across all sessions (thread-safe by QuickFIX/J contract
 * — callbacks from different sessions may arrive concurrently).
 */
public final class FixSessionApplication implements Application {

    private static final Logger log = LoggerFactory.getLogger(FixSessionApplication.class);

    private final OrderSessionRegistry registry;
    private final DisruptorPipeline    pipeline;

    // Per-session connection-ID cache.
    // SessionID is immutable, so we can avoid per-message toString() allocation.
    private final java.util.concurrent.ConcurrentHashMap<SessionID, Long> sessionConnIds =
            new java.util.concurrent.ConcurrentHashMap<>();

    public FixSessionApplication(OrderSessionRegistry registry, DisruptorPipeline pipeline) {
        this.registry = registry;
        this.pipeline = pipeline;
    }

    @Override
    public void onCreate(SessionID sessionID) {
        log.info("FIX session created: {}", sessionID);
    }

    @Override
    public void onLogon(SessionID sessionID) {
        long connId = registry.register(sessionID);
        sessionConnIds.put(sessionID, connId);
        log.info("FIX logon: {} → connId={}", sessionID, connId);
    }

    @Override
    public void onLogout(SessionID sessionID) {
        Long connId = sessionConnIds.remove(sessionID);
        if (connId != null) registry.remove(connId);
        log.info("FIX logout: {}", sessionID);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) { /* no-op */ }

    @Override
    public void fromAdmin(Message message, SessionID sessionID) { /* no-op */ }

    @Override
    public void toApp(Message message, SessionID sessionID) { /* no-op */ }

    @Override
    public void fromApp(Message message, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {

        // Capture arrival timestamp FIRST — before any processing
        long arrivalNs = System.nanoTime();

        String msgType;
        try {
            msgType = message.getHeader().getString(MsgType.FIELD);
        } catch (FieldNotFound e) {
            return; // malformed — skip
        }

        // Only process NewOrderSingle (D) and OrderCancelRequest (F)
        if (!MsgType.ORDER_SINGLE.equals(msgType) && !MsgType.ORDER_CANCEL_REQUEST.equals(msgType)) {
            return;
        }

        Long connId = sessionConnIds.get(sessionID);
        if (connId == null) {
            log.warn("fromApp received message for unknown session: {}", sessionID);
            return;
        }

        // Publish to Disruptor — this call returns immediately
        pipeline.publish(message, sessionID, connId, arrivalNs);
    }
}

