package com.llexsimulator.fill.strategy;

import com.llexsimulator.disruptor.OrderEvent;
import com.llexsimulator.fill.FillBehaviorConfig;
import com.llexsimulator.fill.FillStrategy;
import com.llexsimulator.sbe.FillBehaviorType;
import com.llexsimulator.sbe.RejectReason;

/**
 * Fills at the price captured at order arrival time ({@code arrivalTimeNs} snapshot).
 * Useful for simulating market-impact scenarios where the execution price
 * equals the market price at the moment the order was received.
 */
public final class FillAtArrivalPriceStrategy implements FillStrategy {
    @Override
    public void apply(OrderEvent event, FillBehaviorConfig config) {
        // For this simulator, "arrival price" = the order's own limit price
        // since we don't have a live market feed. This can be overridden by
        // setting fillPriceOverride in the config.
        long fillPrice = config.fillPriceOverride != 0
                         ? config.fillPriceOverride
                         : event.nosDecoder.price();

        event.fillInstructionEncoder
                .correlationId(event.correlationId)
                .fillBehavior(FillBehaviorType.FILL_AT_ARRIVAL_PRICE)
                .fillPctBps(10_000)
                .numPartialFills((short) 1)
                .delayNs(0L)
                .fillPrice(fillPrice)
                .rejectReasonCode(RejectReason.SIMULATOR_REJECT)
                .randomMinQtyPct(0)
                .randomMaxQtyPct(0)
                .randomMinDelayNs(0L)
                .randomMaxDelayNs(0L);
    }
}

