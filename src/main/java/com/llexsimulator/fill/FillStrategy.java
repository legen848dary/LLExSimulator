package com.llexsimulator.fill;

import com.llexsimulator.disruptor.OrderEvent;

/**
 * Strategy interface for fill behavior.
 *
 * <p>Implementations MUST be stateless and allocation-free — a single instance
 * is shared across all Disruptor processing cycles. All mutable per-call state
 * is written into {@code event.fillInstructionBuffer} via the pre-allocated
 * SBE {@code FillInstructionEncoder}.
 */
@FunctionalInterface
public interface FillStrategy {

    /**
     * Populate the {@code FillInstruction} in the event's fill buffer
     * according to this strategy and the supplied config snapshot.
     *
     * @param event  the current Disruptor ring-buffer slot (pre-allocated, reused)
     * @param config a volatile read of the current fill-behavior configuration
     */
    void apply(OrderEvent event, FillBehaviorConfig config);
}

