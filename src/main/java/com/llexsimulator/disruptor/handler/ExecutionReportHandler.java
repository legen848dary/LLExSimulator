package com.llexsimulator.disruptor.handler;

import com.llexsimulator.disruptor.OrderEvent;
import com.llexsimulator.engine.OrderSessionRegistry;
import com.llexsimulator.order.ExecIdGenerator;
import com.llexsimulator.order.OrderIdGenerator;
import com.llexsimulator.order.OrderRepository;
import com.llexsimulator.order.OrderState;
import com.llexsimulator.sbe.ExecType;
import com.llexsimulator.sbe.FillBehaviorType;
import com.llexsimulator.sbe.OrdStatus;
import com.lmax.disruptor.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.locks.LockSupport;

/**
 * Stage 3: builds and sends the FIX {@code ExecutionReport} (35=8) back to the client.
 */
public final class ExecutionReportHandler implements EventHandler<OrderEvent> {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReportHandler.class);

    private final OrderSessionRegistry sessionRegistry;
    private final OrderRepository      orderRepository;
    private final OrderIdGenerator     orderIdGen;
    private final ExecIdGenerator      execIdGen;

    // Scratch byte arrays for zero-GC string construction
    private final byte[] orderIdBytes = new byte[36];
    private final byte[] execIdBytes  = new byte[36];

    public ExecutionReportHandler(OrderSessionRegistry sessionRegistry,
                                  OrderRepository orderRepository) {
        this.sessionRegistry = sessionRegistry;
        this.orderRepository = orderRepository;
        this.orderIdGen      = new OrderIdGenerator("O", System.nanoTime());
        this.execIdGen       = new ExecIdGenerator();
    }

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        FillBehaviorType behavior = event.fillInstructionDecoder.fillBehavior();
        long delayNs = event.fillInstructionDecoder.delayNs();

        if (delayNs > 0) {
            // Capture primitives for async send — snapshot off the event buffers
            long corrId     = event.correlationId;
            long sessionId  = event.sessionConnectionId;
            long fillPrice  = event.fillInstructionDecoder.fillPrice();
            int  fillPctBps = event.fillInstructionDecoder.fillPctBps();
            long orderQty   = event.nosDecoder.orderQty();
            long price      = event.nosDecoder.price();
            com.llexsimulator.sbe.OrderSide side = event.nosDecoder.side();
            // small heap alloc — off critical path (delayed fill)
            byte[] clOrdId = new byte[36];
            event.nosDecoder.getClOrdId(clOrdId, 0);
            byte[] symbol = new byte[16];
            event.nosDecoder.getSymbol(symbol, 0);
            FillBehaviorType beh = behavior;

            Thread.ofVirtual()
                  .name("delayed-fill-" + corrId)
                  .start(() -> {
                      LockSupport.parkNanos(delayNs);
                      sendFill(corrId, sessionId, clOrdId, symbol, side, orderQty, price,
                               orderQty, fillPrice != 0 ? fillPrice : price, 10_000, beh);
                  });
        } else {
            sendSync(event, behavior);
        }
    }

    private void sendSync(OrderEvent event, FillBehaviorType behavior) {
        long corrId    = event.correlationId;
        long sessionId = event.sessionConnectionId;
        long orderQty  = event.nosDecoder.orderQty();
        long price     = event.nosDecoder.price();
        long fillPrice = event.fillInstructionDecoder.fillPrice();
        int  fillPct   = event.fillInstructionDecoder.fillPctBps();
        com.llexsimulator.sbe.OrderSide side = event.nosDecoder.side();

        // Use pre-allocated scratch arrays on event
        event.nosDecoder.getClOrdId(event.clOrdIdBytes, 0);
        event.nosDecoder.getSymbol(event.symbolBytes, 0);

        if (behavior == FillBehaviorType.REJECT) {
            sendReject(corrId, sessionId, event.clOrdIdBytes, event.symbolBytes, side,
                       orderQty, price, event.fillInstructionDecoder.rejectReasonCode());
        } else if (behavior == FillBehaviorType.PARTIAL_THEN_CANCEL) {
            long fillQty = orderQty * fillPct / 10_000L;
            sendFill(corrId, sessionId, event.clOrdIdBytes, event.symbolBytes, side,
                     orderQty, price, fillQty, fillPrice != 0 ? fillPrice : price, fillPct, behavior);
            sendCancel(corrId, sessionId, event.clOrdIdBytes, event.symbolBytes, side,
                       orderQty, price, fillQty);
        } else if (behavior == FillBehaviorType.NO_FILL_IOC_CANCEL) {
            sendCancel(corrId, sessionId, event.clOrdIdBytes, event.symbolBytes, side,
                       orderQty, price, 0L);
        } else {
            int legs = Math.max(1, event.fillInstructionDecoder.numPartialFills());
            long perLegQty = (orderQty * fillPct / 10_000L) / legs;
            for (int i = 0; i < legs; i++) {
                sendFill(corrId, sessionId, event.clOrdIdBytes, event.symbolBytes, side,
                         orderQty, price, perLegQty,
                         fillPrice != 0 ? fillPrice : price, fillPct, behavior);
            }
        }
    }

    private void sendFill(long corrId, long sessionId, byte[] clOrdId, byte[] symbol,
                          com.llexsimulator.sbe.OrderSide side,
                          long orderQty, long price, long fillQty, long fillPrice,
                          int fillPctBps, FillBehaviorType behavior) {
        OrderState state = orderRepository.get(corrId);
        SessionID  sid   = sessionRegistry.getSessionId(sessionId);
        if (sid == null) { log.warn("No session for connId={}", sessionId); return; }

        orderIdGen.nextId(orderIdBytes, 0);
        execIdGen.nextId(execIdBytes, 0);

        long cumQty    = (state != null) ? state.getCumQty() + fillQty : fillQty;
        long leavesQty = Math.max(0, orderQty - cumQty);
        boolean filled = leavesQty == 0;
        char fixSide   = side == com.llexsimulator.sbe.OrderSide.BUY ? '1' : '2';

        quickfix.fix44.ExecutionReport report = buildExecReport(
                clOrdId, orderIdBytes, execIdBytes, symbol, fixSide,
                orderQty, price, fillQty, fillPrice, cumQty, leavesQty,
                filled ? ExecType.FILL : ExecType.PARTIAL_FILL,
                filled ? OrdStatus.FILLED : OrdStatus.PARTIALLY_FILLED,
                0, behavior);

        trySend(report, sid);

        if (state != null) {
            state.setCumQty(cumQty);
            state.setLeavesQty(leavesQty);
            if (filled) orderRepository.release(corrId);
        }
    }

    private void sendReject(long corrId, long sessionId, byte[] clOrdId, byte[] symbol,
                            com.llexsimulator.sbe.OrderSide side,
                            long orderQty, long price,
                            com.llexsimulator.sbe.RejectReason reason) {
        SessionID sid = sessionRegistry.getSessionId(sessionId);
        if (sid == null) return;

        orderIdGen.nextId(orderIdBytes, 0);
        execIdGen.nextId(execIdBytes, 0);
        char fixSide = side == com.llexsimulator.sbe.OrderSide.BUY ? '1' : '2';

        quickfix.fix44.ExecutionReport report = buildExecReport(
                clOrdId, orderIdBytes, execIdBytes, symbol, fixSide,
                orderQty, price, 0L, 0L, 0L, orderQty,
                ExecType.REJECTED, OrdStatus.REJECTED,
                mapRejectReason(reason), FillBehaviorType.REJECT);

        trySend(report, sid);
        orderRepository.release(corrId);
    }

    private void sendCancel(long corrId, long sessionId, byte[] clOrdId, byte[] symbol,
                            com.llexsimulator.sbe.OrderSide side,
                            long orderQty, long price, long cumQty) {
        SessionID sid = sessionRegistry.getSessionId(sessionId);
        if (sid == null) return;

        orderIdGen.nextId(orderIdBytes, 0);
        execIdGen.nextId(execIdBytes, 0);
        char fixSide = side == com.llexsimulator.sbe.OrderSide.BUY ? '1' : '2';

        quickfix.fix44.ExecutionReport report = buildExecReport(
                clOrdId, orderIdBytes, execIdBytes, symbol, fixSide,
                orderQty, price, 0L, 0L, cumQty, 0L,
                ExecType.CANCELED, OrdStatus.CANCELED,
                0, FillBehaviorType.PARTIAL_THEN_CANCEL);

        trySend(report, sid);
        orderRepository.release(corrId);
    }

    private quickfix.fix44.ExecutionReport buildExecReport(
            byte[] clOrdId, byte[] orderId, byte[] execId, byte[] symbol,
            char side, long orderQty, long price, long lastQty, long lastPx,
            long cumQty, long leavesQty,
            ExecType execType, OrdStatus ordStatus,
            int ordRejReason, FillBehaviorType behavior) {

        // QuickFIX/J allocates here — unavoidable; everything upstream is zero-GC
        quickfix.fix44.ExecutionReport er = new quickfix.fix44.ExecutionReport(
                new OrderID(bytesToTrimmedString(orderId, 36)),
                new ExecID(bytesToTrimmedString(execId, 36)),
                new quickfix.field.ExecType(mapExecType(execType)),
                new quickfix.field.OrdStatus(mapOrdStatus(ordStatus)),
                new Side(side),
                new LeavesQty(leavesQty / 10_000.0),
                new CumQty(cumQty / 10_000.0),
                new AvgPx(cumQty > 0 ? (lastPx / 100_000_000.0) : 0.0)
        );
        er.set(new ClOrdID(bytesToTrimmedString(clOrdId, 36)));
        er.set(new Symbol(bytesToTrimmedString(symbol, 16)));
        er.set(new OrderQty(orderQty / 10_000.0));
        if (price > 0)   er.set(new Price(price / 100_000_000.0));
        if (lastQty > 0) er.set(new LastQty(lastQty / 10_000.0));
        if (lastPx > 0)  er.set(new LastPx(lastPx / 100_000_000.0));
        er.set(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
        if (ordRejReason > 0) er.set(new OrdRejReason(ordRejReason));
        // Custom tag 9871: fill behavior name for UI display
        er.setString(9871, behavior.name());
        return er;
    }

    private void trySend(quickfix.Message report, SessionID sid) {
        try {
            Session.sendToTarget(report, sid);
        } catch (SessionNotFound e) {
            log.warn("Session not found when sending ExecutionReport: {}", sid);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String bytesToTrimmedString(byte[] bytes, int len) {
        int end = len;
        while (end > 0 && (bytes[end - 1] == 0 || bytes[end - 1] == ' ')) end--;
        return new String(bytes, 0, end, StandardCharsets.US_ASCII);
    }

    private static char mapExecType(ExecType t) {
        return switch (t) {
            case NEW           -> '0';
            case PARTIAL_FILL  -> '1';
            case FILL          -> '2';
            case DONE_FOR_DAY  -> '3';
            case CANCELED      -> '4';
            case REJECTED      -> '8';
            case TRADE         -> 'F';
            default            -> '0';
        };
    }

    private static char mapOrdStatus(OrdStatus s) {
        return switch (s) {
            case NEW              -> '0';
            case PARTIALLY_FILLED -> '1';
            case FILLED           -> '2';
            case DONE_FOR_DAY     -> '3';
            case CANCELED         -> '4';
            case REJECTED         -> '8';
            case PENDING_NEW      -> 'A';
            default               -> '0';
        };
    }

    private static int mapRejectReason(com.llexsimulator.sbe.RejectReason r) {
        return switch (r) {
            case UNKNOWN_SYMBOL   -> 0;
            case HALTED           -> 2;
            case INVALID_PRICE    -> 5;
            case NOT_AUTHORIZED   -> 6;
            case SIMULATOR_REJECT -> 99;
            default               -> 0;
        };
    }
}
