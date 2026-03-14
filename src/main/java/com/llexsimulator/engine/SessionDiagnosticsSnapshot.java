package com.llexsimulator.engine;

import java.util.List;

/**
 * Immutable diagnostics snapshot for active or recently disconnected FIX sessions.
 */
public record SessionDiagnosticsSnapshot(
        long connectionId,
        long artioSessionId,
        String sessionKey,
        String beginString,
        String senderCompId,
        String targetCompId,
        boolean loggedOn,
        long msgCount,
        int sequenceIndex,
        int lastReceivedMsgSeqNum,
        int lastSentMsgSeqNum,
        long inboundMessageCount,
        long outboundSendSuccessCount,
        long outboundBackpressureCount,
        long outboundSendFailureCount,
        long timeoutCount,
        long disconnectCount,
        long slowStatusChangeCount,
        boolean slowConsumer,
        String lastInboundMsgType,
        String lastOutboundEvent,
        String lastDisconnectReason,
        long lastLibraryPosition,
        long connectedAtEpochMs,
        long lastLogonAtEpochMs,
        long lastInboundAtEpochMs,
        long lastOutboundAtEpochMs,
        long lastTimeoutAtEpochMs,
        long lastDisconnectAtEpochMs,
        List<String> recentEvents
) {
}

