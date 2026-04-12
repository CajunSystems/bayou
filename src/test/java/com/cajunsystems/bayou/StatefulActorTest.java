package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.StatefulActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class StatefulActorTest {

    // ── Domain model ─────────────────────────────────────────────────────────

    record Tally(Map<String, Integer> counts) implements Serializable {
        Tally() { this(new HashMap<>()); }

        Tally add(String word) {
            var next = new HashMap<>(counts);
            next.merge(word, 1, Integer::sum);
            return new Tally(next);
        }
    }

    sealed interface TallyCmd {
        record Count(String word) implements TallyCmd {}
        record Get(String word)   implements TallyCmd {}
        record GetAll()           implements TallyCmd {}
    }

    static class WordCounter implements StatefulActor<Tally, TallyCmd> {

        @Override
        public Tally initialState() {
            return new Tally();
        }

        @Override
        public Tally reduce(Tally state, TallyCmd msg, BayouContext ctx) {
            return switch (msg) {
                case TallyCmd.Count(String word) -> state.add(word);
                case TallyCmd.Get(String word)   -> {
                    ctx.reply(state.counts().getOrDefault(word, 0));
                    yield state;
                }
                case TallyCmd.GetAll() -> {
                    ctx.reply(Map.copyOf(state.counts()));
                    yield state;
                }
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
    void reducerAccumulatesState() throws Exception {
        ActorRef<TallyCmd> actor = system.spawnStateful(
                "tally", new WordCounter(), new JavaSerializer<>());

        actor.tell(new TallyCmd.Count("foo"));
        actor.tell(new TallyCmd.Count("foo"));
        actor.tell(new TallyCmd.Count("bar"));

        int foo = actor.<Integer>ask(new TallyCmd.Get("foo")).get(5, TimeUnit.SECONDS);
        int bar = actor.<Integer>ask(new TallyCmd.Get("bar")).get(5, TimeUnit.SECONDS);
        assertThat(foo).isEqualTo(2);
        assertThat(bar).isEqualTo(1);
    }

    @Test
    void snapshotRestoredOnRestart() throws Exception {
        var sharedLog = system.sharedLog();

        // Use interval=1 so a snapshot is taken after every single message
        ActorRef<TallyCmd> actor1 = system.spawnStateful(
                "tally-snap", new WordCounter(), new JavaSerializer<>(), 1);

        actor1.tell(new TallyCmd.Count("hello"));
        actor1.tell(new TallyCmd.Count("world"));
        actor1.stop().get(5, TimeUnit.SECONDS);

        // New system, same log — should restore from snapshot
        BayouSystem system2 = new BayouSystem(sharedLog);
        ActorRef<TallyCmd> actor2 = system2.spawnStateful(
                "tally-snap", new WordCounter(), new JavaSerializer<>(), 1);

        @SuppressWarnings("unchecked")
        Map<String, Integer> all = actor2.<Map<String, Integer>>ask(new TallyCmd.GetAll())
                                         .get(5, TimeUnit.SECONDS);
        assertThat(all).containsEntry("hello", 1).containsEntry("world", 1);
        system2.shutdown();
    }

    @Test
    void snapshotIntervalTriggersAutomatically() throws Exception {
        // With interval=2, a snapshot should be written after every 2nd message
        ActorRef<TallyCmd> actor = system.spawnStateful(
                "snap-interval", new WordCounter(), new JavaSerializer<>(), 2);

        // Send 4 messages → 2 automatic snapshots should be taken
        actor.tell(new TallyCmd.Count("a"));
        actor.tell(new TallyCmd.Count("b"));
        actor.tell(new TallyCmd.Count("c"));
        actor.tell(new TallyCmd.Count("d"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> all = actor.<Map<String, Integer>>ask(new TallyCmd.GetAll())
                                         .get(5, TimeUnit.SECONDS);
        assertThat(all).containsOnlyKeys("a", "b", "c", "d");
    }

    @Test
    void errorInReducerLeavesStatUnchanged() throws Exception {
        var errorCount = new AtomicInteger(0);

        ActorRef<TallyCmd> actor = system.spawnStateful("error-state",
                new StatefulActor<>() {
                    @Override
                    public Tally initialState() { return new Tally(); }

                    @Override
                    public Tally reduce(Tally state, TallyCmd msg, BayouContext ctx) {
                        if (msg instanceof TallyCmd.Count(String word) && word.equals("BOMB")) {
                            throw new RuntimeException("deliberate error");
                        }
                        return switch (msg) {
                            case TallyCmd.Count(String w)  -> state.add(w);
                            case TallyCmd.Get(String w)    -> { ctx.reply(state.counts().getOrDefault(w, 0)); yield state; }
                            case TallyCmd.GetAll()         -> { ctx.reply(Map.copyOf(state.counts())); yield state; }
                        };
                    }

                    @Override
                    public void onError(TallyCmd msg, Throwable err, BayouContext ctx) {
                        errorCount.incrementAndGet();
                    }
                },
                new JavaSerializer<>());

        actor.tell(new TallyCmd.Count("good"));
        actor.tell(new TallyCmd.Count("BOMB"));   // should error, state rolls back
        actor.tell(new TallyCmd.Count("also-good"));

        await().atMost(5, TimeUnit.SECONDS).until(() -> errorCount.get() == 1);

        @SuppressWarnings("unchecked")
        Map<String, Integer> all = actor.<Map<String, Integer>>ask(new TallyCmd.GetAll())
                                         .get(5, TimeUnit.SECONDS);
        assertThat(all).containsKeys("good", "also-good").doesNotContainKey("BOMB");
    }

    @Test
    void snapshotIntervalZeroThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                system.spawnStateful("bad-interval", new WordCounter(), new JavaSerializer<>(), 0));
    }

    @Test
    void preStartAndPostStopCalled() throws Exception {
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
        ActorRef<TallyCmd> actor = system.spawnStateful("lifecycle-sf",
                new StatefulActor<>() {
                    @Override public Tally initialState() { return new Tally(); }
                    @Override public Tally reduce(Tally s, TallyCmd m, BayouContext c) { return s; }
                    @Override public void preStart(BayouContext ctx)  { events.add("start"); }
                    @Override public void postStop(BayouContext ctx)  { events.add("stop"); }
                },
                new JavaSerializer<>());

        await().atMost(5, TimeUnit.SECONDS).until(() -> events.contains("start"));
        actor.stop().get(5, TimeUnit.SECONDS);
        assertThat(events).containsExactly("start", "stop");
    }
}
