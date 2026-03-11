package com.llexsimulator.fill;

import com.llexsimulator.sbe.FillBehaviorType;
import com.llexsimulator.sbe.RejectReason;

/**
 * Mutable, volatile-field configuration for the active fill-behavior strategy.
 *
 * <p>A single {@code volatile} reference to this object is held by
 * {@code FillStrategyHandler}; a new instance is swapped in atomically when
 * the user activates a different profile via the REST API. This guarantees
 * that the Disruptor hot path sees a consistent snapshot with a single
 * volatile read — no locking, no synchronization.
 */
public final class FillBehaviorConfig {

    // ── strategy selector ────────────────────────────────────────────────────
    public volatile FillBehaviorType behaviorType = FillBehaviorType.IMMEDIATE_FULL_FILL;

    // ── partial fill params ──────────────────────────────────────────────────
    /** Fill percentage in basis points (0 = 0%, 10000 = 100%). */
    public volatile int  fillPctBps      = 10_000;
    public volatile int  numPartialFills = 1;

    // ── delayed fill params ──────────────────────────────────────────────────
    public volatile long delayNs = 0L;

    // ── price override ───────────────────────────────────────────────────────
    /** 0 means "use order price". Encoded as price × 10^8. */
    public volatile long fillPriceOverride = 0L;

    // ── reject params ────────────────────────────────────────────────────────
    public volatile RejectReason rejectReason = RejectReason.SIMULATOR_REJECT;

    // ── random fill params ───────────────────────────────────────────────────
    public volatile int  randomMinQtyPctBps = 5_000;
    public volatile int  randomMaxQtyPctBps = 10_000;
    public volatile long randomMinDelayNs   = 0L;
    public volatile long randomMaxDelayNs   = 1_000_000L; // 1 ms default

    // ── price-improvement params ─────────────────────────────────────────────
    /** Price improvement in basis points applied to the limit price. */
    public volatile int priceImprovementBps = 10;

    /**
     * Creates a config from a DTO, used when saving/activating a profile via REST.
     * Called on the Vert.x event-loop thread (not on the Disruptor thread).
     */
    public static FillBehaviorConfig fromDto(com.llexsimulator.web.dto.FillProfileDto dto) {
        FillBehaviorConfig c = new FillBehaviorConfig();
        c.behaviorType       = FillBehaviorType.valueOf(dto.behaviorType);
        c.fillPctBps         = dto.fillPctBps;
        c.numPartialFills    = dto.numPartialFills;
        c.delayNs            = dto.delayMs * 1_000_000L;
        c.rejectReason       = dto.rejectReason != null
                               ? RejectReason.valueOf(dto.rejectReason)
                               : RejectReason.SIMULATOR_REJECT;
        c.randomMinQtyPctBps = dto.randomMinQtyPct * 100;
        c.randomMaxQtyPctBps = dto.randomMaxQtyPct * 100;
        c.randomMinDelayNs   = dto.randomMinDelayMs * 1_000_000L;
        c.randomMaxDelayNs   = dto.randomMaxDelayMs * 1_000_000L;
        c.priceImprovementBps = dto.priceImprovementBps;
        return c;
    }
}

