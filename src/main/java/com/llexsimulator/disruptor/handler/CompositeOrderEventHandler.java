package com.llexsimulator.disruptor.handler;

import com.llexsimulator.disruptor.OrderEvent;
import com.lmax.disruptor.EventHandler;

/**
 * Runs the full order-processing pipeline on a single Disruptor consumer thread.
 *
 * <p>This preserves the logical stage ordering while avoiding cross-thread handoff
 * between validation, fill-strategy selection, execution-report generation, and
 * metrics accounting.
 */
public final class CompositeOrderEventHandler implements EventHandler<OrderEvent> {

    private final ValidationHandler validationHandler;
    private final FillStrategyHandler fillStrategyHandler;
    private final ExecutionReportHandler executionReportHandler;
    private final MetricsPublishHandler metricsPublishHandler;

    public CompositeOrderEventHandler(
            ValidationHandler validationHandler,
            FillStrategyHandler fillStrategyHandler,
            ExecutionReportHandler executionReportHandler,
            MetricsPublishHandler metricsPublishHandler
    ) {
        this.validationHandler = validationHandler;
        this.fillStrategyHandler = fillStrategyHandler;
        this.executionReportHandler = executionReportHandler;
        this.metricsPublishHandler = metricsPublishHandler;
    }

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        validationHandler.onEvent(event, sequence, endOfBatch);
        fillStrategyHandler.onEvent(event, sequence, endOfBatch);
        executionReportHandler.onEvent(event, sequence, endOfBatch);
        metricsPublishHandler.onEvent(event, sequence, endOfBatch);
    }
}

