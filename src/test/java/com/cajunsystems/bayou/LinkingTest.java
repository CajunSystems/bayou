package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class LinkingTest {

    BayouSystem system;

    @BeforeEach
    void setUp() throws Exception {
        system = BayouTestSupport.freshSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    /** Helper: spawn an actor that crashes in preStart after a latch is released. */
    private Ref<String> spawnCrashOnLatch(String id, CountDownLatch latch) {
        return system.spawn(id, new Actor<>() {
            @Override
            public void preStart(BayouContext<String> ctx) {
                try {
                    latch.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("deliberate crash from " + id);
            }
            @Override public void handle(String msg, BayouContext<String> ctx) {}
        });
    }

    @Test
    void link_bothStop_whenOneDies() throws Exception {
        CountDownLatch crashLatch = new CountDownLatch(1);

        Ref<String> refA = spawnCrashOnLatch("actor-a", crashLatch);
        Ref<String> refB = system.spawn("actor-b", (msg, ctx) -> {});

        // Wait for both to be alive before linking
        await().atMost(1, TimeUnit.SECONDS).until(refA::isAlive);
        await().atMost(1, TimeUnit.SECONDS).until(refB::isAlive);

        system.link(refA, refB);
        crashLatch.countDown(); // trigger A's crash

        // Both A and B should stop: A crashes, B receives LinkedActorDied and (no trapExits) throws -> dies
        await().atMost(3, TimeUnit.SECONDS).until(() -> !refA.isAlive());
        await().atMost(3, TimeUnit.SECONDS).until(() -> !refB.isAlive());
    }

    @Test
    void link_propagatesOnGracefulStop() throws Exception {
        Ref<String> refA = system.spawn("actor-a", (msg, ctx) -> {});
        Ref<String> refB = system.spawn("actor-b", (msg, ctx) -> {});

        await().atMost(1, TimeUnit.SECONDS).until(refA::isAlive);
        await().atMost(1, TimeUnit.SECONDS).until(refB::isAlive);

        system.link(refA, refB);
        refA.stop().get(2, TimeUnit.SECONDS); // graceful stop A

        // B should also stop: receives LinkedActorDied(cause=null), throws RuntimeException -> dies
        await().atMost(3, TimeUnit.SECONDS).until(() -> !refB.isAlive());
    }

    @Test
    void trapExits_convertsExitToSignal() throws Exception {
        var receivedSignal = new AtomicReference<Signal>();
        CountDownLatch crashLatch = new CountDownLatch(1);

        // Actor A traps exits — sets trapExits in preStart
        Ref<String> refA = system.spawn("trapper", new Actor<>() {
            @Override
            public void preStart(BayouContext<String> ctx) {
                ctx.trapExits(true);
            }
            @Override public void handle(String msg, BayouContext<String> ctx) {}
            @Override
            public void onSignal(Signal signal, BayouContext<String> ctx) {
                receivedSignal.set(signal);
            }
        });

        Ref<String> refB = spawnCrashOnLatch("crasher", crashLatch);

        await().atMost(1, TimeUnit.SECONDS).until(refA::isAlive);
        await().atMost(1, TimeUnit.SECONDS).until(refB::isAlive);

        system.link(refA, refB);
        crashLatch.countDown(); // crash B

        // A should stay alive and receive LinkedActorDied as a signal
        await().atMost(3, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedSignal.get()).isInstanceOf(LinkedActorDied.class));

        assertThat(refA.isAlive()).isTrue(); // A survived
        assertThat(((LinkedActorDied) receivedSignal.get()).actorId()).isEqualTo("crasher");
    }

    @Test
    void unlink_preventsCrashPropagation() throws Exception {
        CountDownLatch crashLatch = new CountDownLatch(1);

        Ref<String> refA = spawnCrashOnLatch("actor-a", crashLatch);
        Ref<String> refB = system.spawn("actor-b", (msg, ctx) -> {});

        await().atMost(1, TimeUnit.SECONDS).until(refA::isAlive);
        await().atMost(1, TimeUnit.SECONDS).until(refB::isAlive);

        system.link(refA, refB);
        system.unlink(refA, refB); // remove link before crash
        crashLatch.countDown();

        // A crashes, but B should remain alive
        await().atMost(3, TimeUnit.SECONDS).until(() -> !refA.isAlive());
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(refB.isAlive()).isTrue();
    }

    @Test
    void link_fromWithinActor_usingCtx() throws Exception {
        CountDownLatch crashLatch = new CountDownLatch(1);

        Ref<String> refB = spawnCrashOnLatch("actor-b", crashLatch);
        await().atMost(1, TimeUnit.SECONDS).until(refB::isAlive);

        // Actor A links to B using ctx.link() from preStart
        Ref<String> refA = system.spawn("actor-a", new Actor<>() {
            @Override
            public void preStart(BayouContext<String> ctx) {
                ctx.link(refB);
            }
            @Override public void handle(String msg, BayouContext<String> ctx) {}
        });

        await().atMost(1, TimeUnit.SECONDS).until(refA::isAlive);

        crashLatch.countDown(); // crash B

        // A should also die due to the link
        await().atMost(3, TimeUnit.SECONDS).until(() -> !refA.isAlive());
    }
}
