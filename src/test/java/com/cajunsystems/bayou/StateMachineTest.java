package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.StateMachineActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class StateMachineTest {

    enum TrafficLight { RED, GREEN, YELLOW }

    BayouSystem system;

    @BeforeEach
    void setUp() throws Exception {
        system = BayouTestSupport.freshSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    @Test
    void onEnter_calledForInitialState() throws Exception {
        var latch = new java.util.concurrent.CountDownLatch(1);
        var enteredStates = new java.util.concurrent.CopyOnWriteArrayList<TrafficLight>();

        system.spawnStateMachine("fsm-init", new StateMachineActor<TrafficLight, String>() {
            @Override
            public Optional<TrafficLight> transition(TrafficLight state, String msg, BayouContext<String> ctx) {
                return Optional.empty();
            }
            @Override
            public void onEnter(TrafficLight state, BayouContext<String> ctx) {
                enteredStates.add(state);
                latch.countDown();
            }
        }, TrafficLight.RED);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(enteredStates).containsExactly(TrafficLight.RED);
    }

    @Test
    void validTransition_changesState_firesCallbacksInOrder() {
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();

        Ref<String> actor = system.spawnStateMachine("fsm-trans",
            new StateMachineActor<TrafficLight, String>() {
                @Override
                public Optional<TrafficLight> transition(TrafficLight s, String msg, BayouContext<String> ctx) {
                    if (s == TrafficLight.RED && "go".equals(msg)) return Optional.of(TrafficLight.GREEN);
                    return Optional.empty();
                }
                @Override public void onEnter(TrafficLight s, BayouContext<String> ctx) { events.add("enter:" + s); }
                @Override public void onExit(TrafficLight s, BayouContext<String> ctx)  { events.add("exit:"  + s); }
            },
            TrafficLight.RED);

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(events).contains("enter:RED"));

        actor.tell("go");

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(events).containsExactly("enter:RED", "exit:RED", "enter:GREEN"));
    }

    @Test
    void ignoredMessage_staysInState_noCallbacks() throws Exception {
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var processed = new java.util.concurrent.CountDownLatch(1);

        Ref<String> actor = system.spawnStateMachine("fsm-ignore",
            new StateMachineActor<TrafficLight, String>() {
                @Override
                public Optional<TrafficLight> transition(TrafficLight s, String msg, BayouContext<String> ctx) {
                    processed.countDown();
                    return Optional.empty();
                }
                @Override public void onEnter(TrafficLight s, BayouContext<String> ctx) { events.add("enter:" + s); }
                @Override public void onExit(TrafficLight s, BayouContext<String> ctx)  { events.add("exit:"  + s); }
            },
            TrafficLight.RED);

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(events).contains("enter:RED"));

        actor.tell("unknown");
        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();

        Thread.sleep(100);
        assertThat(events).containsExactly("enter:RED");
    }

    @Test
    void fullCycle_trafficLight() {
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();

        Ref<String> actor = system.spawnStateMachine("traffic",
            new StateMachineActor<TrafficLight, String>() {
                @Override
                public Optional<TrafficLight> transition(TrafficLight s, String msg, BayouContext<String> ctx) {
                    return switch (msg) {
                        case "go"   -> s == TrafficLight.RED    ? Optional.of(TrafficLight.GREEN)  : Optional.empty();
                        case "slow" -> s == TrafficLight.GREEN  ? Optional.of(TrafficLight.YELLOW) : Optional.empty();
                        case "stop" -> s == TrafficLight.YELLOW ? Optional.of(TrafficLight.RED)    : Optional.empty();
                        default     -> Optional.empty();
                    };
                }
                @Override public void onEnter(TrafficLight s, BayouContext<String> ctx) { events.add("enter:" + s); }
                @Override public void onExit(TrafficLight s, BayouContext<String> ctx)  { events.add("exit:"  + s); }
            },
            TrafficLight.RED);

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(events).contains("enter:RED"));

        actor.tell("go");
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(events).contains("enter:GREEN"));

        actor.tell("slow");
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(events).contains("enter:YELLOW"));

        actor.tell("stop");
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(events).containsSubsequence(
                "enter:RED",
                "exit:RED", "enter:GREEN",
                "exit:GREEN", "enter:YELLOW",
                "exit:YELLOW", "enter:RED"
            ));
    }

    @Test
    void postStop_calledOnGracefulStop() throws Exception {
        var stopped = new java.util.concurrent.CountDownLatch(1);

        Ref<String> actor = system.spawnStateMachine("fsm-stop",
            new StateMachineActor<TrafficLight, String>() {
                @Override
                public Optional<TrafficLight> transition(TrafficLight s, String m, BayouContext<String> ctx) {
                    return Optional.empty();
                }
                @Override
                public void postStop(BayouContext<String> ctx) {
                    stopped.countDown();
                }
            },
            TrafficLight.RED);

        actor.stop().get(2, TimeUnit.SECONDS);
        assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
    }
}
