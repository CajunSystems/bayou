package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

class PubSubTest {

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
    void multipleSubscribers_allReceivePublished() {
        var received1 = new CopyOnWriteArrayList<String>();
        var received2 = new CopyOnWriteArrayList<String>();
        var received3 = new CopyOnWriteArrayList<String>();

        Ref<String> a1 = system.spawn("sub1", (msg, ctx) -> received1.add(msg));
        Ref<String> a2 = system.spawn("sub2", (msg, ctx) -> received2.add(msg));
        Ref<String> a3 = system.spawn("sub3", (msg, ctx) -> received3.add(msg));

        system.pubsub().subscribe("news", a1);
        system.pubsub().subscribe("news", a2);
        system.pubsub().subscribe("news", a3);

        system.pubsub().publish("news", "breaking");

        await().atMost(3, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(received1).containsExactly("breaking"));
        await().atMost(3, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(received2).containsExactly("breaking"));
        await().atMost(3, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(received3).containsExactly("breaking"));
    }

    @Test
    void unsubscribe_stopsDelivery() throws Exception {
        var received = new CopyOnWriteArrayList<String>();

        Ref<String> actor = system.spawn("unsub-actor", (msg, ctx) -> received.add(msg));
        system.pubsub().subscribe("updates", actor);

        system.pubsub().publish("updates", "first");
        await().atMost(3, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(received).containsExactly("first"));

        system.pubsub().unsubscribe("updates", actor);
        system.pubsub().publish("updates", "second");
        Thread.sleep(200);

        assertThat(received).containsExactly("first");
    }

    @Test
    void deadActor_silentlySkippedOnPublish() throws Exception {
        var receivedLive = new CopyOnWriteArrayList<String>();

        Ref<String> dead = system.spawn("dead-actor", (msg, ctx) -> {});
        Ref<String> live = system.spawn("live-actor", (msg, ctx) -> receivedLive.add(msg));

        system.pubsub().subscribe("events", dead);
        system.pubsub().subscribe("events", live);

        dead.stop().get(2, TimeUnit.SECONDS);

        assertThatCode(() -> system.pubsub().publish("events", "hello")).doesNotThrowAnyException();

        await().atMost(3, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedLive).containsExactly("hello"));
    }

    @Test
    void topicIsolation_onlyCorrectTopicReceives() throws Exception {
        var receivedA = new CopyOnWriteArrayList<String>();
        var receivedB = new CopyOnWriteArrayList<String>();

        Ref<String> actorA = system.spawn("actor-a", (msg, ctx) -> receivedA.add(msg));
        Ref<String> actorB = system.spawn("actor-b", (msg, ctx) -> receivedB.add(msg));

        system.pubsub().subscribe("topic-a", actorA);
        system.pubsub().subscribe("topic-b", actorB);

        system.pubsub().publish("topic-a", "only-a");

        await().atMost(3, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedA).containsExactly("only-a"));
        Thread.sleep(100);

        assertThat(receivedB).isEmpty();
    }

    @Test
    void ctxSelf_allowsInActorSubscription() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var preStartDone = new CountDownLatch(1);

        system.spawn("self-sub", new Actor<String>() {
            @Override
            public void handle(String msg, BayouContext<String> ctx) {
                received.add(msg);
            }

            @Override
            public void preStart(BayouContext<String> ctx) {
                ctx.system().pubsub().subscribe("greet", ctx.self());
                preStartDone.countDown();
            }
        });

        preStartDone.await(2, TimeUnit.SECONDS);
        system.pubsub().publish("greet", "hello-self");

        await().atMost(3, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(received).containsExactly("hello-self"));
    }
}
