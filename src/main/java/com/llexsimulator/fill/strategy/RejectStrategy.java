package com.llexsimulator.fill.strategy;

import com.llexsimulator.disruptor.OrderEvent;
import com.llexsimulator.fill.FillBehaviorConfig;
import com.llexsimulator.fill.FillStrategy;
import com.llexsimulator.sbe.FillBehaviorType;

/** Rejects the order with a configurable reason code. */
public final class RejectStrategy implements FillStrategy {
    @Override
    public void apply(OrderEvent event, FillBehaviorConfig config) {
        event.fillInstructionEncoder
                .correlationId(event.correlationId)
                .fillBehavior(FillBehaviorType.REJECT)
                .fillPctBps(0)
                .numPartialFills((short) 0)
                .delayNs(0L)
                .fillPrice(0L)
                .rejectReasonCode(config.rejectReason)
                .randomMinQtyPct(0)
                .randomMaxQtyPct(0)
                .randomMinDelayNs(0L)
                .randomMaxDelayNs(0L);
    }
}

