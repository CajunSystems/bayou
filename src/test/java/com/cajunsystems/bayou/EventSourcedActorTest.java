package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.EventSourcedActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EventSourcedActorTest {

    // ── Domain model ─────────────────────────────────────────────────────────

    record CounterState(int value) implements Serializable {}

    sealed interface CounterEvent extends Serializable {
        record Incremented(int by) implements CounterEvent {}
        record Reset()             implements CounterEvent {}
    }

    sealed interface CounterCmd {
        record Increment(int by) implements CounterCmd {}
        record Reset()           implements CounterCmd {}
        record GetValue()        implements CounterCmd {}
    }

    static class CounterActor implements EventSourcedActor<CounterState, CounterEvent, CounterCmd> {

        @Override
        public CounterState initialState() {
            return new CounterState(0);
        }

        @Override
        public List<CounterEvent> handle(CounterState state, CounterCmd msg, BayouContext ctx) {
            return switch (msg) {
                case CounterCmd.Increment(int by) -> List.of(new CounterEvent.Incremented(by));
                case CounterCmd.Reset()           -> List.of(new CounterEvent.Reset());
                case CounterCmd.GetValue()        -> {
                    ctx.reply(state.value());
                    yield List.of();
                }
            };
        }

        @Override
        public CounterState apply(CounterState state, CounterEvent event) {
            return switch (event) {
                case CounterEvent.Incremented(int by) -> new CounterState(state.value() + by);
                case CounterEvent.Reset()             -> new CounterState(0);
            };
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

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
    void stateAccumulatesAcrossMessages() throws Exception {
        ActorRef<CounterCmd> counter = system.spawnEventSourced(
                "counter", new CounterActor(), new JavaSerializer<>());

        counter.tell(new CounterCmd.Increment(3));
        counter.tell(new CounterCmd.Increment(7));

        int value = counter.<Integer>ask(new CounterCmd.GetValue()).get(5, TimeUnit.SECONDS);
        assertThat(value).isEqualTo(10);
    }

    @Test
    void resetEventClearsState() throws Exception {
        ActorRef<CounterCmd> counter = system.spawnEventSourced(
                "counter-reset", new CounterActor(), new JavaSerializer<>());

        counter.tell(new CounterCmd.Increment(5));
        counter.tell(new CounterCmd.Reset());
        counter.tell(new CounterCmd.Increment(2));

        int value = counter.<Integer>ask(new CounterCmd.GetValue()).get(5, TimeUnit.SECONDS);
        assertThat(value).isEqualTo(2);
    }

    @Test
    void stateIsReplayedOnRestart() throws Exception {
        // Shared log persists events; a new system backed by the same log should replay them.
        // We use two BayouSystem instances sharing the same SharedLogService.
        var sharedLog = system.sharedLog();

        ActorRef<CounterCmd> counter1 = system.spawnEventSourced(
                "replay-counter", new CounterActor(), new JavaSerializer<>());
        counter1.tell(new CounterCmd.Increment(10));
        counter1.tell(new CounterCmd.Increment(5));
        counter1.stop().get(5, TimeUnit.SECONDS);

        // Second system re-uses the same underlying log
        BayouSystem system2 = new BayouSystem(sharedLog);
        ActorRef<CounterCmd> counter2 = system2.spawnEventSourced(
                "replay-counter", new CounterActor(), new JavaSerializer<>());

        int value = counter2.<Integer>ask(new CounterCmd.GetValue()).get(5, TimeUnit.SECONDS);
        assertThat(value).isEqualTo(15);
        system2.shutdown();
    }

    @Test
    void readOnlyQueryProducesNoEvents() throws Exception {
        ActorRef<CounterCmd> counter = system.spawnEventSourced(
                "ro-counter", new CounterActor(), new JavaSerializer<>());

        // GetValue is a pure read — no events should be emitted
        int v1 = counter.<Integer>ask(new CounterCmd.GetValue()).get(5, TimeUnit.SECONDS);
        int v2 = counter.<Integer>ask(new CounterCmd.GetValue()).get(5, TimeUnit.SECONDS);
        assertThat(v1).isEqualTo(0);
        assertThat(v2).isEqualTo(0);
    }

    @Test
    void preStartCalledAfterReplay() throws Exception {
        var startCount = new AtomicInteger(0);
        ActorRef<CounterCmd> counter = system.spawnEventSourced(
                "lifecycle-es",
                new CounterActor() {
                    @Override
                    public void preStart(BayouContext ctx) {
                        startCount.incrementAndGet();
                    }
                },
                new JavaSerializer<>());

        await().atMost(5, TimeUnit.SECONDS).until(() -> startCount.get() == 1);
        assertThat(startCount.get()).isEqualTo(1);
    }

    @Test
    void postStopCalledAfterStop() throws Exception {
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
        ActorRef<CounterCmd> counter = system.spawnEventSourced(
                "lifecycle-es-stop",
                new CounterActor() {
                    @Override public void preStart(BayouContext ctx) { events.add("start"); }
                    @Override public void postStop(BayouContext ctx) { events.add("stop"); }
                },
                new JavaSerializer<>());

        await().atMost(5, TimeUnit.SECONDS).until(() -> events.contains("start"));
        counter.stop().get(5, TimeUnit.SECONDS);
        assertThat(events).containsExactly("start", "stop");
    }
}
