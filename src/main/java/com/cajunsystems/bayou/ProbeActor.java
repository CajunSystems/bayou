package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;

import java.util.concurrent.LinkedBlockingQueue;

/** Internal actor backing a {@link TestProbe}. */
final class ProbeActor<M> implements Actor<M> {

    private final LinkedBlockingQueue<M> messages;
    private final LinkedBlockingQueue<Signal> signals;

    ProbeActor(LinkedBlockingQueue<M> messages, LinkedBlockingQueue<Signal> signals) {
        this.messages = messages;
        this.signals = signals;
    }

    @Override
    public void handle(M message, BayouContext<M> ctx) {
        messages.offer(message);
    }

    @Override
    public void onSignal(Signal signal, BayouContext<M> ctx) {
        signals.offer(signal);
    }
}
