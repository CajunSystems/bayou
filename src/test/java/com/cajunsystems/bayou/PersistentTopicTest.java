package com.cajunsystems.bayou;

import com.cajunsystems.gumbo.core.LogTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

class PersistentTopicTest {

    BayouSystem system;

    @BeforeEach
    void setUp() throws Exception {
        system = BayouTestSupport.freshSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    // ── Test 1: publish delivers to live subscribers ──────────────────────────

    @Test
    void publishDeliversToLiveSubscribers() {
        var received1 = new CopyOnWriteArrayList<String>();
        var received2 = new CopyOnWriteArrayList<String>();

        Ref<String> sub1 = system.spawn("sub1", (msg, ctx) -> received1.add(msg));
        Ref<String> sub2 = system.spawn("sub2", (msg, ctx) -> received2.add(msg));

        BayouSerializer<String> ser = new JavaSerializer<>();
        BayouTopic<String> topic = system.topic("news", ser);

        topic.subscribe(sub1);
        topic.subscribe(sub2);

        topic.publish("msg1");
        topic.publish("msg2");
        topic.publish("msg3");

        // 2 subscribers × 3 messages = 6 total deliveries
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(received1).hasSize(3);
                   assertThat(received2).hasSize(3);
               });

        assertThat(received1).containsExactly("msg1", "msg2", "msg3");
        assertThat(received2).containsExactly("msg1", "msg2", "msg3");
    }

    // ── Test 2: unsubscribe stops delivery ────────────────────────────────────

    @Test
    void unsubscribeStopsDelivery() throws Exception {
        var received = new CopyOnWriteArrayList<String>();

        Ref<String> sub = system.spawn("unsub-actor", (msg, ctx) -> received.add(msg));

        BayouSerializer<String> ser = new JavaSerializer<>();
        BayouTopic<String> topic = system.topic("updates", ser);

        topic.subscribe(sub);
        topic.publish("first");

        await().atMost(3, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(received).containsExactly("first"));

        topic.unsubscribe(sub);
        topic.publish("second");

        Thread.sleep(300);

        assertThat(received).containsExactly("first");
    }

    // ── Test 3: topic() is idempotent ─────────────────────────────────────────

    @Test
    void topicIsIdempotent() {
        BayouSerializer<String> ser = new JavaSerializer<>();
        BayouTopic<String> t1 = system.topic("news-idem", ser);
        BayouTopic<String> t2 = system.topic("news-idem", ser);
        assertThat(t1).isSameAs(t2);
    }

    // ── Test 4: publish persists to log across system restart ─────────────────

    @Test
    void publishPersistsToLog() throws Exception {
        // Use a shared log that survives across two BayouSystem instances.
        // system1 reuses the @BeforeEach system's underlying SharedLog.
        var sharedLog = system.sharedLog();

        BayouSerializer<String> ser = new JavaSerializer<>();
        BayouTopic<String> topic = system.topic("events", ser);

        for (int i = 0; i < 10; i++) {
            topic.publish("msg-" + i);
        }

        // Stop only the topic actor — drains its mailbox so all appends complete.
        // This leaves sharedLog alive for system2.
        system.lookup("bayou-topic-events")
              .orElseThrow()
              .stop()
              .get(5, TimeUnit.SECONDS);

        // Create system2 backed by the same SharedLog (still open)
        BayouSystem system2 = new BayouSystem(sharedLog);

        var logView = system2.sharedLog().getView(LogTag.of("bayou.topic", "events"));
        var entries = logView.readAll().join();
        assertThat(entries).hasSize(10);

        system2.shutdown();
    }

    // ── Test 5: topic isolation ───────────────────────────────────────────────

    @Test
    void topicIsolation() throws Exception {
        var receivedA = new CopyOnWriteArrayList<String>();
        var receivedB = new CopyOnWriteArrayList<String>();

        Ref<String> actorA = system.spawn("actor-a", (msg, ctx) -> receivedA.add(msg));
        Ref<String> actorB = system.spawn("actor-b", (msg, ctx) -> receivedB.add(msg));

        BayouSerializer<String> ser = new JavaSerializer<>();
        BayouTopic<String> topicA = system.topic("topic-a", ser);
        BayouTopic<String> topicB = system.topic("topic-b", ser);

        topicA.subscribe(actorA);
        topicB.subscribe(actorB);

        topicA.publish("only-for-a-1");
        topicA.publish("only-for-a-2");

        await().atMost(3, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedA).hasSize(2));

        Thread.sleep(200);

        assertThat(receivedA).containsExactly("only-for-a-1", "only-for-a-2");
        assertThat(receivedB).isEmpty();
    }

    // ── Test 6: dead subscriber is cleaned up silently ────────────────────────

    @Test
    void deadSubscriberIsCleanedUp() throws Exception {
        Ref<String> dead = system.spawn("dead-sub", (msg, ctx) -> {});

        BayouSerializer<String> ser = new JavaSerializer<>();
        BayouTopic<String> topic = system.topic("cleanup-topic", ser);

        topic.subscribe(dead);

        dead.stop().get(2, TimeUnit.SECONDS);

        assertThatCode(() -> topic.publish("after-death")).doesNotThrowAnyException();

        // Allow the topic actor to process the publish; system must still be alive
        Thread.sleep(300);
        assertThat(system.lookup("bayou-topic-cleanup-topic"))
                .isPresent()
                .get()
                .matches(Ref::isAlive);
    }
}
