package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class NestedSupervisionTest {

    private BayouSystem system;

    @BeforeEach void setUp() throws Exception { system = BayouTestSupport.freshSystem(); }
    @AfterEach  void tearDown()               { system.shutdown(); }

    @Test
    void nestedChildCrashHandledByChildSupervisor() throws Exception {
        // Grandchild crashes once then recovers. Parent should not be involved —
        // the child supervisor handles the restart internally.
        var crashed = new AtomicBoolean(false);

        Actor<String> crashOnce = new Actor<>() {
            public void handle(String msg, BayouContext ctx) {}
            public void preStart(BayouContext ctx) {
                if (crashed.compareAndSet(false, true)) {
                    throw new RuntimeException("grandchild first-start crash");
                }
            }
        };

        system.spawnSupervisor("parent", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(
                    ChildSpec.supervisor("child-sup", new SupervisorActor() {
                        public List<ChildSpec> children() {
                            return List.of(ChildSpec.stateless("grandchild", crashOnce));
                        }
                        public SupervisionStrategy strategy() {
                            return new OneForOneStrategy(RestartWindow.UNLIMITED);
                        }
                    })
                );
            }
            public SupervisionStrategy strategy() {
                return new OneForOneStrategy(RestartWindow.UNLIMITED);
            }
        });

        // Grandchild recovers after restart by its direct supervisor
        await().atMost(5, TimeUnit.SECONDS).until(() ->
            system.lookup("grandchild").map(ActorRef::isAlive).orElse(false));

        assertThat(system.lookup("child-sup").map(ActorRef::isAlive)).contains(true);
        assertThat(system.lookup("parent").map(ActorRef::isAlive)).contains(true);
    }

    @Test
    void childSupervisorEscalationPropagatesUpToParent() {
        // Child supervisor has a window of 1 restart; always-crashing grandchild
        // causes it to escalate after 2 crashes.
        // Parent strategy is STOP — child supervisor is permanently stopped.
        Actor<String> alwaysCrashes = new Actor<>() {
            public void handle(String msg, BayouContext ctx) {}
            public void preStart(BayouContext ctx) {
                throw new RuntimeException("grandchild always crashes");
            }
        };

        SupervisorRef parent = system.spawnSupervisor("parent", new SupervisorActor() {
            public List<ChildSpec> children() {
                return List.of(
                    ChildSpec.supervisor("child-sup", new SupervisorActor() {
                        public List<ChildSpec> children() {
                            return List.of(ChildSpec.stateless("grandchild", alwaysCrashes));
                        }
                        public SupervisionStrategy strategy() {
                            // Window of 1: grandchild crashes 2 times → escalate
                            return new OneForOneStrategy(new RestartWindow(1, Duration.ofSeconds(10)));
                        }
                    })
                );
            }
            public SupervisionStrategy strategy() {
                // Parent permanently stops an escalating child supervisor
                return (childId, cause) -> RestartDecision.STOP;
            }
        });

        // Wait for child-sup to be dead AND grandchild deregistered (cleanup() runs after isAlive=false)
        await().atMost(10, TimeUnit.SECONDS).until(() ->
            system.lookup("child-sup").map(ref -> !ref.isAlive()).orElse(false)
            && system.lookup("grandchild").isEmpty());

        // Parent remains alive; child supervisor dead, grandchild unregistered
        assertThat(parent.isAlive()).isTrue();
        assertThat(system.lookup("child-sup").map(ActorRef::isAlive)).contains(false);
        assertThat(system.lookup("grandchild")).isEmpty();
    }
}
