package com.cajunsystems.bayou;

import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TimerTest {

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
    void scheduleOnce_firesAfterDelay() {
        var counter = new AtomicInteger(0);
        Ref<String> timerActor = system.spawn("self-timer", (msg, ctx) -> {
            if ("start".equals(msg)) {
                ctx.scheduleOnce(Duration.ofMillis(100), "tick");
            } else if ("tick".equals(msg)) {
                counter.incrementAndGet();
            }
        });

        timerActor.tell("start");
        await().atMost(1, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(counter.get()).isEqualTo(1));
        // Verify it fires exactly once (wait another 300ms)
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void scheduleOnce_cancelPreventsDelivery() throws Exception {
        var counter = new AtomicInteger(0);
        var timerRef = new TimerRef[1];

        Ref<String> actor = system.spawn("cancel-test", (msg, ctx) -> {
            if ("start".equals(msg)) {
                timerRef[0] = ctx.scheduleOnce(Duration.ofMillis(200), "tick");
            } else if ("tick".equals(msg)) {
                counter.incrementAndGet();
            }
        });

        actor.tell("start");
        // Give actor time to process "start" and register the timer
        await().atMost(500, TimeUnit.MILLISECONDS)
               .until(() -> timerRef[0] != null);
        timerRef[0].cancel();

        // Wait past the timer's delay; count must remain 0
        try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(counter.get()).isEqualTo(0);
    }

    @Test
    void schedulePeriodic_firesRepeatedly() throws Exception {
        var counter = new AtomicInteger(0);
        var timerRef = new TimerRef[1];

        Ref<String> actor = system.spawn("periodic-test", (msg, ctx) -> {
            if ("start".equals(msg)) {
                timerRef[0] = ctx.schedulePeriodic(Duration.ofMillis(50), "tick");
            } else if ("tick".equals(msg)) {
                counter.incrementAndGet();
            }
        });

        actor.tell("start");
        await().atMost(1, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(counter.get()).isGreaterThanOrEqualTo(3));

        // Cancel and verify it stops
        await().atMost(500, TimeUnit.MILLISECONDS).until(() -> timerRef[0] != null);
        timerRef[0].cancel();
        int countAfterCancel = counter.get();
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(counter.get()).isLessThanOrEqualTo(countAfterCancel + 1); // at most 1 in-flight
    }

    @Test
    void actorStop_cancelsActiveTimers() throws Exception {
        var counter = new AtomicInteger(0);

        Ref<String> actor = system.spawn("stop-test", (msg, ctx) -> {
            if ("start".equals(msg)) {
                ctx.scheduleOnce(Duration.ofMillis(200), "tick");
            } else if ("tick".equals(msg)) {
                counter.incrementAndGet();
            }
        });

        actor.tell("start");
        // Give actor time to process "start" and register the timer
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        actor.stop().get(2, TimeUnit.SECONDS);

        // Wait past the timer delay; timer should have been cancelled
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(counter.get()).isEqualTo(0);
    }

    @Test
    void multipleTimers_independentCancellation() throws Exception {
        var countA = new AtomicInteger(0);
        var countB = new AtomicInteger(0);
        var timerB = new TimerRef[1];

        Ref<String> actor = system.spawn("multi-timer", (msg, ctx) -> {
            switch (msg) {
                case "start" -> {
                    ctx.scheduleOnce(Duration.ofMillis(100), "tickA");
                    timerB[0] = ctx.scheduleOnce(Duration.ofMillis(300), "tickB");
                }
                case "tickA" -> countA.incrementAndGet();
                case "tickB" -> countB.incrementAndGet();
            }
        });

        actor.tell("start");
        await().atMost(500, TimeUnit.MILLISECONDS).until(() -> timerB[0] != null);
        timerB[0].cancel();

        // Wait for A to fire (100ms), then past B's delay (300ms)
        try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(countA.get()).isEqualTo(1); // A fired
        assertThat(countB.get()).isEqualTo(0); // B was cancelled
    }
}
