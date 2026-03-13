package com.llexsimulator.client;

import quickfix.Log;
import quickfix.LogFactory;
import quickfix.SessionID;

/**
 * Suppresses QuickFIX/J raw session/message logging when the demo client is run
 * in high-rate mode and file logs are not desired.
 */
public final class NoOpQuickFixLogFactory implements LogFactory {

    private static final Log NO_OP_LOG = new Log() {
        @Override
        public void clear() {
        }

        @Override
        public void onIncoming(String message) {
        }

        @Override
        public void onOutgoing(String message) {
        }

        @Override
        public void onEvent(String text) {
        }

        @Override
        public void onErrorEvent(String text) {
        }
    };


    @Override
    public Log create(SessionID sessionID) {
        return NO_OP_LOG;
    }
}

