package com.cajunsystems.bayou;

import java.util.concurrent.CompletableFuture;

/**
 * Internal mailbox entry. Wraps either a message, a signal, or an ask-pattern message.
 * Package-private — not part of the public API.
 */
record Envelope<M>(M payload, Signal signal, CompletableFuture<Object> replyFuture) {

    static <M> Envelope<M> tell(M payload) {
        return new Envelope<>(payload, null, null);
    }

    static <M> Envelope<M> ask(M payload) {
        return new Envelope<>(payload, null, new CompletableFuture<>());
    }

    static <M> Envelope<M> signal(Signal signal) {
        return new Envelope<>(null, signal, null);
    }

    boolean isAsk() {
        return replyFuture != null;
    }

    boolean isSignal() {
        return signal != null;
    }
}
