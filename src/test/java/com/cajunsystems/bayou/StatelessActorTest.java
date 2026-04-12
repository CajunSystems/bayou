package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.StatelessActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class StatelessActorTest {

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
    void tellDeliverMessagesInOrder() {
        var received = new CopyOnWriteArrayList<String>();
        ActorRef<String> actor = system.spawn("echo", (msg, ctx) -> received.add(msg));

        actor.tell("a");
        actor.tell("b");
        actor.tell("c");

        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(received).containsExactly("a", "b", "c"));
    }

    @Test
    void askReturnsReply() throws Exception {
        ActorRef<String> actor = system.spawn("upper", (msg, ctx) -> ctx.reply(msg.toUpperCase()));

        CompletableFuture<String> future = actor.ask("hello");
        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo("HELLO");
    }

    @Test
    void lambdaImplementsStatelessActor() {
        // StatelessActor is @FunctionalInterface — verify lambda works
        StatelessActor<Integer> noop = (msg, ctx) -> {};
        ActorRef<Integer> ref = system.spawn("noop", noop);
        ref.tell(42); // should not throw
    }

    @Test
    void preStartAndPostStopAreCalled() throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        ActorRef<String> actor = system.spawn("lifecycle", new StatelessActor<>() {
            @Override public void handle(String msg, BayouContext ctx) { events.add("msg:" + msg); }
            @Override public void preStart(BayouContext ctx)  { events.add("start"); }
            @Override public void postStop(BayouContext ctx)  { events.add("stop"); }
        });

        actor.tell("x");
        await().atMost(2, TimeUnit.SECONDS).until(() -> events.contains("msg:x"));

        actor.stop().get(2, TimeUnit.SECONDS);

        assertThat(events).startsWith("start").endsWith("stop");
    }

    @Test
    void errorInHandlerCallsOnError() throws Exception {
        var errorCaptured = new AtomicInteger(0);
        ActorRef<String> actor = system.spawn("faulty", new StatelessActor<>() {
            @Override public void handle(String msg, BayouContext ctx) {
                throw new RuntimeException("boom");
            }
            @Override public void onError(String msg, Throwable err, BayouContext ctx) {
                errorCaptured.incrementAndGet();
            }
        });

        actor.tell("trigger");
        await().atMost(2, TimeUnit.SECONDS).until(() -> errorCaptured.get() == 1);
    }

    @Test
    void duplicateActorIdThrows() {
        system.spawn("dup", (msg, ctx) -> {});
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> system.spawn("dup", (msg, ctx) -> {}));
    }
}
