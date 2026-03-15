package com.llexsimulator.disruptor.handler;

import com.llexsimulator.disruptor.OrderEvent;
import com.llexsimulator.engine.FixConnection;
import com.llexsimulator.engine.FixOutboundSender;
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

import java.util.concurrent.locks.LockSupport;

/**
 * Stage 3: builds and sends the FIX {@code ExecutionReport} (35=8) back to the client.
 */
public final class ExecutionReportHandler implements EventHandler<OrderEvent> {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReportHandler.class);
    private static final String EVENT_PARTIAL_FILL = "PARTIAL_FILL/PARTIALLY_FILLED";
    private static final String EVENT_FILL = "FILL/FILLED";
    private static final String EVENT_REJECT = "REJECTED/REJECTED";
    private static final String EVENT_CANCEL = "CANCELED/CANCELED";

    private final OrderSessionRegistry sessionRegistry;
    private final OrderRepository      orderRepository;
    private final OrderIdGenerator     orderIdGen;
    private final ExecIdGenerator      execIdGen;
    private final FixOutboundSender    outboundSender;

    private final ThreadLocal<SendContext> sendContext = ThreadLocal.withInitial(SendContext::new);

    public ExecutionReportHandler(OrderSessionRegistry sessionRegistry,
                                  OrderRepository orderRepository,
                                  FixOutboundSender outboundSender) {
        this.sessionRegistry = sessionRegistry;
        this.orderRepository = orderRepository;
        this.orderIdGen      = new OrderIdGenerator("O", System.nanoTime());
        this.execIdGen       = new ExecIdGenerator();
        this.outboundSender  = outboundSender;
    }

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        FillBehaviorType behavior = event.fillInstructionDecoder.fillBehavior();
        long delayNs = event.fillInstructionDecoder.delayNs();

        if (delayNs > 0) {
            // Capture primitives for async send — snapshot off the event buffers
            long corrId              = event.correlationId;
            long sessionId           = event.sessionConnectionId;
            long fillPrice           = event.fillInstructionDecoder.fillPrice();
            int  fillPctBps          = event.fillInstructionDecoder.fillPctBps();
            int  numPartialFills     = Math.max(1, event.fillInstructionDecoder.numPartialFills());
            long orderQty            = event.nosDecoder.orderQty();
            long price               = event.nosDecoder.price();
            com.llexsimulator.sbe.OrderSide side = event.nosDecoder.side();
            // small heap alloc — off critical path (delayed fill)
            byte[] clOrdId = new byte[36];
            event.nosDecoder.getClOrdId(clOrdId, 0);
            byte[] symbol = new byte[16];
            event.nosDecoder.getSymbol(symbol, 0);

            Thread.ofVirtual()
                  .name("delayed-fill-" + corrId)
                  .start(() -> {
                      LockSupport.parkNanos(delayNs);
                      processBehavior(corrId, sessionId, clOrdId, symbol, side,
                              orderQty, price, fillPrice, fillPctBps, numPartialFills, behavior,
                              com.llexsimulator.sbe.RejectReason.SIMULATOR_REJECT);
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
        int  legs      = Math.max(1, event.fillInstructionDecoder.numPartialFills());
        com.llexsimulator.sbe.OrderSide side = event.nosDecoder.side();

        // Use pre-allocated scratch arrays on event
        event.nosDecoder.getClOrdId(event.clOrdIdBytes, 0);
        event.nosDecoder.getSymbol(event.symbolBytes, 0);

        processBehavior(corrId, sessionId, event.clOrdIdBytes, event.symbolBytes, side,
                orderQty, price, fillPrice, fillPct, legs, behavior,
                event.fillInstructionDecoder.rejectReasonCode());
    }

    private void processBehavior(long corrId, long sessionId, byte[] clOrdId, byte[] symbol,
                                 com.llexsimulator.sbe.OrderSide side,
                                 long orderQty, long price, long fillPrice, int fillPct,
                                 int legs, FillBehaviorType behavior,
                                 com.llexsimulator.sbe.RejectReason rejectReason) {
        if (behavior == FillBehaviorType.REJECT) {
            sendReject(corrId, sessionId, clOrdId, symbol, side,
                    orderQty, price, rejectReason);
            return;
        }

        if (behavior == FillBehaviorType.PARTIAL_THEN_CANCEL) {
            long fillQty = Math.max(0L, orderQty * fillPct / 10_000L);
            sendFill(corrId, sessionId, clOrdId, symbol, side,
                    orderQty, price, fillQty, fillPrice != 0 ? fillPrice : price,
                    false);
            sendCancel(corrId, sessionId, clOrdId, symbol, side, orderQty, price, fillQty);
            return;
        }

        if (behavior == FillBehaviorType.NO_FILL_IOC_CANCEL) {
            sendCancel(corrId, sessionId, clOrdId, symbol, side, orderQty, price, 0L);
            return;
        }

        long totalFillQty = Math.max(0L, orderQty * fillPct / 10_000L);
        int  effectiveLegs = Math.max(1, legs);
        long sentQty = 0L;

        for (int i = 0; i < effectiveLegs; i++) {
            long remaining = totalFillQty - sentQty;
            long fillQty = (i == effectiveLegs - 1)
                    ? Math.max(0L, remaining)
                    : Math.max(0L, totalFillQty / effectiveLegs);
            sentQty += fillQty;
            boolean terminalAction = i == effectiveLegs - 1;

            sendFill(corrId, sessionId, clOrdId, symbol, side,
                    orderQty, price, fillQty, fillPrice != 0 ? fillPrice : price,
                    terminalAction);
        }
    }

    private void sendFill(long corrId, long sessionId, byte[] clOrdId, byte[] symbol,
                          com.llexsimulator.sbe.OrderSide side,
                          long orderQty, long price, long fillQty, long fillPrice,
                          boolean terminalAction) {
        OrderState state = orderRepository.get(corrId);
        FixConnection connection = sessionRegistry.get(sessionId);
        if (connection == null || connection.session() == null) {
            log.warn("No session for connId={} (correlationId={})", sessionId, corrId);
            orderRepository.release(corrId);
            return;
        }

        SendContext sendContext = this.sendContext.get();
        int orderIdLength = orderIdGen.nextId(sendContext.orderIdBytes, 0);
        int execIdLength = execIdGen.nextId(sendContext.execIdBytes, 0);

        long cumQty    = (state != null) ? state.getCumQty() + fillQty : fillQty;
        long leavesQty = Math.max(0, orderQty - cumQty);
        boolean filled = leavesQty == 0;
        char fixSide   = side == com.llexsimulator.sbe.OrderSide.BUY ? '1' : '2';

        sendExecutionReport(connection,
                clOrdId, trimmedLength(clOrdId, 36),
                sendContext.orderIdBytes, orderIdLength,
                sendContext.execIdBytes, execIdLength,
                symbol, fixSide,
                orderQty, price, fillQty, fillPrice, cumQty, leavesQty,
                filled ? ExecType.FILL : ExecType.PARTIAL_FILL,
                filled ? OrdStatus.FILLED : OrdStatus.PARTIALLY_FILLED,
                0);

        if (state != null) {
            state.setCumQty(cumQty);
            state.setLeavesQty(leavesQty);
            if (filled || terminalAction) orderRepository.release(corrId);
        }
    }

    private void sendReject(long corrId, long sessionId, byte[] clOrdId, byte[] symbol,
                            com.llexsimulator.sbe.OrderSide side,
                            long orderQty, long price,
                            com.llexsimulator.sbe.RejectReason reason) {
        FixConnection connection = sessionRegistry.get(sessionId);
        if (connection == null || connection.session() == null) {
            log.warn("No session for reject connId={} (correlationId={})", sessionId, corrId);
            orderRepository.release(corrId);
            return;
        }

        SendContext sendContext = this.sendContext.get();
        int orderIdLength = orderIdGen.nextId(sendContext.orderIdBytes, 0);
        int execIdLength = execIdGen.nextId(sendContext.execIdBytes, 0);
        char fixSide = side == com.llexsimulator.sbe.OrderSide.BUY ? '1' : '2';

        sendExecutionReport(connection,
                clOrdId, trimmedLength(clOrdId, 36),
                sendContext.orderIdBytes, orderIdLength,
                sendContext.execIdBytes, execIdLength,
                symbol, fixSide,
                orderQty, price, 0L, 0L, 0L, orderQty,
                ExecType.REJECTED, OrdStatus.REJECTED,
                mapRejectReason(reason));
        orderRepository.release(corrId);
    }

    private void sendCancel(long corrId, long sessionId, byte[] clOrdId, byte[] symbol,
                            com.llexsimulator.sbe.OrderSide side,
                            long orderQty, long price, long cumQty) {
        FixConnection connection = sessionRegistry.get(sessionId);
        if (connection == null || connection.session() == null) {
            log.warn("No session for cancel connId={} (correlationId={})", sessionId, corrId);
            orderRepository.release(corrId);
            return;
        }

        SendContext sendContext = this.sendContext.get();
        int orderIdLength = orderIdGen.nextId(sendContext.orderIdBytes, 0);
        int execIdLength = execIdGen.nextId(sendContext.execIdBytes, 0);
        char fixSide = side == com.llexsimulator.sbe.OrderSide.BUY ? '1' : '2';

        sendExecutionReport(connection,
                clOrdId, trimmedLength(clOrdId, 36),
                sendContext.orderIdBytes, orderIdLength,
                sendContext.execIdBytes, execIdLength,
                symbol, fixSide,
                orderQty, price, 0L, 0L, cumQty, 0L,
                ExecType.CANCELED, OrdStatus.CANCELED,
                0);
        orderRepository.release(corrId);
    }

    private void sendExecutionReport(
            FixConnection connection,
            byte[] clOrdId, int clOrdIdLength,
            byte[] orderId, int orderIdLength,
            byte[] execId, int execIdLength,
            byte[] symbol,
            char side, long orderQty, long price, long lastQty, long lastPx,
            long cumQty, long leavesQty,
            ExecType execType, OrdStatus ordStatus,
            int ordRejReason) {

        outboundSender.enqueueExecutionReport(
                connection,
                outboundEvent(execType, ordStatus),
                clOrdId, clOrdIdLength,
                orderId, orderIdLength,
                execId, execIdLength,
                symbol, trimmedLength(symbol, 16),
                side, orderQty, price, lastQty, lastPx,
                cumQty, leavesQty,
                mapExecType(execType), mapOrdStatus(ordStatus),
                ordRejReason);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

    private static int trimmedLength(byte[] bytes, int maxLength) {
        int end = maxLength;
        while (end > 0 && (bytes[end - 1] == 0 || bytes[end - 1] == ' ')) {
            end--;
        }
        return end;
    }

    private static String outboundEvent(ExecType execType, OrdStatus ordStatus) {
        return switch (execType) {
            case PARTIAL_FILL -> EVENT_PARTIAL_FILL;
            case FILL -> EVENT_FILL;
            case REJECTED -> EVENT_REJECT;
            case CANCELED -> EVENT_CANCEL;
            default -> switch (ordStatus) {
                case PARTIALLY_FILLED -> EVENT_PARTIAL_FILL;
                case FILLED -> EVENT_FILL;
                case REJECTED -> EVENT_REJECT;
                case CANCELED -> EVENT_CANCEL;
                default -> EVENT_FILL;
            };
        };
    }

    private static final class SendContext {
        private final byte[] orderIdBytes = new byte[36];
        private final byte[] execIdBytes = new byte[36];
    }
}
