package com.cajunsystems.bayou;

/**
 * Supervision strategy that restarts only the crashed child.
 *
 * <p>Sibling actors are unaffected. The {@link RestartWindow} configures
 * the death-spiral guard: if the child crashes too many times within the
 * window, the supervisor escalates instead of restarting.
 *
 * <pre>{@code
 * // Restart up to 5 times within 60 seconds, then escalate:
 * new OneForOneStrategy(new RestartWindow(5, Duration.ofSeconds(60)))
 *
 * // Always restart (no limit):
 * new OneForOneStrategy(RestartWindow.UNLIMITED)
 * }</pre>
 */
public final class OneForOneStrategy implements SupervisionStrategy {

    private final RestartWindow restartWindow;

    public OneForOneStrategy(RestartWindow restartWindow) {
        if (restartWindow == null) throw new IllegalArgumentException("restartWindow must not be null");
        this.restartWindow = restartWindow;
    }

    /** Returns the restart window used by this strategy. */
    public RestartWindow restartWindow() {
        return restartWindow;
    }

    /**
     * Always returns {@link RestartDecision#RESTART} for now.
     * Phase 5 (death spiral guard) will add restart-count tracking and
     * return {@link RestartDecision#ESCALATE} when the window is exceeded.
     */
    @Override
    public RestartDecision decide(String childId, Throwable cause) {
        return RestartDecision.RESTART;
    }
}
