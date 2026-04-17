package com.cajunsystems.bayou;

import java.time.Duration;

/**
 * Configures the death-spiral guard for a supervision strategy.
 *
 * <p>If a child crashes more than {@code maxRestarts} times within {@code within},
 * the supervisor escalates rather than restarting again.
 *
 * <p>Use {@link #UNLIMITED} to disable the guard entirely.
 *
 * @param maxRestarts maximum number of restarts before escalation
 * @param within      the rolling time window; use {@link Duration#ZERO} to disable time-windowing
 */
public record RestartWindow(int maxRestarts, Duration within) {

    /** No restart limit — the supervisor will always attempt to restart. */
    public static final RestartWindow UNLIMITED = new RestartWindow(Integer.MAX_VALUE, Duration.ZERO);

    public RestartWindow {
        if (maxRestarts < 0) throw new IllegalArgumentException(
            "maxRestarts must be >= 0, got: " + maxRestarts);
        if (within == null)  throw new IllegalArgumentException("within must not be null");
    }
}
