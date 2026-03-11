package com.llexsimulator.fill.strategy;

import com.llexsimulator.disruptor.OrderEvent;
import com.llexsimulator.fill.FillBehaviorConfig;
import com.llexsimulator.fill.FillStrategy;
import com.llexsimulator.sbe.FillBehaviorType;
import com.llexsimulator.sbe.RejectReason;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomizes both fill quantity (within configured bps range) and
 * fill delay (within configured nanosecond range).
 *
 * <p>Uses {@link ThreadLocalRandom} exclusively — zero allocation, no lock contention.
 */
public final class RandomFillStrategy implements FillStrategy {
    @Override
    public void apply(OrderEvent event, FillBehaviorConfig config) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int  fillPct  = (config.randomMinQtyPctBps >= config.randomMaxQtyPctBps)
                        ? config.randomMaxQtyPctBps
                        : rng.nextInt(config.randomMinQtyPctBps, config.randomMaxQtyPctBps + 1);
        long delayNs  = (config.randomMinDelayNs >= config.randomMaxDelayNs)
                        ? config.randomMaxDelayNs
                        : rng.nextLong(config.randomMinDelayNs, config.randomMaxDelayNs + 1);

        event.fillInstructionEncoder
                .correlationId(event.correlationId)
                .fillBehavior(FillBehaviorType.RANDOM_FILL)
                .fillPctBps(fillPct)
                .numPartialFills((short) 1)
                .delayNs(delayNs)
                .fillPrice(event.nosDecoder.price())
                .rejectReasonCode(RejectReason.SIMULATOR_REJECT)
                .randomMinQtyPct(config.randomMinQtyPctBps)
                .randomMaxQtyPct(config.randomMaxQtyPctBps)
                .randomMinDelayNs(config.randomMinDelayNs)
                .randomMaxDelayNs(config.randomMaxDelayNs);
    }
}

