package com.llexsimulator.web.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llexsimulator.fill.FillProfileManager;
import com.llexsimulator.metrics.MetricsRegistry;
import com.llexsimulator.web.dto.StatisticsDto;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/** Serves the current metrics snapshot as JSON. */
public final class StatisticsHandler {

    private final MetricsRegistry  registry;
    private final FillProfileManager profileManager;
    private final ObjectMapper     mapper;

    public StatisticsHandler(MetricsRegistry registry, FillProfileManager profileManager,
                              ObjectMapper mapper) {
        this.registry       = registry;
        this.profileManager = profileManager;
        this.mapper         = mapper;
    }

    public Handler<RoutingContext> get() {
        return ctx -> {
            try {
                writeDto(ctx, buildDto());
            } catch (Exception e) {
                ctx.fail(500, e);
            }
        };
    }

    public Handler<RoutingContext> reset() {
        return ctx -> {
            try {
                registry.reset();
                writeDto(ctx, buildDto());
            } catch (Exception e) {
                ctx.fail(500, e);
            }
        };
    }

    private StatisticsDto buildDto() {
        StatisticsDto dto = new StatisticsDto();
        dto.ordersReceived   = registry.getOrdersReceived();
        dto.execReportsSent  = registry.getExecReportsSent();
        dto.fillsSent        = registry.getFillsSent();
        dto.rejectsSent      = registry.getRejectsSent();
        dto.cancelsSent      = registry.getCancelsSent();
        dto.p80LatencyUs     = registry.getP80Ns() / 1000;
        dto.p90LatencyUs     = registry.getP90Ns() / 1000;
        dto.p99LatencyUs     = registry.getP99Ns() / 1000;
        dto.throughputPerSec = registry.getThroughputPerSec();
        dto.fillRatePct      = dto.ordersReceived > 0
                               ? dto.fillsSent * 100.0 / dto.ordersReceived : 0.0;
        dto.activeProfile    = profileManager.getActiveProfileName();
        return dto;
    }

    private void writeDto(RoutingContext ctx, StatisticsDto dto) throws Exception {
        ctx.response()
           .putHeader("Content-Type", "application/json")
           .end(mapper.writeValueAsString(dto));
    }
}

