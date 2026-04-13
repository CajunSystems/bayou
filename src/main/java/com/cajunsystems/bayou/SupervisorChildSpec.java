package com.cajunsystems.bayou;

/**
 * Specification for a nested supervisor child. Created via {@link ChildSpec#supervisor(String, SupervisorActor)}.
 */
record SupervisorChildSpec(String actorId, SupervisorActor supervisorActor) implements ChildSpec {}
