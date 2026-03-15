package com.llexsimulator.engine;

import org.agrona.concurrent.ManyToOneConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.builder.ExecutionReportEncoder;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.session.Session;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bridges outbound FIX sends from producer threads onto the Artio library poller thread.
 */
public final class FixOutboundSender {

    private static final Logger log = LoggerFactory.getLogger(FixOutboundSender.class);
    private static final int MAX_DRAIN_PER_CYCLE = 1024;
    private static final int INITIAL_REPORT_POOL_SIZE = 4096;
    private static final int MAX_CL_ORD_ID_LENGTH = 36;
    private static final int MAX_ORDER_ID_LENGTH = 36;
    private static final int MAX_EXEC_ID_LENGTH = 36;
    private static final int MAX_SYMBOL_LENGTH = 16;

    private final ManyToOneConcurrentLinkedQueue<PendingExecutionReport> queue =
            new ManyToOneConcurrentLinkedQueue<>();
    private final ConcurrentLinkedDeque<PendingExecutionReport> pool = new ConcurrentLinkedDeque<>();
    private final ExecutionReportEncoder executionReport = new ExecutionReportEncoder();
    private final UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();

    public FixOutboundSender() {
        for (int i = 0; i < INITIAL_REPORT_POOL_SIZE; i++) {
            pool.offerFirst(new PendingExecutionReport());
        }
    }

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
        PendingExecutionReport pending = pool.pollFirst();
        if (pending == null) {
            pending = new PendingExecutionReport();
        }
        pending.init(
                connection,
                outboundEvent,
                clOrdId, clOrdIdLength,
                orderId, orderIdLength,
                execId, execIdLength,
                symbol, symbolLength,
                side, orderQty, price, lastQty, lastPx,
                cumQty, leavesQty,
                execType, ordStatus,
                ordRejReason);
        queue.add(pending);
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
            recycle(pending);
            workCount++;
        }
        return workCount;
    }

    private long trySend(PendingExecutionReport pending) {
        FixConnection connection = pending.connection;
        synchronized (connection) {
            Session session = connection.session();
            if (session == null || !session.isActive()) {
                connection.onOutboundSendFailure(session, pending.outboundEvent, Long.MIN_VALUE);
                log.warn("Cannot send ExecutionReport on inactive session={} state sessionPresent={}",
                        connection.sessionKey(), session != null);
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

    private void recycle(PendingExecutionReport pending) {
        pending.reset();
        pool.offerFirst(pending);
    }

    private static final class PendingExecutionReport {
        private final byte[] clOrdId = new byte[MAX_CL_ORD_ID_LENGTH];
        private final byte[] orderId = new byte[MAX_ORDER_ID_LENGTH];
        private final byte[] execId = new byte[MAX_EXEC_ID_LENGTH];
        private final byte[] symbol = new byte[MAX_SYMBOL_LENGTH];

        private FixConnection connection;
        private String outboundEvent;
        private int clOrdIdLength;
        private int orderIdLength;
        private int execIdLength;
        private int symbolLength;
        private char side;
        private long orderQty;
        private long price;
        private long lastQty;
        private long lastPx;
        private long cumQty;
        private long leavesQty;
        private char execType;
        private char ordStatus;
        private int ordRejReason;

        private void init(
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
            this.clOrdIdLength = copyBytes(clOrdId, clOrdIdLength, this.clOrdId);
            this.orderIdLength = copyBytes(orderId, orderIdLength, this.orderId);
            this.execIdLength = copyBytes(execId, execIdLength, this.execId);
            this.symbolLength = copyBytes(symbol, symbolLength, this.symbol);
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

        private void reset() {
            connection = null;
            outboundEvent = null;
            clOrdIdLength = 0;
            orderIdLength = 0;
            execIdLength = 0;
            symbolLength = 0;
            side = 0;
            orderQty = 0L;
            price = 0L;
            lastQty = 0L;
            lastPx = 0L;
            cumQty = 0L;
            leavesQty = 0L;
            execType = 0;
            ordStatus = 0;
            ordRejReason = 0;
        }

        private static int copyBytes(byte[] source, int requestedLength, byte[] target) {
            int safeLength = Math.max(0, Math.min(requestedLength, target.length));
            if (safeLength > 0) {
                System.arraycopy(source, 0, target, 0, safeLength);
            }
            return safeLength;
        }
    }
}

