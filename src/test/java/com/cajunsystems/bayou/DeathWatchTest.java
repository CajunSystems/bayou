package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;
import org.junit.jupiter.api.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DeathWatchTest {

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
    void watch_receivesTerminated_onCrash() {
        var receivedSignal = new AtomicReference<Signal>();

        // Actor A — the watcher; records signals via onSignal
        Ref<String> refA = system.spawn("watcher", new Actor<>() {
            @Override public void handle(String msg, BayouContext<String> ctx) {}
            @Override public void onSignal(Signal signal, BayouContext<String> ctx) {
                receivedSignal.set(signal);
            }
        });

        // Actor B — crashes in preStart after a latch is opened (latch opened after watch is set up)
        var crashGate = new java.util.concurrent.CountDownLatch(1);
        Ref<String> refB = system.spawn("crasher", new Actor<>() {
            @Override public void preStart(BayouContext<String> ctx) {
                try { crashGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new RuntimeException("deliberate crash");
            }
            @Override public void handle(String msg, BayouContext<String> ctx) {}
        });

        system.watch(refB, refA);
        crashGate.countDown();  // open gate — crasher will throw from preStart, terminating

        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(receivedSignal.get()).isInstanceOf(Terminated.class);
                   assertThat(((Terminated) receivedSignal.get()).actorId()).isEqualTo("crasher");
               });
    }

    @Test
    void watch_receivesTerminated_onGracefulStop() throws Exception {
        var receivedSignal = new AtomicReference<Signal>();

        Ref<String> refA = system.spawn("watcher", new Actor<>() {
            @Override public void handle(String msg, BayouContext<String> ctx) {}
            @Override public void onSignal(Signal signal, BayouContext<String> ctx) {
                receivedSignal.set(signal);
            }
        });

        Ref<String> refB = system.spawn("stoppable", (msg, ctx) -> {});
        system.watch(refB, refA);

        refB.stop().get(2, TimeUnit.SECONDS);

        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(receivedSignal.get()).isInstanceOf(Terminated.class);
                   assertThat(((Terminated) receivedSignal.get()).actorId()).isEqualTo("stoppable");
               });
    }

    @Test
    void unwatch_preventsTerminatedDelivery() throws Exception {
        var signalCount = new AtomicInteger(0);

        Ref<String> refA = system.spawn("watcher", new Actor<>() {
            @Override public void handle(String msg, BayouContext<String> ctx) {}
            @Override public void onSignal(Signal signal, BayouContext<String> ctx) {
                if (signal instanceof Terminated) signalCount.incrementAndGet();
            }
        });

        Ref<String> refB = system.spawn("target", (msg, ctx) -> {});
        WatchHandle handle = system.watch(refB, refA);

        system.unwatch(handle);  // cancel before target dies
        refB.stop().get(2, TimeUnit.SECONDS);

        // Wait past delivery window; count must stay 0
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(signalCount.get()).isEqualTo(0);
    }

    @Test
    void watch_fromWithinActor_usingCtxWatch() {
        var receivedSignal = new AtomicReference<Signal>();

        // Actor B is spawned first so we have its ref for the lambda capture
        Ref<String> refB = system.spawn("target", (msg, ctx) -> {});

        // Actor A — in preStart, watches B using ctx.watch()
        Ref<String> refA = system.spawn("self-watcher", new Actor<>() {
            @Override
            public void preStart(BayouContext<String> ctx) {
                ctx.watch(refB);  // watch from within actor via context
            }
            @Override public void handle(String msg, BayouContext<String> ctx) {}
            @Override public void onSignal(Signal signal, BayouContext<String> ctx) {
                receivedSignal.set(signal);
            }
        });

        // Give preStart time to run (actor starts async)
        await().atMost(1, TimeUnit.SECONDS).until(refA::isAlive);

        refB.stop();

        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(receivedSignal.get()).isInstanceOf(Terminated.class);
                   assertThat(((Terminated) receivedSignal.get()).actorId()).isEqualTo("target");
               });
    }
}
