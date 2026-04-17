package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.bayou.actor.EventSourcedActor;
import com.cajunsystems.bayou.actor.StatefulActor;

/**
 * Specification for a supervised child actor. Created via static factory methods.
 *
 * <pre>{@code
 * ChildSpec.stateless("worker", (msg, ctx) -> ...)
 * ChildSpec.stateful("counter", new MyStatefulActor(), new JavaSerializer<>())
 * ChildSpec.eventSourced("ledger", new MyEventSourcedActor(), new JavaSerializer<>())
 * }</pre>
 */
public sealed interface ChildSpec permits StatelessChildSpec, StatefulChildSpec, EventSourcedChildSpec, SupervisorChildSpec {

    /** The unique actor ID for this child within the system. */
    String actorId();

    /** The mailbox configuration for this child actor. */
    MailboxConfig mailboxConfig();

    /** Creates a spec for a stateless actor. */
    static <M> ChildSpec stateless(String actorId, Actor<M> actor) {
        return new StatelessChildSpec<>(actorId, actor, MailboxConfig.unbounded());
    }

    /**
     * Creates a spec for a stateful actor.
     * The default snapshot interval ({@value BayouSystem#DEFAULT_SNAPSHOT_INTERVAL}) is used
     * unless overridden via {@link StatefulChildSpec#snapshotInterval(int)}.
     *
     * <pre>{@code
     * ChildSpec.stateful("counter", new MyCounter(), new JavaSerializer<>())
     *          .snapshotInterval(10)
     * }</pre>
     */
    static <S, M> StatefulChildSpec<S, M> stateful(String actorId,
                                                     StatefulActor<S, M> actor,
                                                     BayouSerializer<S> stateSerializer) {
        return new StatefulChildSpec<>(actorId, actor, stateSerializer,
                BayouSystem.DEFAULT_SNAPSHOT_INTERVAL, MailboxConfig.unbounded());
    }

    /** Creates a spec for an event-sourced actor. */
    static <S, E, M> ChildSpec eventSourced(String actorId,
                                              EventSourcedActor<S, E, M> actor,
                                              BayouSerializer<E> eventSerializer) {
        return new EventSourcedChildSpec<>(actorId, actor, eventSerializer, MailboxConfig.unbounded());
    }

    /**
     * Creates a spec for a nested supervisor.
     *
     * <p>The nested supervisor owns its own children and applies its own strategy.
     * Escalation from the nested supervisor propagates to this supervisor as a crash signal.
     *
     * <pre>{@code
     * ChildSpec.supervisor("inner-sup", new SupervisorActor() {
     *     public List<ChildSpec> children() { return List.of(ChildSpec.stateless("worker", actor)); }
     *     public SupervisionStrategy strategy() { return new OneForOneStrategy(RestartWindow.UNLIMITED); }
     * })
     * }</pre>
     */
    static ChildSpec supervisor(String actorId, SupervisorActor supervisorActor) {
        return new SupervisorChildSpec(actorId, supervisorActor, MailboxConfig.unbounded());
    }
}
