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
                StatisticsDto dto = new StatisticsDto();
                dto.ordersReceived   = registry.getOrdersReceived();
                dto.execReportsSent  = registry.getExecReportsSent();
                dto.fillsSent        = registry.getFillsSent();
                dto.rejectsSent      = registry.getRejectsSent();
                dto.p50LatencyUs     = registry.getP50Ns()  / 1000;
                dto.p99LatencyUs     = registry.getP99Ns()  / 1000;
                dto.p999LatencyUs    = registry.getP999Ns() / 1000;
                dto.maxLatencyUs     = registry.getMaxLatencyNs() / 1000;
                dto.throughputPerSec = registry.getThroughputPerSec();
                dto.fillRatePct      = dto.ordersReceived > 0
                                       ? dto.fillsSent * 100.0 / dto.ordersReceived : 0.0;
                dto.activeProfile    = profileManager.getActiveProfileName();

                ctx.response()
                   .putHeader("Content-Type", "application/json")
                   .end(mapper.writeValueAsString(dto));
            } catch (Exception e) {
                ctx.fail(500, e);
            }
        };
    }
}

