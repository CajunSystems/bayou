package com.cajunsystems.bayou;

/**
 * Specification for a nested supervisor child. Created via {@link ChildSpec#supervisor(String, SupervisorActor)}.
 */
record SupervisorChildSpec(String actorId, SupervisorActor supervisorActor, MailboxConfig mailboxConfig) implements ChildSpec {

    public SupervisorChildSpec withMailbox(MailboxConfig config) {
        return new SupervisorChildSpec(actorId, supervisorActor, config);
    }
}
