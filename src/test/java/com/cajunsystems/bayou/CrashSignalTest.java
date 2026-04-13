package com.cajunsystems.bayou;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CrashSignalTest {

    private BayouSystem system;

    @BeforeEach
    void setUp() throws Exception {
        system = BayouTestSupport.freshSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    @Test
    void crashListenerReceivesSignalOnAbnormalExit() throws Exception {
        var crashes = new CopyOnWriteArrayList<ChildCrash>();

        // A runner that throws during initialize() — simulates a fatal crash
        var runner = new AbstractActorRunner<String>("crashing-actor", system) {
            @Override protected void initialize() {
                throw new RuntimeException("boom");
            }
            @Override protected void processEnvelope(Envelope<String> envelope) {}
            @Override protected void cleanup() {}
        };
        runner.setCrashListener(crashes::add);
        runner.start();

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(crashes).hasSize(1));

        ChildCrash crash = crashes.get(0);
        assertThat(crash.actorId()).isEqualTo("crashing-actor");
        assertThat(crash.cause()).isInstanceOf(RuntimeException.class);
        assertThat(crash.cause().getMessage()).isEqualTo("boom");
        assertThat(crash.runner()).isSameAs(runner);
    }

    @Test
    void noSignalOnGracefulStop() throws Exception {
        var crashes = new CopyOnWriteArrayList<ChildCrash>();

        var runner = new AbstractActorRunner<String>("normal-actor", system) {
            @Override protected void initialize() {}
            @Override protected void processEnvelope(Envelope<String> envelope) {}
            @Override protected void cleanup() {}
        };
        runner.setCrashListener(crashes::add);
        runner.start();

        runner.stop().get(5, TimeUnit.SECONDS);

        // Brief pause to ensure any spurious signal would have arrived
        Thread.sleep(100);
        assertThat(crashes).isEmpty();
    }
}
