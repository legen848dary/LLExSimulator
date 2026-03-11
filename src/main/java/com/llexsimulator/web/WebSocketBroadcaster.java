package com.llexsimulator.web;

import io.vertx.core.http.ServerWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Maintains the set of connected WebSocket clients and broadcasts JSON messages
 * to all of them.
 *
 * <p>All {@link #broadcast(String)} calls MUST originate from the Vert.x event-loop
 * thread (use {@code vertx.runOnContext()} when calling from other threads).
 */
public final class WebSocketBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(WebSocketBroadcaster.class);

    private final CopyOnWriteArraySet<ServerWebSocket> clients = new CopyOnWriteArraySet<>();

    public void handleUpgrade(ServerWebSocket ws) {
        clients.add(ws);
        log.info("WebSocket client connected: {}", ws.remoteAddress());

        ws.closeHandler(v -> {
            clients.remove(ws);
            log.debug("WebSocket client disconnected: {}", ws.remoteAddress());
        });
        ws.exceptionHandler(ex -> {
            clients.remove(ws);
            log.debug("WebSocket client error: {}", ex.getMessage());
        });
    }

    /** Sends {@code json} to all connected clients. Must be called on the event loop. */
    public void broadcast(String json) {
        for (ServerWebSocket ws : clients) {
            ws.writeTextMessage(json);
        }
    }

    public int clientCount() { return clients.size(); }
}

