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

        // Supervisor should die after 3 restarts (4 preStart calls total)
        await().atMost(10, TimeUnit.SECONDS).until(() -> !sup.isAlive());

        // Exactly 4 preStart calls: initial + 3 restarts; 4th crash triggers ESCALATE
        assertThat(preStarts.get()).isEqualTo(4);
        // cleanup() unregisters children — crasher is no longer in the system
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
            system.lookup("worker").map(ActorRef::isAlive).orElse(false));

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

        // Supervisor and child both stop; system does not throw
        await().atMost(5, TimeUnit.SECONDS).until(() -> !sup.isAlive());

        assertThat(sup.isAlive()).isFalse();
        // cleanup() unregisters children — crasher is no longer in the system
        assertThat(system.lookup("crasher")).isEmpty();

        // System remains operational — can still spawn new actors
        ActorRef<String> probe = system.spawn("probe", (msg, ctx) -> {});
        assertThat(probe.isAlive()).isTrue();
    }
}
