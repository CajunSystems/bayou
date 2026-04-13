package com.cajunsystems.bayou;

/**
 * The action a supervisor takes when one of its children crashes.
 *
 * <p>Returned by {@link SupervisionStrategy#decide(String, Throwable)}.
 */
public enum RestartDecision {

    /**
     * Restart only the crashed child. Siblings are unaffected.
     * Used by {@link OneForOneStrategy}.
     */
    RESTART,

    /**
     * Stop and restart all of the supervisor's children.
     * Used by {@link AllForOneStrategy}.
     */
    RESTART_ALL,

    /**
     * Stop the crashed child permanently. The supervisor continues running
     * and its other children are unaffected.
     */
    STOP,

    /**
     * Escalate the failure up the supervision tree: treat this supervisor
     * as if it crashed, signalling its own parent. If this supervisor has
     * no parent, it stops itself and all its children.
     */
    ESCALATE
}
