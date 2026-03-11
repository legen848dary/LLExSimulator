package com.llexsimulator.web.handler;

import com.llexsimulator.disruptor.DisruptorPipeline;
import com.llexsimulator.engine.OrderSessionRegistry;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/** Simple health check endpoint. */
public final class HealthHandler {

    private final OrderSessionRegistry registry;
    private final DisruptorPipeline    pipeline;

    public HealthHandler(OrderSessionRegistry registry, DisruptorPipeline pipeline) {
        this.registry = registry;
        this.pipeline = pipeline;
    }

    public Handler<RoutingContext> get() {
        return ctx -> {
            long remaining = pipeline.getRemainingCapacity();
            ctx.response()
               .putHeader("Content-Type", "application/json")
               .end("{\"status\":\"UP\""
                    + ",\"fixSessions\":" + registry.activeCount()
                    + ",\"disruptorRemainingCapacity\":" + remaining
                    + "}");
        };
    }
}

