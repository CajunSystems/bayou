package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SupervisorActorTest {

    private BayouSystem system;

    @BeforeEach void setUp() throws Exception { system = BayouTestSupport.freshSystem(); }
    @AfterEach  void tearDown()               { system.shutdown(); }

    @Test
    void supervisorStartsAllDeclaredChildren() throws Exception {
        var ref = system.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(
                    ChildSpec.stateless("child-a", (msg, ctx) -> {}),
                    ChildSpec.stateless("child-b", (msg, ctx) -> {})
                );
            }
            public SupervisionStrategy strategy() {
                return new OneForOneStrategy(RestartWindow.UNLIMITED);
            }
        });

        await().atMost(2, TimeUnit.SECONDS).until(() ->
            system.lookup("child-a").map(ActorRef::isAlive).orElse(false)
            && system.lookup("child-b").map(ActorRef::isAlive).orElse(false)
        );

        assertThat(ref.isAlive()).isTrue();
        assertThat(system.lookup("child-a")).isPresent();
        assertThat(system.lookup("child-b")).isPresent();
    }

    @Test
    void childrenReceiveMessagesNormally() throws Exception {
        var received = new CopyOnWriteArrayList<String>();

        system.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(ChildSpec.stateless("worker",
                        (String msg, BayouContext ctx) -> received.add(msg)));
            }
            public SupervisionStrategy strategy() {
                return new OneForOneStrategy(RestartWindow.UNLIMITED);
            }
        });

        await().atMost(2, TimeUnit.SECONDS).until(() ->
            system.lookup("worker").map(ActorRef::isAlive).orElse(false));

        ActorRef<String> worker = system.<String>lookup("worker").orElseThrow();
        worker.tell("hello");
        worker.tell("world");

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(received).containsExactly("hello", "world"));
    }

    @Test
    void stoppingSupervisorStopsAllChildren() throws Exception {
        var ref = system.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(
                    ChildSpec.stateless("c1", (msg, ctx) -> {}),
                    ChildSpec.stateless("c2", (msg, ctx) -> {})
                );
            }
            public SupervisionStrategy strategy() {
                return new OneForOneStrategy(RestartWindow.UNLIMITED);
            }
        });

        await().atMost(2, TimeUnit.SECONDS).until(() ->
            system.lookup("c1").map(ActorRef::isAlive).orElse(false));

        ref.stop().get(5, TimeUnit.SECONDS);

        assertThat(ref.isAlive()).isFalse();
        assertThat(system.lookup("c1").map(ActorRef::isAlive)).contains(false);
        assertThat(system.lookup("c2").map(ActorRef::isAlive)).contains(false);
    }

    @Test
    void supervisorReceivesCrashSignalFromChild() throws Exception {
        var decisions = new CopyOnWriteArrayList<RestartDecision>();

        // preStart() throwing crashes the loop (propagates past processEnvelope's catch)
        Actor<String> crashingActor = new Actor<>() {
            public void handle(String msg, BayouContext ctx) {}
            public void preStart(BayouContext ctx) {
                throw new RuntimeException("preStart crash");
            }
        };

        system.spawnSupervisor("sup", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(ChildSpec.stateless("crasher", crashingActor));
            }
            public SupervisionStrategy strategy() {
                // Capture decisions without affecting the decision
                return (childId, cause) -> {
                    RestartDecision d = new OneForOneStrategy(RestartWindow.UNLIMITED)
                            .decide(childId, cause);
                    decisions.add(d);
                    return d;
                };
            }
        });

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(decisions).hasSize(1));

        assertThat(decisions.get(0)).isEqualTo(RestartDecision.RESTART);
    }
}
