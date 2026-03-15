package com.llexsimulator.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llexsimulator.disruptor.DisruptorPipeline;
import com.llexsimulator.engine.OrderSessionRegistry;
import com.llexsimulator.fill.FillProfileManager;
import com.llexsimulator.metrics.MetricsRegistry;
import com.llexsimulator.web.handler.*;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.core.Vertx;

/** Assembles all REST routes onto a single Vert.x {@link Router}. */
public final class RestApiRouter {

    private RestApiRouter() {}

    public static Router create(Vertx vertx,
                                MetricsRegistry     registry,
                                OrderSessionRegistry sessionRegistry,
                                FillProfileManager  profileManager,
                                DisruptorPipeline   pipeline) {

        ObjectMapper mapper = new ObjectMapper();

        FillProfileHandler fillHandler      = new FillProfileHandler(profileManager, mapper);
        StatisticsHandler  statsHandler     = new StatisticsHandler(registry, profileManager, mapper);
        SessionHandler     sessionHandler   = new SessionHandler(sessionRegistry, mapper);
        HealthHandler      healthHandler    = new HealthHandler(sessionRegistry, pipeline);
        BenchmarkReportsHandler benchmarkReportsHandler = new BenchmarkReportsHandler();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        // ── Health ────────────────────────────────────────────────────────────
        router.get("/api/health").handler(healthHandler.get());

        // ── Fill profiles ─────────────────────────────────────────────────────
        router.get("/api/fill-profiles").handler(fillHandler.list());
        router.post("/api/fill-profiles").handler(fillHandler.createOrUpdate());
        router.put("/api/fill-profiles/:name/activate").handler(fillHandler.activate());
        router.delete("/api/fill-profiles/:name").handler(fillHandler.delete());

        // ── Statistics ────────────────────────────────────────────────────────
        router.get("/api/statistics").handler(statsHandler.get());
        router.post("/api/statistics/reset").handler(statsHandler.reset());

        // ── Sessions ──────────────────────────────────────────────────────────
        router.get("/api/sessions").handler(sessionHandler.list());
        router.get("/api/sessions/recent-disconnects").handler(sessionHandler.recentDisconnects());
        router.delete("/api/sessions/:id").handler(sessionHandler.disconnect());

        // ── Benchmark Reports ──────────────────────────────────────────────────
        router.get("/reports").handler(benchmarkReportsHandler.index());
        router.get("/reports/:runId").handler(benchmarkReportsHandler.show());

        // ── Static SPA (Vue.js 3 + Tailwind) ─────────────────────────────────
        router.route("/*").handler(StaticHandler.create("web"));

        return router;
    }
}

