package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DeathSpiralTest {

    private BayouSystem system;

    @BeforeEach void setUp() throws Exception { system = BayouTestSupport.freshSystem(); }
    @AfterEach  void tearDown()               { system.shutdown(); }

    @Test
    void escalatesAfterExceedingRestartWindow() {
        // Window: 3 restarts in 10 seconds
        // Always-crashing child → 4th crash triggers ESCALATE → supervisor dies
        var preStarts = new AtomicInteger(0);
        Actor<String> alwaysCrashes = new Actor<>() {
            public void handle(String msg, BayouContext ctx) {}
            public void preStart(BayouContext ctx) {
                preStarts.incrementAndGet();
                throw new RuntimeException("always crashes");
            }
        };

        SupervisorRef sup = system.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(ChildSpec.stateless("crasher", alwaysCrashes));
            }
            public SupervisionStrategy strategy() {
                return new OneForOneStrategy(new RestartWindow(3, Duration.ofSeconds(10)));
            }
        });

        // Wait for supervisor to fully stop: isAlive=false happens before cleanup(),
        // so also wait for cleanup() to deregister the child.
        await().atMost(10, TimeUnit.SECONDS).until(() ->
            !sup.isAlive() && system.lookup("crasher").isEmpty());

        // Exactly 4 preStart calls: initial + 3 restarts; 4th crash triggers ESCALATE
        assertThat(preStarts.get()).isEqualTo(4);
        assertThat(system.lookup("crasher")).isEmpty();
    }

    @Test
    void unlimitedWindowNeverEscalates() throws Exception {
        // Crashes 3 times then succeeds — UNLIMITED window should never escalate
        var preStarts = new AtomicInteger(0);
        Actor<String> crashThreeTimes = new Actor<>() {
            public void handle(String msg, BayouContext ctx) {}
            public void preStart(BayouContext ctx) {
                if (preStarts.incrementAndGet() <= 3) {
                    throw new RuntimeException("crash #" + preStarts.get());
                }
            }
        };

        SupervisorRef sup = system.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(ChildSpec.stateless("worker", crashThreeTimes));
            }
            public SupervisionStrategy strategy() {
                return new OneForOneStrategy(RestartWindow.UNLIMITED);
            }
        });

        // Actor recovers on 4th preStart — both actor and supervisor stay alive
        await().atMost(5, TimeUnit.SECONDS).until(() ->
            system.lookup("worker").map(Ref::isAlive).orElse(false));

        assertThat(sup.isAlive()).isTrue();
        assertThat(preStarts.get()).isEqualTo(4);
    }

    @Test
    void topLevelEscalationStopsGracefully() {
        // Top-level supervisor with window=1; 2nd crash causes ESCALATE.
        // No parent → supervisor stops permanently; system remains usable.
        var preStarts = new AtomicInteger(0);
        Actor<String> alwaysCrashes = new Actor<>() {
            public void handle(String msg, BayouContext ctx) {}
            public void preStart(BayouContext ctx) {
                preStarts.incrementAndGet();
                throw new RuntimeException("always crashes");
            }
        };

        SupervisorRef sup = system.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(ChildSpec.stateless("crasher", alwaysCrashes));
            }
            public SupervisionStrategy strategy() {
                return new OneForOneStrategy(new RestartWindow(1, Duration.ofSeconds(10)));
            }
        });

        // Wait for supervisor to fully stop including cleanup() deregistering the child
        await().atMost(5, TimeUnit.SECONDS).until(() ->
            !sup.isAlive() && system.lookup("crasher").isEmpty());

        assertThat(sup.isAlive()).isFalse();
        assertThat(system.lookup("crasher")).isEmpty();

        // System remains operational — can still spawn new actors
        Ref<String> probe = system.spawn("probe", (msg, ctx) -> {});
        assertThat(probe.isAlive()).isTrue();
    }

    @Test
    void allForOneEscalatesAfterExceedingRestartWindow() {
        // Window: 2 restarts in 10 seconds for AllForOneStrategy
        // Actor A always crashes on preStart. Actor B is a silent sibling.
        // A crashes 3 times (initial + 2 restarts) → 3rd crash triggers ESCALATE.
        // Supervisor dies.
        var aPreStarts = new AtomicInteger(0);

        Actor<String> actorA = new Actor<>() {
            public void handle(String msg, BayouContext ctx) {}
            public void preStart(BayouContext ctx) {
                aPreStarts.incrementAndGet();
                throw new RuntimeException("A always crashes");
            }
        };

        Actor<String> actorB = new Actor<>() {
            public void handle(String msg, BayouContext ctx) {}
        };

        SupervisorRef sup = system.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(
                    ChildSpec.stateless("a", actorA),
                    ChildSpec.stateless("b", actorB)
                );
            }
            public SupervisionStrategy strategy() {
                return new AllForOneStrategy(new RestartWindow(2, Duration.ofSeconds(10)));
            }
        });

        // Wait for supervisor to fully stop including cleanup() deregistering both children
        await().atMost(10, TimeUnit.SECONDS).until(() ->
            !sup.isAlive() && system.lookup("a").isEmpty() && system.lookup("b").isEmpty());

        // A crashed 3 times: initial + 2 restarts; 3rd crash triggers ESCALATE
        assertThat(aPreStarts.get()).isEqualTo(3);
        assertThat(system.lookup("a")).isEmpty();
        assertThat(system.lookup("b")).isEmpty();
    }
}
