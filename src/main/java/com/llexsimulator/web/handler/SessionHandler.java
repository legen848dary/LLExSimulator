package com.llexsimulator.web.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llexsimulator.engine.OrderSessionRegistry;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import quickfix.Session;
import quickfix.SessionID;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                List<Map<String, Object>> sessions = new ArrayList<>();
                for (SessionID sid : registry.getAllSessionIds()) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("sessionId",    sid.toString());
                    info.put("beginString",  sid.getBeginString());
                    info.put("senderCompId", sid.getSenderCompID());
                    info.put("targetCompId", sid.getTargetCompID());
                    Session s = Session.lookupSession(sid);
                    info.put("loggedOn",  s != null && s.isLoggedOn());
                    info.put("msgCount",  s != null ? s.getExpectedSenderNum() - 1 : 0);
                    sessions.add(info);
                }
                ctx.response()
                   .putHeader("Content-Type", "application/json")
                   .end(mapper.writeValueAsString(sessions));
            } catch (Exception e) {
                ctx.fail(500, e);
            }
        };
    }

    public Handler<RoutingContext> disconnect() {
        return ctx -> {
            String sessionIdStr = ctx.pathParam("id");
            // Find matching session and disconnect
            boolean found = false;
            for (SessionID sid : registry.getAllSessionIds()) {
                if (sid.toString().equals(sessionIdStr)) {
                    Session s = Session.lookupSession(sid);
                    if (s != null) {
                        s.logout("Disconnected via REST API");
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
}

