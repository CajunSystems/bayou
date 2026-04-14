package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.bayou.actor.EventSourcedActor;
import com.cajunsystems.bayou.actor.StatefulActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RestartMechanicsTest {

    private BayouSystem system;

    @BeforeEach void setUp() throws Exception { system = BayouTestSupport.freshSystem(); }
    @AfterEach  void tearDown()               { system.shutdown(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** State: running sum. reduce(n) adds n and replies with the new total. */
    record Sum(int value) implements Serializable {}

    // Minimal event-sourced counter for supervised restart test.
    // State: running sum. handle(n) adds n and replies with new total.
    record SumState(int value) implements Serializable {}

    sealed interface SumEvent extends Serializable {
        record Added(int n) implements SumEvent {}
    }

    static class SumEventActor implements EventSourcedActor<SumState, SumEvent, Integer> {
        @Override public SumState initialState() { return new SumState(0); }
        @Override public List<SumEvent> handle(SumState state, Integer n, BayouContext ctx) {
            ctx.reply(state.value() + n);
            return List.of(new SumEvent.Added(n));
        }
        @Override public SumState apply(SumState state, SumEvent event) {
            return new SumState(state.value() + ((SumEvent.Added) event).n());
        }
    }

    static class SumActor implements StatefulActor<Sum, Integer> {
        @Override public Sum initialState() { return new Sum(0); }
        @Override public Sum reduce(Sum state, Integer n, BayouContext ctx) {
            Sum next = new Sum(state.value() + n);
            ctx.reply(next.value());
            return next;
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void statelessOneForOneRestartResumesMessageProcessing() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var crashed = new AtomicBoolean(false);

        Actor<String> actor = new Actor<>() {
            public void handle(String msg, BayouContext ctx) { received.add(msg); }
            public void preStart(BayouContext ctx) {
                if (crashed.compareAndSet(false, true)) {
                    throw new RuntimeException("first-start crash");
                }
            }
        };

        system.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(ChildSpec.stateless("worker", actor));
            }
            public SupervisionStrategy strategy() {
                return new OneForOneStrategy(RestartWindow.UNLIMITED);
            }
        });

        // Wait for restart to complete (actor alive after second preStart)
        await().atMost(5, TimeUnit.SECONDS).until(() ->
                system.lookup("worker").map(Ref::isAlive).orElse(false));

        Ref<String> worker = system.<String>lookup("worker").orElseThrow();
        worker.tell("hello");
        worker.tell("world");

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(received).containsExactly("hello", "world"));
    }

    @Test
    void statefulOneForOneRestartRestoresSnapshot() throws Exception {
        var sharedLog = system.sharedLog();

        // Phase 1: standalone actor accumulates state (snapshot every message)
        Ref<Integer> seeder = system.spawnStateful("ctr", new SumActor(), new JavaSerializer<>(), 1);
        seeder.tell(7);
        seeder.tell(8);
        // Wait for both messages processed and snapshotted (ask with 0 returns current state)
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(seeder.<Integer>ask(0).get(5, TimeUnit.SECONDS)).isEqualTo(15));
        seeder.stop().get(5, TimeUnit.SECONDS);
        // Log now has snapshot with state = 15

        // Phase 2: supervised on same log — crash on first preStart, restart reads snapshot
        var preStartCalls = new AtomicInteger(0);
        BayouSystem system2 = new BayouSystem(sharedLog);

        system2.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(ChildSpec.stateful("ctr", new SumActor() {
                    @Override
                    public void preStart(BayouContext ctx) {
                        if (preStartCalls.incrementAndGet() == 1) {
                            throw new RuntimeException("crash before first message");
                        }
                    }
                }, new JavaSerializer<>()).snapshotInterval(1));
            }
            public SupervisionStrategy strategy() {
                return new OneForOneStrategy(RestartWindow.UNLIMITED);
            }
        });

        // Wait for restart (preStartCalls reaches 2)
        await().atMost(5, TimeUnit.SECONDS).until(() -> preStartCalls.get() >= 2);

        // State should be restored from snapshot = 15; ask with 0 → reply = 15
        Ref<Integer> ctr = system2.<Integer>lookup("ctr").orElseThrow();
        int restored = ctr.<Integer>ask(0).get(5, TimeUnit.SECONDS);
        assertThat(restored).isEqualTo(15);

        system2.shutdown();
    }

    @Test
    void allForOneRestartsBothCrashedAndSiblings() throws Exception {
        var aPreStarts = new AtomicInteger(0);
        var bPreStarts = new AtomicInteger(0);

        Actor<String> actorA = new Actor<>() {
            public void handle(String msg, BayouContext ctx) {}
            public void preStart(BayouContext ctx) {
                // Crash only on the very first start; succeed on restart
                if (aPreStarts.incrementAndGet() == 1) {
                    throw new RuntimeException("A crashes on first start");
                }
            }
        };

        Actor<String> actorB = new Actor<>() {
            public void handle(String msg, BayouContext ctx) {}
            public void preStart(BayouContext ctx) { bPreStarts.incrementAndGet(); }
        };

        system.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(
                    ChildSpec.stateless("a", actorA),
                    ChildSpec.stateless("b", actorB)
                );
            }
            public SupervisionStrategy strategy() {
                return new AllForOneStrategy(RestartWindow.UNLIMITED);
            }
        });

        // A crashes → all-for-one → A restarts (aPreStarts=2) and B is stopped+restarted (bPreStarts=2)
        await().atMost(5, TimeUnit.SECONDS).until(() ->
                aPreStarts.get() >= 2 && bPreStarts.get() >= 2);

        assertThat(system.lookup("a").map(Ref::isAlive)).contains(true);
        assertThat(system.lookup("b").map(Ref::isAlive)).contains(true);
    }

    @Test
    void stopDecisionLeavesCrashedActorPermanentlyDead() throws Exception {
        Actor<String> alwaysCrashes = new Actor<>() {
            public void handle(String msg, BayouContext ctx) {}
            public void preStart(BayouContext ctx) {
                throw new RuntimeException("always crashes");
            }
        };

        system.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(ChildSpec.stateless("crasher", alwaysCrashes));
            }
            public SupervisionStrategy strategy() {
                return (childId, cause) -> RestartDecision.STOP;
            }
        });

        // Wait for crash to be processed — actor stops and stays stopped
        await().atMost(5, TimeUnit.SECONDS).until(() ->
                system.lookup("crasher").map(Ref::isAlive).map(alive -> !alive).orElse(false));

        // Confirm no restart occurs
        Thread.sleep(300);
        assertThat(system.lookup("crasher").map(Ref::isAlive)).contains(false);
    }

    @Test
    void spawnChildAddsChildUnderSupervision() throws Exception {
        var received = new CopyOnWriteArrayList<String>();

        // Start with no initial children
        SupervisorRef sup = system.spawnSupervisor("sup", new SupervisorActor() {
            public SupervisionStrategy strategy() {
                return new OneForOneStrategy(RestartWindow.UNLIMITED);
            }
        });

        await().atMost(2, TimeUnit.SECONDS).until(sup::isAlive);

        // Dynamically spawn a child
        sup.spawnChild(ChildSpec.stateless("dynamic",
                (String msg, BayouContext ctx) -> received.add(msg)));

        await().atMost(2, TimeUnit.SECONDS).until(() ->
                system.lookup("dynamic").map(Ref::isAlive).orElse(false));

        Ref<String> child = system.<String>lookup("dynamic").orElseThrow();
        child.tell("ping");
        child.tell("pong");

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(received).containsExactly("ping", "pong"));
    }

    @Test
    void eventSourcedOneForOneRestartReplaysEventLog() throws Exception {
        var sharedLog = system.sharedLog();

        // Phase 1: standalone actor builds event log (value = 5 + 3 = 8)
        Ref<Integer> seeder = system.spawnEventSourced("ctr", new SumEventActor(), new JavaSerializer<>());
        seeder.tell(5);
        seeder.tell(3);
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(seeder.<Integer>ask(0).get(5, TimeUnit.SECONDS)).isEqualTo(8));
        seeder.stop().get(5, TimeUnit.SECONDS);

        // Phase 2: supervised on same log — crash on first preStart
        var preStartCalls = new AtomicInteger(0);
        BayouSystem system2 = new BayouSystem(sharedLog);

        system2.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(ChildSpec.eventSourced("ctr", new SumEventActor() {
                    @Override
                    public void preStart(BayouContext ctx) {
                        if (preStartCalls.incrementAndGet() == 1) {
                            throw new RuntimeException("crash before first message");
                        }
                    }
                }, new JavaSerializer<>()));
            }
            public SupervisionStrategy strategy() {
                return new OneForOneStrategy(RestartWindow.UNLIMITED);
            }
        });

        // Wait for restart (preStartCalls reaches 2)
        await().atMost(5, TimeUnit.SECONDS).until(() -> preStartCalls.get() >= 2);

        // State replayed from event log: 0 + 5 + 3 = 8; ask with 0 → reply = 8
        Ref<Integer> ctr = system2.<Integer>lookup("ctr").orElseThrow();
        int replayed = ctr.<Integer>ask(0).get(5, TimeUnit.SECONDS);
        assertThat(replayed).isEqualTo(8);

        system2.shutdown();
    }
}
