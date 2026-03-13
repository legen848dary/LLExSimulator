package com.llexsimulator.engine;

import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionWriter;

/**
 * Artio-backed FIX session metadata used by the simulator hot path and web/API layer.
 */
public final class FixConnection {

    private final long connectionId;
    private final long sessionId;
    private final String sessionKey;
    private final String beginString;
    private final String senderCompId;
    private final String targetCompId;

    private volatile Session session;
    private volatile SessionWriter writer;
    private volatile int sequenceIndex;
    private volatile boolean loggedOn;

    FixConnection(long connectionId, Session session, SessionWriter writer) {
        CompositeKey key = session.compositeKey();
        this.connectionId = connectionId;
        this.sessionId = session.id();
        this.beginString = session.beginString();
        this.senderCompId = key != null ? key.localCompId() : "UNKNOWN";
        this.targetCompId = key != null ? key.remoteCompId() : "UNKNOWN";
        this.sessionKey = beginString + ':' + senderCompId + "->" + targetCompId + '#' + sessionId;
        this.session = session;
        this.writer = writer;
        this.sequenceIndex = session.sequenceIndex();
        this.loggedOn = true;
    }

    public long connectionId() {
        return connectionId;
    }

    public long sessionId() {
        return sessionId;
    }

    public String sessionKey() {
        return sessionKey;
    }

    public String beginString() {
        return beginString;
    }

    public String senderCompId() {
        return senderCompId;
    }

    public String targetCompId() {
        return targetCompId;
    }

    public Session session() {
        return session;
    }

    public SessionWriter writer() {
        return writer;
    }

    public int sequenceIndex() {
        return sequenceIndex;
    }

    public boolean loggedOn() {
        return loggedOn;
    }

    public void updateSession(Session session, SessionWriter writer) {
        this.session = session;
        this.writer = writer;
        this.sequenceIndex = session.sequenceIndex();
        this.loggedOn = true;
    }

    public void sequenceIndex(int sequenceIndex) {
        this.sequenceIndex = sequenceIndex;
        SessionWriter currentWriter = writer;
        if (currentWriter != null) {
            currentWriter.sequenceIndex(sequenceIndex);
        }
    }

    public void markDisconnected() {
        this.loggedOn = false;
    }
}

