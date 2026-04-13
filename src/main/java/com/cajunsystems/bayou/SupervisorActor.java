package com.cajunsystems.bayou;

import java.util.List;

/**
 * Defines a supervised group of actors.
 *
 * <p>Implement this interface and pass it to {@link BayouSystem#spawnSupervisor} to
 * create a supervisor that starts the declared children and applies the chosen strategy
 * when any child crashes.
 *
 * <pre>{@code
 * BayouSystem system = ...;
 * SupervisorRef ref = system.spawnSupervisor("my-supervisor", new SupervisorActor() {
 *
 *     public List<ChildSpec> children() {
 *         return List.of(
 *             ChildSpec.stateless("worker-1", (msg, ctx) -> ctx.logger().info("got: {}", msg)),
 *             ChildSpec.stateless("worker-2", (msg, ctx) -> ctx.logger().info("got: {}", msg))
 *         );
 *     }
 *
 *     public SupervisionStrategy strategy() {
 *         return new OneForOneStrategy(new RestartWindow(5, Duration.ofSeconds(60)));
 *     }
 * });
 * }</pre>
 */
public interface SupervisorActor {

    /**
     * Declares the children started when the supervisor starts.
     * The returned list defines declaration order, which is used for all-for-one restarts.
     *
     * <p>Defaults to an empty list — override when initial children are known at spawn time.
     * Children can always be added later via {@link SupervisorRef#spawnChild(ChildSpec)}.
     */
    default List<ChildSpec> children() { return List.of(); }

    /**
     * The supervision strategy applied when a child crashes.
     */
    SupervisionStrategy strategy();
}
