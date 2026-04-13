package com.cajunsystems.bayou;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

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
    private final Map<String, ArrayDeque<Instant>> restartHistory = new HashMap<>();

    public OneForOneStrategy(RestartWindow restartWindow) {
        if (restartWindow == null) throw new IllegalArgumentException("restartWindow must not be null");
        this.restartWindow = restartWindow;
    }

    /** Returns the restart window used by this strategy. */
    public RestartWindow restartWindow() {
        return restartWindow;
    }

    @Override
    public RestartDecision decide(String childId, Throwable cause) {
        if (restartWindow == RestartWindow.UNLIMITED) return RestartDecision.RESTART;
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
        return RestartDecision.RESTART;
    }
}
