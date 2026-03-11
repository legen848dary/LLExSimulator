package com.llexsimulator.engine;

import org.agrona.collections.Long2ObjectHashMap;
import quickfix.SessionID;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maps numeric session connection IDs (used on the zero-GC hot path) to
 * QuickFIX/J {@link SessionID} objects (used only for sending FIX messages).
 *
 * <p>Write operations (logon/logout) happen rarely — synchronization overhead
 * is acceptable. Reads on the hot path use the lock-free Agrona map.
 */
public final class OrderSessionRegistry {

    private final Long2ObjectHashMap<SessionID> idToSession = new Long2ObjectHashMap<>();
    private final AtomicLong                    counter     = new AtomicLong(0L);

    /** Assigns a new numeric ID and registers the session. Returns the assigned ID. */
    public synchronized long register(SessionID sessionId) {
        long id = counter.incrementAndGet();
        idToSession.put(id, sessionId);
        return id;
    }

    public synchronized void remove(long connectionId) {
        idToSession.remove(connectionId);
    }

    /** Hot-path read — returns {@code null} if not found. */
    public SessionID getSessionId(long connectionId) {
        return idToSession.get(connectionId);
    }

    public synchronized int activeCount() { return idToSession.size(); }

    public synchronized List<SessionID> getAllSessionIds() {
        return new ArrayList<>(idToSession.values());
    }
}

