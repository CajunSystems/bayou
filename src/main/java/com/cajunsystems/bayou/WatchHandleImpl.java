package com.cajunsystems.bayou;

import java.util.function.Consumer;

final class WatchHandleImpl implements WatchHandle {
    private final AbstractActorRunner<?> target;
    private final Consumer<Signal> listener;

    WatchHandleImpl(AbstractActorRunner<?> target, Consumer<Signal> listener) {
        this.target = target;
        this.listener = listener;
    }

    @Override
    public void cancel() {
        target.signalListeners.remove(listener);
    }
}
