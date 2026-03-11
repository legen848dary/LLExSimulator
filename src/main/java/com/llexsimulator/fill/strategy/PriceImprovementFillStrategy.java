package com.llexsimulator.fill.strategy;

import com.llexsimulator.disruptor.OrderEvent;
import com.llexsimulator.fill.FillBehaviorConfig;
import com.llexsimulator.fill.FillStrategy;
import com.llexsimulator.sbe.FillBehaviorType;
import com.llexsimulator.sbe.OrderSide;
import com.llexsimulator.sbe.RejectReason;

/**
 * Fills at a price improved by {@code priceImprovementBps} basis points relative to the limit:
 * <ul>
 *   <li>BUY: fills at limit − improvement (cheaper for buyer)</li>
 *   <li>SELL: fills at limit + improvement (richer for seller)</li>
 * </ul>
 */
public final class PriceImprovementFillStrategy implements FillStrategy {
    @Override
    public void apply(OrderEvent event, FillBehaviorConfig config) {
        long limitPrice = event.nosDecoder.price();
        long delta      = (limitPrice * config.priceImprovementBps) / 10_000L;
        long fillPrice  = (event.nosDecoder.side() == OrderSide.BUY)
                          ? limitPrice - delta
                          : limitPrice + delta;

        event.fillInstructionEncoder
                .correlationId(event.correlationId)
                .fillBehavior(FillBehaviorType.PRICE_IMPROVEMENT)
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

