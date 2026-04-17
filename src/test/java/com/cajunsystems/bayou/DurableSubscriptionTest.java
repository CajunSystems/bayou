package com.cajunsystems.bayou;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DurableSubscriptionTest {

    BayouSystem system;

    @BeforeEach
    void setUp() throws Exception {
        system = BayouTestSupport.freshSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    // ── Test 1: durable vs non-durable gap behaviour ──────────────────────────

    @Test
    void doneWhen_durableReceivesAllNonDurableMissesGap() throws Exception {
        var receivedA = new CopyOnWriteArrayList<Integer>();
        var receivedB = new CopyOnWriteArrayList<Integer>();

        Ref<Integer> aRef = system.spawn("actor-a-1", (msg, ctx) -> receivedA.add(msg));
        Ref<Integer> bRef = system.spawn("actor-b-1", (msg, ctx) -> receivedB.add(msg));

        BayouSerializer<Integer> ser = new JavaSerializer<>();
        BayouTopic<Integer> topic = system.topic("topic-gap", ser);

        // Register A as durable, B as non-durable
        topic.subscribe("sub-a", aRef);
        topic.subscribe(bRef);

        // Publish messages 1–5, wait for both to receive all 5
        for (int i = 1; i <= 5; i++) {
            topic.publish(i);
        }
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(receivedA).hasSize(5);
                   assertThat(receivedB).hasSize(5);
               });

        // Remove B from live delivery
        topic.unsubscribe(bRef);

        // Publish messages 6–10 (A receives as durable; B is gone)
        for (int i = 6; i <= 10; i++) {
            topic.publish(i);
        }
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedA).hasSize(10));

        // Re-subscribe B non-durably (starts from now — no replay)
        topic.subscribe(bRef);

        // Assert final state: A received all 10, B only received the first 5
        assertThat(receivedA).hasSize(10);
        assertThat(receivedB).hasSize(5);
    }

    // ── Test 2: durable subscriber catches up on re-subscribe ─────────────────

    @Test
    void durableSubscriberCatchesUpOnResubscribe() throws Exception {
        var receivedA = new CopyOnWriteArrayList<Integer>();

        Ref<Integer> aRef = system.spawn("actor-a-2", (msg, ctx) -> receivedA.add(msg));

        BayouSerializer<Integer> ser = new JavaSerializer<>();
        BayouTopic<Integer> topic = system.topic("topic-catchup", ser);

        topic.subscribe("sub-a", aRef);

        // Publish 5 messages, wait for delivery
        for (int i = 1; i <= 5; i++) {
            topic.publish(i);
        }
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedA).hasSize(5));

        // Stop A — it is now dead and will be removed from durableSubscribers on next publish
        aRef.stop().get(2, TimeUnit.SECONDS);

        // Publish 5 more messages while A is dead
        for (int i = 6; i <= 10; i++) {
            topic.publish(i);
        }

        // Give the topic actor time to process publishes (and prune dead A)
        await().atMost(3, TimeUnit.SECONDS)
               .until(() -> !aRef.isAlive());

        // Spawn A2 and reconnect with the same subscription ID
        var receivedA2 = new CopyOnWriteArrayList<Integer>();
        Ref<Integer> a2Ref = system.spawn("actor-a2-2", (msg, ctx) -> receivedA2.add(msg));
        topic.subscribe("sub-a", a2Ref);

        // A2 should receive exactly the 5 missed messages via catch-up replay
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedA2).hasSize(5));
    }

    // ── Test 3: unsubscribeDurable forgets position ───────────────────────────

    @Test
    void unsubscribeDurableForgetPosition() throws Exception {
        var receivedA = new CopyOnWriteArrayList<Integer>();

        Ref<Integer> aRef = system.spawn("actor-a-3", (msg, ctx) -> receivedA.add(msg));

        BayouSerializer<Integer> ser = new JavaSerializer<>();
        BayouTopic<Integer> topic = system.topic("topic-forget", ser);

        topic.subscribe("sub-a", aRef);

        // Publish 5 messages, wait for delivery
        for (int i = 1; i <= 5; i++) {
            topic.publish(i);
        }
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedA).hasSize(5));

        // Delete the durable subscription position
        topic.unsubscribeDurable("sub-a");

        // Publish 5 more — position has been deleted, A is no longer in durableSubscribers
        for (int i = 6; i <= 10; i++) {
            topic.publish(i);
        }

        // Give actor time to process the publishes before spawning A2
        Thread.sleep(300);

        // Spawn A2 and re-subscribe with the same ID — KV is null → starts fresh from now
        var receivedA2 = new CopyOnWriteArrayList<Integer>();
        Ref<Integer> a2Ref = system.spawn("actor-a2-3", (msg, ctx) -> receivedA2.add(msg));
        topic.subscribe("sub-a", a2Ref);

        // Publish 1 final trigger message so A2 gets at least one delivery
        topic.publish(99);

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedA2).hasSize(1));

        // A2 should have received exactly 1 message (NOT 6 — the 5 after unsubscribe were missed)
        assertThat(receivedA2).containsExactly(99);
    }

    // ── Test 4: two subscriptions progress independently ──────────────────────

    @Test
    void twoSubscriptionsProgressIndependently() throws Exception {
        var receivedA = new CopyOnWriteArrayList<Integer>();
        var receivedB = new CopyOnWriteArrayList<Integer>();

        Ref<Integer> aRef = system.spawn("actor-a-4", (msg, ctx) -> receivedA.add(msg));
        Ref<Integer> bRef = system.spawn("actor-b-4", (msg, ctx) -> receivedB.add(msg));

        BayouSerializer<Integer> ser = new JavaSerializer<>();
        BayouTopic<Integer> topic = system.topic("topic-independent", ser);

        topic.subscribe("sub-a", aRef);
        topic.subscribe("sub-b", bRef);

        // Publish 5 messages — both receive 5
        for (int i = 1; i <= 5; i++) {
            topic.publish(i);
        }
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(receivedA).hasSize(5);
                   assertThat(receivedB).hasSize(5);
               });

        // Stop A — B keeps receiving
        aRef.stop().get(2, TimeUnit.SECONDS);

        // Publish 5 more — B receives 5 more (total 10); A is pruned from map
        for (int i = 6; i <= 10; i++) {
            topic.publish(i);
        }
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedB).hasSize(10));

        // Spawn A2 and reconnect with sub-a — should catch up with 5 missed messages
        var receivedA2 = new CopyOnWriteArrayList<Integer>();
        Ref<Integer> a2Ref = system.spawn("actor-a2-4", (msg, ctx) -> receivedA2.add(msg));
        topic.subscribe("sub-a", a2Ref);

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedA2).hasSize(5));

        // Final assertions
        assertThat(receivedA2).hasSize(5);
        assertThat(receivedB).hasSize(10);
    }
}
