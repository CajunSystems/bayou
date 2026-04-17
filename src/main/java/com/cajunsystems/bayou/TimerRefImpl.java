package com.cajunsystems.bayou;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

final class TimerRefImpl implements TimerRef {

    private final ScheduledFuture<?> future;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    TimerRefImpl(ScheduledFuture<?> future) {
        this.future = future;
    }

    @Override
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            future.cancel(false);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get() || future.isCancelled() || future.isDone();
    }
}
