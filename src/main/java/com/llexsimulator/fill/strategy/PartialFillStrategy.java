package com.llexsimulator.fill.strategy;

import com.llexsimulator.disruptor.OrderEvent;
import com.llexsimulator.fill.FillBehaviorConfig;
import com.llexsimulator.fill.FillStrategy;
import com.llexsimulator.sbe.FillBehaviorType;
import com.llexsimulator.sbe.RejectReason;

/** Fills a configurable percentage of the order in N legs. */
public final class PartialFillStrategy implements FillStrategy {
    @Override
    public void apply(OrderEvent event, FillBehaviorConfig config) {
        event.fillInstructionEncoder
                .correlationId(event.correlationId)
                .fillBehavior(FillBehaviorType.PARTIAL_FILL)
                .fillPctBps(config.fillPctBps)
                .numPartialFills((short) Math.max(1, config.numPartialFills))
                .delayNs(0L)
                .fillPrice(event.nosDecoder.price())
                .rejectReasonCode(RejectReason.SIMULATOR_REJECT)
                .randomMinQtyPct(0)
                .randomMaxQtyPct(0)
                .randomMinDelayNs(0L)
                .randomMaxDelayNs(0L);
    }
}

