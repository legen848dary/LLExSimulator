package com.llexsimulator.fill.strategy;

import com.llexsimulator.disruptor.OrderEvent;
import com.llexsimulator.fill.FillBehaviorConfig;
import com.llexsimulator.fill.FillStrategy;
import com.llexsimulator.sbe.FillBehaviorType;
import com.llexsimulator.sbe.RejectReason;

/** Immediately cancels IOC/FOK orders with zero fill. */
public final class IocCancelStrategy implements FillStrategy {
    @Override
    public void apply(OrderEvent event, FillBehaviorConfig config) {
        event.fillInstructionEncoder
                .correlationId(event.correlationId)
                .fillBehavior(FillBehaviorType.NO_FILL_IOC_CANCEL)
                .fillPctBps(0)
                .numPartialFills((short) 0)
                .delayNs(0L)
                .fillPrice(0L)
                .rejectReasonCode(RejectReason.SIMULATOR_REJECT)
                .randomMinQtyPct(0)
                .randomMaxQtyPct(0)
                .randomMinDelayNs(0L)
                .randomMaxDelayNs(0L);
    }
}

