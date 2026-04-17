package com.cajunsystems.bayou;

/**
 * Handle for a pending or repeating timer. Obtained from
 * {@link BayouContext#scheduleOnce} or {@link BayouContext#schedulePeriodic}.
 */
public interface TimerRef {
    /** Cancel this timer. For periodic timers, stops all future firings. Idempotent. */
    void cancel();
    /** Returns {@code true} if this timer has been cancelled or has already fired (one-shot). */
    boolean isCancelled();
}
