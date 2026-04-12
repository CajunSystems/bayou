package com.cajunsystems.bayou;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Package-private implementation of {@link BayouContext}.
 * One instance is created per actor runner and reused across all message deliveries;
 * {@link #setCurrentEnvelope} is called before each dispatch.
 */
class BayouContextImpl implements BayouContext {

    private final String actorId;
    private final BayouSystem system;
    private final Logger logger;
    private Envelope<?> currentEnvelope;

    BayouContextImpl(String actorId, BayouSystem system) {
        this.actorId = actorId;
        this.system = system;
        this.logger = LoggerFactory.getLogger("bayou.actor." + actorId);
    }

    void setCurrentEnvelope(Envelope<?> envelope) {
        this.currentEnvelope = envelope;
    }

    @Override
    public String actorId() {
        return actorId;
    }

    @Override
    public BayouSystem system() {
        return system;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void reply(Object value) {
        if (currentEnvelope != null && currentEnvelope.isAsk()) {
            ((CompletableFuture<Object>) currentEnvelope.replyFuture()).complete(value);
        }
    }
}
