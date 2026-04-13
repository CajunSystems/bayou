package com.cajunsystems.bayou;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

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
    private final Map<String, ArrayDeque<Instant>> restartHistory = new HashMap<>();

    public AllForOneStrategy(RestartWindow restartWindow) {
        if (restartWindow == null) throw new IllegalArgumentException("restartWindow must not be null");
        this.restartWindow = restartWindow;
    }

    /** Returns the restart window used by this strategy. */
    public RestartWindow restartWindow() {
        return restartWindow;
    }

    @Override
    public RestartDecision decide(String childId, Throwable cause) {
        if (restartWindow == RestartWindow.UNLIMITED) return RestartDecision.RESTART_ALL;
        ArrayDeque<Instant> history =
            restartHistory.computeIfAbsent(childId, k -> new ArrayDeque<>());
        if (!restartWindow.within().isZero()) {
            Instant cutoff = Instant.now().minus(restartWindow.within());
            history.removeIf(t -> t.isBefore(cutoff));
        }
        if (history.size() >= restartWindow.maxRestarts()) {
            return RestartDecision.ESCALATE;
        }
        history.addLast(Instant.now());
        return RestartDecision.RESTART_ALL;
    }
}
