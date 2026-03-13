package com.llexsimulator.engine;

import org.agrona.collections.Long2ObjectHashMap;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maps numeric session connection IDs (used on the hot path) to Artio-backed
 * {@link FixConnection} objects used for outbound execution reports and the UI.
 *
 * <p>Write operations (logon/logout) happen rarely — synchronization overhead
 * is acceptable. Reads on the hot path use the lock-free Agrona map.
 */
public final class OrderSessionRegistry {

    private final Long2ObjectHashMap<FixConnection> idToConnection = new Long2ObjectHashMap<>();
    private final AtomicLong                        counter        = new AtomicLong(0L);

    /** Assigns a new numeric ID and registers the Artio session. */
    public synchronized FixConnection register(Session session, SessionWriter writer) {
        long id = counter.incrementAndGet();
        FixConnection connection = new FixConnection(id, session, writer);
        idToConnection.put(id, connection);
        return connection;
    }

    public synchronized FixConnection remove(long connectionId) {
        return idToConnection.remove(connectionId);
    }

    /** Hot-path read — returns {@code null} if not found. */
    public FixConnection get(long connectionId) {
        return idToConnection.get(connectionId);
    }

    public synchronized int activeCount() {
        return idToConnection.size();
    }

    public synchronized List<FixConnection> getAllConnections() {
        return new ArrayList<>(idToConnection.values());
    }
}

