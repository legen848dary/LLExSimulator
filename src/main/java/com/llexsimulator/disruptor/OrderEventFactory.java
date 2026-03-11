package com.llexsimulator.disruptor;

import com.lmax.disruptor.EventFactory;

/**
 * Allocates all {@link OrderEvent} slots in the ring buffer exactly once at startup.
 * After this, no heap allocation occurs for order processing.
 */
public final class OrderEventFactory implements EventFactory<OrderEvent> {
    @Override
    public OrderEvent newInstance() {
        return new OrderEvent();
    }
}

