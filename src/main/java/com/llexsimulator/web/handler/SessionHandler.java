package com.llexsimulator.web.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llexsimulator.engine.FixConnection;
import com.llexsimulator.engine.OrderSessionRegistry;
import com.llexsimulator.engine.SessionDiagnosticsSnapshot;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import uk.co.real_logic.artio.session.Session;

import java.util.ArrayList;
import java.util.List;

/** Lists active FIX sessions and exposes disconnect. */
public final class SessionHandler {

    private final OrderSessionRegistry registry;
    private final ObjectMapper         mapper;

    public SessionHandler(OrderSessionRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper   = mapper;
    }

    public Handler<RoutingContext> list() {
        return ctx -> {
            try {
                List<SessionDiagnosticsSnapshot> sessions = new ArrayList<>();
                for (FixConnection connection : registry.getAllConnections()) {
                    sessions.add(connection.snapshot());
                }
                ctx.response()
                   .putHeader("Content-Type", "application/json")
                   .end(mapper.writeValueAsString(sessions));
            } catch (Exception e) {
                ctx.fail(500, e);
            }
        };
    }

    public Handler<RoutingContext> recentDisconnects() {
        return ctx -> {
            try {
                int limit = parseLimit(ctx.request().getParam("limit"));
                ctx.response()
                   .putHeader("Content-Type", "application/json")
                   .end(mapper.writeValueAsString(registry.getRecentDisconnects(limit)));
            } catch (Exception e) {
                ctx.fail(500, e);
            }
        };
    }

    public Handler<RoutingContext> disconnect() {
        return ctx -> {
            String sessionIdStr = ctx.pathParam("id");
            boolean found = false;
            for (FixConnection connection : registry.getAllConnections()) {
                if (connection.sessionKey().equals(sessionIdStr)) {
                    Session session = connection.session();
                    if (session != null) {
                        session.requestDisconnect();
                        found = true;
                    }
                    break;
                }
            }
            ctx.response()
               .putHeader("Content-Type", "application/json")
               .setStatusCode(found ? 200 : 404)
               .end("{\"disconnected\":" + found + "}");
        };
    }

    private static int parseLimit(String rawLimit) {
        if (rawLimit == null || rawLimit.isBlank()) {
            return 16;
        }
        try {
            return Integer.parseInt(rawLimit.trim());
        } catch (NumberFormatException e) {
            return 16;
        }
    }
}

