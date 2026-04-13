package com.cajunsystems.bayou;

/**
 * Supervision strategy that restarts all children when any one crashes.
 *
 * <p>All children (including siblings of the crashed actor) are stopped
 * and restarted in their original declaration order. Use this when children
 * are tightly coupled and a single crash invalidates the whole group.
 *
 * <pre>{@code
 * new AllForOneStrategy(new RestartWindow(3, Duration.ofSeconds(30)))
 * }</pre>
 */
public final class AllForOneStrategy implements SupervisionStrategy {

    private final RestartWindow restartWindow;

    public AllForOneStrategy(RestartWindow restartWindow) {
        if (restartWindow == null) throw new IllegalArgumentException("restartWindow must not be null");
        this.restartWindow = restartWindow;
    }

    /** Returns the restart window used by this strategy. */
    public RestartWindow restartWindow() {
        return restartWindow;
    }

    /**
     * Always returns {@link RestartDecision#RESTART_ALL} for now.
     * Phase 5 (death spiral guard) will add restart-count tracking and
     * return {@link RestartDecision#ESCALATE} when the window is exceeded.
     */
    @Override
    public RestartDecision decide(String childId, Throwable cause) {
        return RestartDecision.RESTART_ALL;
    }
}
