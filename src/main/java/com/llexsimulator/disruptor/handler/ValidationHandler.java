package com.llexsimulator.disruptor.handler;

import com.llexsimulator.disruptor.OrderEvent;
import com.llexsimulator.sbe.FillBehaviorType;
import com.llexsimulator.sbe.OrderSide;
import com.llexsimulator.sbe.OrderType;
import com.llexsimulator.sbe.RejectReason;
import com.lmax.disruptor.EventHandler;

/**
 * Stage 1: validates the {@link OrderEvent} and sets {@code event.isValid}.
 *
 * <p>If invalid, writes a REJECT {@code FillInstruction} into the event's
 * fill buffer so downstream handlers can still emit a proper Reject report.
 * Zero allocation.
 */
public final class ValidationHandler implements EventHandler<OrderEvent> {

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        // Basic field validation — all reads from off-heap SBE flyweight
        boolean valid = true;
        String  reason = null;

        if (event.nosDecoder.side() == OrderSide.NULL_VAL) {
            valid = false; reason = "Missing Side";
        } else if (event.nosDecoder.orderQty() <= 0) {
            valid = false; reason = "Invalid OrderQty";
        } else if (event.nosDecoder.orderType() == OrderType.LIMIT && event.nosDecoder.price() <= 0) {
            valid = false; reason = "LIMIT order missing Price";
        }

        event.isValid = valid;

        if (!valid) {
            // Pre-populate a REJECT fill instruction so ExecutionReportHandler
            // can send the rejection without re-examining the failure reason.
            event.fillInstructionEncoder
                    .wrapAndApplyHeader(event.fillInstructionBuffer, 0, event.headerEncoder)
                    .correlationId(event.correlationId)
                    .fillBehavior(FillBehaviorType.REJECT)
                    .fillPctBps(0)
                    .numPartialFills((short) 0)
                    .delayNs(0L)
                    .fillPrice(0L)
                    .rejectReasonCode(RejectReason.INVALID_PRICE)
                    .randomMinQtyPct(0)
                    .randomMaxQtyPct(0)
                    .randomMinDelayNs(0L)
                    .randomMaxDelayNs(0L);

            // Re-wrap decoder for downstream
            event.fillInstructionDecoder.wrapAndApplyHeader(
                    event.fillInstructionBuffer, 0, event.headerDecoder);
        }
    }
}

