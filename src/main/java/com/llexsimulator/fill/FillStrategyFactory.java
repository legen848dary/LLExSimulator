package com.llexsimulator.fill;

import com.llexsimulator.fill.strategy.*;
import com.llexsimulator.sbe.FillBehaviorType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Singleton registry of all {@link FillStrategy} implementations.
 * Each strategy instance is pre-created once at startup — zero allocation on the hot path.
 */
public final class FillStrategyFactory {

    private static final Map<FillBehaviorType, FillStrategy> STRATEGIES =
            new EnumMap<>(FillBehaviorType.class);

    static {
        STRATEGIES.put(FillBehaviorType.IMMEDIATE_FULL_FILL,   new ImmediateFillStrategy());
        STRATEGIES.put(FillBehaviorType.PARTIAL_FILL,          new PartialFillStrategy());
        STRATEGIES.put(FillBehaviorType.DELAYED_FILL,          new DelayedFillStrategy());
        STRATEGIES.put(FillBehaviorType.REJECT,                new RejectStrategy());
        STRATEGIES.put(FillBehaviorType.PARTIAL_THEN_CANCEL,   new PartialFillThenCancelStrategy());
        STRATEGIES.put(FillBehaviorType.PRICE_IMPROVEMENT,     new PriceImprovementFillStrategy());
        STRATEGIES.put(FillBehaviorType.FILL_AT_ARRIVAL_PRICE, new FillAtArrivalPriceStrategy());
        STRATEGIES.put(FillBehaviorType.RANDOM_FILL,           new RandomFillStrategy());
        STRATEGIES.put(FillBehaviorType.NO_FILL_IOC_CANCEL,    new IocCancelStrategy());
    }

    private FillStrategyFactory() {}

    public static FillStrategy getStrategy(FillBehaviorType type) {
        FillStrategy s = STRATEGIES.get(type);
        if (s == null) {
            throw new IllegalArgumentException("No strategy registered for: " + type);
        }
        return s;
    }
}

