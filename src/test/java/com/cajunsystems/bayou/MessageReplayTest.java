package com.cajunsystems.bayou;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tests for message replay via {@link BayouTopic#subscribeFrom},
 * {@link BayouTopic#subscribeFromBeginning}, and {@link BayouTopic#latestOffset}.
 *
 * <p>Note: gumbo seqnums are 0-indexed. An empty log returns {@code -1} from
 * {@code latestOffset()}; after N publishes the latest offset is {@code N-1}.
 */
class MessageReplayTest {

    BayouSystem system;

    @BeforeEach
    void setUp() throws Exception {
        system = BayouTestSupport.freshSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    // ── Test 1: subscribeFromBeginning receives all history then live ─────────

    @Test
    void doneWhen_subscribeFromBeginningReceivesAllHistoryThenLive() {
        BayouSerializer<String> ser = new JavaSerializer<>();
        BayouTopic<String> topic = system.topic("replay-beginning", ser);

        // Publish 100 messages (seqnums 0-99)
        for (int i = 1; i <= 100; i++) {
            topic.publish("msg-" + i);
        }

        // Wait until all 100 are in the log (0-indexed: latest seqnum = 99)
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> topic.latestOffset() >= 99);

        AtomicInteger counter = new AtomicInteger(0);
        Ref<String> subscriber = system.spawn("replay-beginning-sub", (msg, ctx) -> counter.incrementAndGet());
        topic.subscribeFromBeginning(subscriber);

        // Wait for all 100 historical messages
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> counter.get() >= 100);

        // Publish 1 more ("msg-101")
        topic.publish("msg-101");

        // Wait for 101 total
        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> counter.get() >= 101);

        assertThat(counter.get()).isEqualTo(101);
    }

    // ── Test 2: subscribeFrom offset receives only messages from that offset ──

    @Test
    void subscribeFromOffsetReceivesOnlyMessagesFromThatOffset() throws Exception {
        BayouSerializer<String> ser = new JavaSerializer<>();
        BayouTopic<String> topic = system.topic("replay-offset", ser);

        // Publish 10 messages (seqnums 0-9)
        for (int i = 1; i <= 10; i++) {
            topic.publish("msg-" + i);
        }

        // Wait until all 10 are in the log (0-indexed: latest seqnum = 9)
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> topic.latestOffset() >= 9);

        AtomicInteger counter = new AtomicInteger(0);
        Ref<String> subscriber = system.spawn("replay-offset-sub", (msg, ctx) -> counter.incrementAndGet());

        // subscribeFrom(4) receives entries with seqnum > 4 (seqnums 5,6,7,8,9 = 5 messages)
        topic.subscribeFrom(4, subscriber);

        // Wait for exactly 5 messages
        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> counter.get() >= 5);

        // Verify no 6th message arrives
        Thread.sleep(300);
        assertThat(counter.get()).isEqualTo(5);
    }

    // ── Test 3: replay arrives before subsequent live messages ────────────────

    @Test
    void replayArrivesBeforeSubsequentLiveMessages() {
        BayouSerializer<String> ser = new JavaSerializer<>();
        BayouTopic<String> topic = system.topic("replay-ordering", ser);

        ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();

        // Publish 5 historical messages (seqnums 0-4)
        for (int i = 1; i <= 5; i++) {
            topic.publish("hist-" + i);
        }

        // Wait until all 5 are in the log (0-indexed: latest seqnum = 4)
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> topic.latestOffset() >= 4);

        Ref<String> subscriber = system.spawn("replay-ordering-sub", (msg, ctx) -> received.add(msg));
        // Use -1 to read from beginning (gumbo seqnums are 0-indexed; readAfter(-1) returns all)
        topic.subscribeFrom(-1, subscriber);

        // Publish 5 live messages
        for (int i = 1; i <= 5; i++) {
            topic.publish("live-" + i);
        }

        // Wait for all 10 messages
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> received.size() >= 10);

        // The first 5 must be historical, the next 5 must be live
        String[] all = received.toArray(new String[0]);
        assertThat(all[0]).isEqualTo("hist-1");
        assertThat(all[1]).isEqualTo("hist-2");
        assertThat(all[2]).isEqualTo("hist-3");
        assertThat(all[3]).isEqualTo("hist-4");
        assertThat(all[4]).isEqualTo("hist-5");
        assertThat(all[5]).isEqualTo("live-1");
        assertThat(all[6]).isEqualTo("live-2");
        assertThat(all[7]).isEqualTo("live-3");
        assertThat(all[8]).isEqualTo("live-4");
        assertThat(all[9]).isEqualTo("live-5");
    }

    // ── Test 4: latestOffset reflects published message count ─────────────────

    @Test
    void latestOffsetReflectsPublishedMessageCount() {
        BayouSerializer<String> ser = new JavaSerializer<>();
        BayouTopic<String> topic = system.topic("replay-offset-count", ser);

        long initialOffset = topic.latestOffset(); // -1 for empty log

        // Publish 5 messages (seqnums: initialOffset+1 .. initialOffset+5)
        for (int i = 1; i <= 5; i++) {
            topic.publish("msg-" + i);
        }

        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> topic.latestOffset() >= initialOffset + 5);

        assertThat(topic.latestOffset()).isGreaterThanOrEqualTo(initialOffset + 5);

        long midOffset = topic.latestOffset();

        // Publish 5 more
        for (int i = 6; i <= 10; i++) {
            topic.publish("msg-" + i);
        }

        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> topic.latestOffset() >= midOffset + 5);

        assertThat(topic.latestOffset()).isGreaterThanOrEqualTo(midOffset + 5);
    }

    // ── Test 5: subscribeFrom does not interfere with live subscribers ────────

    @Test
    void subscribeFromDoesNotInterfereWithLiveSubscribers() {
        BayouSerializer<String> ser = new JavaSerializer<>();
        BayouTopic<String> topic = system.topic("replay-coexist", ser);

        AtomicInteger liveCount = new AtomicInteger(0);
        AtomicInteger replayCount = new AtomicInteger(0);

        Ref<String> liveRef = system.spawn("replay-coexist-live", (msg, ctx) -> liveCount.incrementAndGet());
        topic.subscribe(liveRef);

        // Publish 5 messages — liveRef receives all 5 (seqnums 0-4)
        for (int i = 1; i <= 5; i++) {
            topic.publish("msg-" + i);
        }

        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> liveCount.get() >= 5);

        // Wait until all 5 are in the log before replay subscriber connects
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> topic.latestOffset() >= 4);

        Ref<String> replayRef = system.spawn("replay-coexist-replay", (msg, ctx) -> replayCount.incrementAndGet());
        topic.subscribeFromBeginning(replayRef);

        // Wait for replayRef to receive the 5 replayed messages
        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> replayCount.get() >= 5);

        // Publish 5 more — both liveRef and replayRef should receive them
        for (int i = 6; i <= 10; i++) {
            topic.publish("msg-" + i);
        }

        // liveRef: 10 total (5 original live + 5 new live)
        // replayRef: 10 total (5 replayed + 5 new live)
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(liveCount.get()).isEqualTo(10);
                   assertThat(replayCount.get()).isEqualTo(10);
               });
    }
}
