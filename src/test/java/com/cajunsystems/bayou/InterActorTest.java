package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class InterActorTest {

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
    void actorCanSpawnChildViaContext() {
        var received = new CopyOnWriteArrayList<String>();

        // Parent spawns a child actor on its first message, then forwards to it.
        Ref<String> parent = system.spawn("parent", (msg, ctx) -> {
            Actor<String> childActor = (m, c) -> received.add("child:" + m);
            Ref<String> child = ctx.system().spawn("child", childActor);
            child.tell(msg);
        });

        parent.tell("hello");

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(received).containsExactly("child:hello"));
    }

    @Test
    void lookupReturnsRefForSpawnedActor() {
        system.spawn("known", (msg, ctx) -> {});

        Optional<Ref<String>> ref = system.lookup("known");
        assertThat(ref).isPresent();
        assertThat(ref.get().actorId()).isEqualTo("known");
    }

    @Test
    void lookupReturnsEmptyForUnknownActor() {
        var ref = system.lookup("no-such-actor");
        assertThat(ref).isEmpty();
    }

    @Test
    void isAliveReturnsTrueWhileRunning() {
        Ref<String> actor = system.spawn("alive-check", (msg, ctx) -> {});
        assertThat(actor.isAlive()).isTrue();
    }

    @Test
    void isAliveReturnsFalseAfterStop() throws Exception {
        Ref<String> actor = system.spawn("dying", (msg, ctx) -> {});
        actor.stop().get(5, TimeUnit.SECONDS);
        assertThat(actor.isAlive()).isFalse();
    }

    @Test
    void askWithTimeoutCompletesNormally() throws Exception {
        Ref<String> actor = system.spawn("timed-ask",
                (msg, ctx) -> ctx.reply(msg.toUpperCase()));

        String reply = actor.<String>ask("ping", java.time.Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
        assertThat(reply).isEqualTo("PING");
    }

    @Test
    void askWithTimeoutExpiresWhenNoReply() {
        Ref<String> actor = system.spawn("no-reply", (msg, ctx) -> { /* deliberately no reply */ });

        var future = actor.<String>ask("ping", java.time.Duration.ofMillis(200));
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> future.get(1, TimeUnit.SECONDS));
    }
}
