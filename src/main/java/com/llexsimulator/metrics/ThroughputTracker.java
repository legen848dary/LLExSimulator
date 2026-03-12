package com.llexsimulator.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Sliding 1-second window throughput tracker.
 *
 * <p>Uses two {@link LongAdder} slots (current and previous). A dedicated
 * virtual thread swaps them every second. {@link #getPerSecond()} returns
 * the previous window's count — zero allocation, no locking.
 */
public final class ThroughputTracker {

    private final LongAdder   currentWindow  = new LongAdder();
    private final AtomicLong  previousWindow = new AtomicLong(0L);

    public ThroughputTracker() {
        Thread.ofVirtual().name("throughput-tracker").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                previousWindow.set(currentWindow.sumThenReset());
            }
        });
    }

    public void increment()         { currentWindow.increment(); }
    public long getPerSecond()      { return previousWindow.get(); }

    public void reset() {
        currentWindow.reset();
        previousWindow.set(0L);
    }
}

