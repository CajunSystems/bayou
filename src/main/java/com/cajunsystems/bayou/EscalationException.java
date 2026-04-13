package com.cajunsystems.bayou;

/** Thrown by a supervisor runner to propagate escalation up the supervision tree. */
final class EscalationException extends RuntimeException {
    EscalationException(String supervisorId, Throwable cause) {
        super("Supervisor '" + supervisorId + "' escalating: restart window exhausted", cause);
    }
}
