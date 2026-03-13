package com.llexsimulator.web.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llexsimulator.engine.FixConnection;
import com.llexsimulator.engine.OrderSessionRegistry;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.session.Session;

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
                for (FixConnection connection : registry.getAllConnections()) {
                    Map<String, Object> info = new HashMap<>();
                    Session session = connection.session();
                    info.put("sessionId", connection.sessionKey());
                    info.put("beginString", connection.beginString());
                    info.put("senderCompId", connection.senderCompId());
                    info.put("targetCompId", connection.targetCompId());
                    info.put("loggedOn", connection.loggedOn() && session != null && session.isConnected());
                    info.put("msgCount", session != null ? session.lastReceivedMsgSeqNum() : 0);
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
            boolean found = false;
            for (FixConnection connection : registry.getAllConnections()) {
                if (connection.sessionKey().equals(sessionIdStr)) {
                    if (connection.writer() != null) {
                        connection.writer().requestDisconnect(DisconnectReason.ADMIN_API_DISCONNECT);
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

