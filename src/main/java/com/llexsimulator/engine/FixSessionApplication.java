package com.llexsimulator.engine;

import com.llexsimulator.disruptor.DisruptorPipeline;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.real_logic.artio.decoder.NewOrderSingleDecoder;
import uk.co.real_logic.artio.decoder.OrderCancelRequestDecoder;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.library.SessionAcquireHandler;
import uk.co.real_logic.artio.library.SessionAcquiredInfo;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionWriter;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

/**
 * Artio acceptor session factory and inbound session handler.
 */
public final class FixSessionApplication implements SessionAcquireHandler {

    private static final Logger log = LoggerFactory.getLogger(FixSessionApplication.class);
    private static final long NEW_ORDER_SINGLE_TYPE = 'D';
    private static final long ORDER_CANCEL_REQUEST_TYPE = 'F';

    private final OrderSessionRegistry registry;
    private final DisruptorPipeline pipeline;

    private volatile FixLibrary library;

    public FixSessionApplication(OrderSessionRegistry registry, DisruptorPipeline pipeline) {
        this.registry = registry;
        this.pipeline = pipeline;
    }

    public void attachLibrary(FixLibrary library) {
        this.library = library;
    }

    @Override
    public SessionHandler onSessionAcquired(Session session, SessionAcquiredInfo acquiredInfo) {
        FixLibrary currentLibrary = library;
        if (currentLibrary == null) {
            throw new IllegalStateException("Artio library must be attached before session acquisition");
        }

        SessionWriter writer = currentLibrary.sessionWriter(session.id(), session.connectionId(), session.sequenceIndex());
        FixConnection connection = registry.register(session, writer);
        connection.onSlowStatus(session, acquiredInfo.isSlow());
        log.info("FIX logon: session={} connId={} slow={} metaDataStatus={} seqIdx={} rxSeq={} txSeq={}",
                connection.sessionKey(), connection.connectionId(), acquiredInfo.isSlow(), acquiredInfo.metaDataStatus(),
                connection.sequenceIndex(), connection.lastReceivedMsgSeqNum(), connection.lastSentMsgSeqNum());
        return new InboundSessionHandler(connection);
    }

    private final class InboundSessionHandler implements SessionHandler {

        private final FixConnection connection;
        private final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer();
        private final NewOrderSingleDecoder newOrderSingleDecoder = new NewOrderSingleDecoder();
        private final OrderCancelRequestDecoder orderCancelRequestDecoder = new OrderCancelRequestDecoder();

        private InboundSessionHandler(FixConnection connection) {
            this.connection = connection;
        }

        @Override
        public Action onMessage(org.agrona.DirectBuffer buffer, int offset, int length, int libraryId,
                                Session session, int sequenceIndex, long messageType, long timestampInNs,
                                long position, OnMessageInfo messageInfo) {

            if (!messageInfo.isValid()) {
                return Action.CONTINUE;
            }

            connection.onInboundMessage(session, sequenceIndex, messageType, position);
            long arrivalNs = System.nanoTime();
            asciiBuffer.wrap(buffer);

            if (messageType == NEW_ORDER_SINGLE_TYPE) {
                newOrderSingleDecoder.decode(asciiBuffer, offset, length);
                pipeline.publish(newOrderSingleDecoder, connection, arrivalNs);
            } else if (messageType == ORDER_CANCEL_REQUEST_TYPE) {
                orderCancelRequestDecoder.decode(asciiBuffer, offset, length);
                pipeline.publish(orderCancelRequestDecoder, connection, arrivalNs);
            }

            return Action.CONTINUE;
        }

        @Override
        public void onTimeout(int libraryId, Session session) {
            connection.onTimeout(session);
            log.warn("FIX session timeout: session={} seqIdx={} rxSeq={} txSeq={} inbound={} lastInboundType={} lastDisconnectReason={}",
                    connection.sessionKey(), connection.sequenceIndex(), connection.lastReceivedMsgSeqNum(),
                    connection.lastSentMsgSeqNum(), connection.inboundMessageCount(), connection.lastInboundMsgType(),
                    connection.lastDisconnectReason());
        }

        @Override
        public void onSlowStatus(int libraryId, Session session, boolean hasBecomeSlow) {
            connection.onSlowStatus(session, hasBecomeSlow);
            log.warn("FIX slow-consumer status changed: session={} slow={} seqIdx={} rxSeq={} txSeq={} inbound={}",
                    connection.sessionKey(), hasBecomeSlow, connection.sequenceIndex(),
                    connection.lastReceivedMsgSeqNum(), connection.lastSentMsgSeqNum(), connection.inboundMessageCount());
        }

        @Override
        public Action onDisconnect(int libraryId, Session session, DisconnectReason reason) {
            connection.onDisconnect(session, reason.name());
            registry.archiveAndRemove(connection.connectionId());
            log.info("FIX logout: session={} reason={} seqIdx={} rxSeq={} txSeq={} inbound={} outOk={} backpressure={} sendFailures={} recentEvents={}",
                    connection.sessionKey(), reason, connection.sequenceIndex(), connection.lastReceivedMsgSeqNum(),
                    connection.lastSentMsgSeqNum(), connection.inboundMessageCount(), connection.outboundSendSuccessCount(),
                    connection.outboundBackpressureCount(), connection.outboundSendFailureCount(), connection.recentEventsSnapshot());
            return Action.CONTINUE;
        }

        @Override
        public void onSessionStart(Session session) {
            connection.updateSession(session, connection.writer());
            log.info("FIX session started: {}", connection.sessionKey());
        }
    }
}

