package com.cajunsystems.bayou;

/**
 * Decides what a supervisor does when one of its children crashes.
 *
 * <p>Built-in implementations: {@link OneForOneStrategy}, {@link AllForOneStrategy}.
 * Custom strategies can be supplied as lambdas or anonymous classes.
 *
 * <pre>{@code
 * SupervisionStrategy strategy = new OneForOneStrategy(
 *         new RestartWindow(5, Duration.ofSeconds(60)));
 *
 * // Or a custom strategy via lambda:
 * SupervisionStrategy custom = (childId, cause) ->
 *         cause instanceof IllegalStateException
 *                 ? RestartDecision.STOP
 *                 : RestartDecision.RESTART;
 * }</pre>
 */
@FunctionalInterface
public interface SupervisionStrategy {

    /**
     * Determine what action to take for a crashed child.
     *
     * @param childId the actor ID of the crashed child
     * @param cause   the exception that caused the crash
     * @return the action the supervisor should take
     */
    RestartDecision decide(String childId, Throwable cause);
}
