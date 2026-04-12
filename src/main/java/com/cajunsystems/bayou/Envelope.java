package com.cajunsystems.bayou;

import java.util.concurrent.CompletableFuture;

/**
 * Internal mailbox entry. Wraps a message with an optional promise for ask-pattern replies.
 * Package-private — not part of the public API.
 */
record Envelope<M>(M payload, CompletableFuture<Object> replyFuture) {

    static <M> Envelope<M> tell(M payload) {
        return new Envelope<>(payload, null);
    }

    static <M> Envelope<M> ask(M payload) {
        return new Envelope<>(payload, new CompletableFuture<>());
    }

    boolean isAsk() {
        return replyFuture != null;
    }
}
