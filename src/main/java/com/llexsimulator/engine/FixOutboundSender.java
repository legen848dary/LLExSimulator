package com.llexsimulator.engine;

import org.agrona.concurrent.ManyToOneConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.builder.ExecutionReportEncoder;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.session.Session;

import java.util.Arrays;

/**
 * Bridges outbound FIX sends from producer threads onto the Artio library poller thread.
 */
public final class FixOutboundSender {

    private static final Logger log = LoggerFactory.getLogger(FixOutboundSender.class);
    private static final int MAX_DRAIN_PER_CYCLE = 1024;

    private final ManyToOneConcurrentLinkedQueue<PendingExecutionReport> queue =
            new ManyToOneConcurrentLinkedQueue<>();
    private final ExecutionReportEncoder executionReport = new ExecutionReportEncoder();
    private final UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();

    public void enqueueExecutionReport(
            FixConnection connection,
            String outboundEvent,
            byte[] clOrdId, int clOrdIdLength,
            byte[] orderId, int orderIdLength,
            byte[] execId, int execIdLength,
            byte[] symbol, int symbolLength,
            char side, long orderQty, long price, long lastQty, long lastPx,
            long cumQty, long leavesQty,
            char execType, char ordStatus,
            int ordRejReason) {
        queue.add(new PendingExecutionReport(
                connection,
                outboundEvent,
                Arrays.copyOf(clOrdId, clOrdIdLength), clOrdIdLength,
                Arrays.copyOf(orderId, orderIdLength), orderIdLength,
                Arrays.copyOf(execId, execIdLength), execIdLength,
                Arrays.copyOf(symbol, symbolLength), symbolLength,
                side, orderQty, price, lastQty, lastPx,
                cumQty, leavesQty,
                execType, ordStatus,
                ordRejReason));
    }

    public int drain() {
        int workCount = 0;
        for (int i = 0; i < MAX_DRAIN_PER_CYCLE; i++) {
            PendingExecutionReport pending = queue.poll();
            if (pending == null) {
                break;
            }

            long result = trySend(pending);
            Session session = pending.connection.session();
            if (Pressure.isBackPressured(result)) {
                pending.connection.onOutboundBackpressure(session, pending.outboundEvent, result);
                log.warn("Back pressured while sending ExecutionReport: session={} result={}",
                        pending.connection.sessionKey(), result);
                queue.add(pending);
                break;
            }

            if (result >= 0L) {
                pending.connection.onOutboundSendSuccess(session, pending.outboundEvent);
            } else {
                pending.connection.onOutboundSendFailure(session, pending.outboundEvent, result);
                log.warn("Failed to send ExecutionReport: session={} result={}",
                        pending.connection.sessionKey(), result);
            }
            workCount++;
        }
        return workCount;
    }

    private long trySend(PendingExecutionReport pending) {
        synchronized (pending.connection) {
            Session session = pending.connection.session();
            if (session == null || !session.isActive()) {
                pending.connection.onOutboundSendFailure(session, pending.outboundEvent, Long.MIN_VALUE);
                log.warn("Cannot send ExecutionReport on inactive session={} state sessionPresent={}",
                        pending.connection.sessionKey(), session != null);
                return Long.MIN_VALUE;
            }

            executionReport.reset();
            executionReport.orderID(pending.orderId, pending.orderIdLength)
                    .execID(pending.execId, pending.execIdLength)
                    .execType(pending.execType)
                    .ordStatus(pending.ordStatus)
                    .side(pending.side)
                    .leavesQty(pending.leavesQty, 4)
                    .cumQty(pending.cumQty, 4)
                    .avgPx(pending.cumQty > 0 ? pending.lastPx : 0L, 8)
                    .clOrdID(pending.clOrdId, pending.clOrdIdLength);
            executionReport.instrument().symbol(pending.symbol, pending.symbolLength);
            executionReport.orderQtyData().orderQty(pending.orderQty, 4);

            if (pending.price > 0) {
                executionReport.price(pending.price, 8);
            }
            if (pending.lastQty > 0) {
                executionReport.lastQty(pending.lastQty, 4);
            }
            if (pending.lastPx > 0) {
                executionReport.lastPx(pending.lastPx, 8);
            }
            if (pending.ordRejReason > 0) {
                executionReport.ordRejReason(pending.ordRejReason);
            }

            int timestampLength = timestampEncoder.encode(System.currentTimeMillis());
            executionReport.transactTime(timestampEncoder.buffer(), timestampLength);
            return session.trySend(executionReport);
        }
    }

    private static final class PendingExecutionReport {
        private final FixConnection connection;
        private final String outboundEvent;
        private final byte[] clOrdId;
        private final int clOrdIdLength;
        private final byte[] orderId;
        private final int orderIdLength;
        private final byte[] execId;
        private final int execIdLength;
        private final byte[] symbol;
        private final int symbolLength;
        private final char side;
        private final long orderQty;
        private final long price;
        private final long lastQty;
        private final long lastPx;
        private final long cumQty;
        private final long leavesQty;
        private final char execType;
        private final char ordStatus;
        private final int ordRejReason;

        private PendingExecutionReport(
                FixConnection connection,
                String outboundEvent,
                byte[] clOrdId, int clOrdIdLength,
                byte[] orderId, int orderIdLength,
                byte[] execId, int execIdLength,
                byte[] symbol, int symbolLength,
                char side, long orderQty, long price, long lastQty, long lastPx,
                long cumQty, long leavesQty,
                char execType, char ordStatus,
                int ordRejReason) {
            this.connection = connection;
            this.outboundEvent = outboundEvent;
            this.clOrdId = clOrdId;
            this.clOrdIdLength = clOrdIdLength;
            this.orderId = orderId;
            this.orderIdLength = orderIdLength;
            this.execId = execId;
            this.execIdLength = execIdLength;
            this.symbol = symbol;
            this.symbolLength = symbolLength;
            this.side = side;
            this.orderQty = orderQty;
            this.price = price;
            this.lastQty = lastQty;
            this.lastPx = lastPx;
            this.cumQty = cumQty;
            this.leavesQty = leavesQty;
            this.execType = execType;
            this.ordStatus = ordStatus;
            this.ordRejReason = ordRejReason;
        }
    }
}

