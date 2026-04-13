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
     * Declares the children this supervisor owns. Called once when the supervisor starts.
     * The returned list defines the children's declaration order, which is used for
     * all-for-one restarts.
     */
    List<ChildSpec> children();

    /**
     * The supervision strategy applied when a child crashes.
     */
    SupervisionStrategy strategy();
}
