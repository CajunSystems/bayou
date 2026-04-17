package com.cajunsystems.bayou;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestKitTest {

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
    void probeReceivesDirectTell() {
        TestProbe<String> probe = TestKit.probe(system, "probe");
        probe.ref().tell("hello");
        probe.expectMessage("hello", Duration.ofSeconds(1));
    }

    @Test
    void probeReceivesActorReply() {
        TestProbe<String> probe = TestKit.probe(system, "probe");
        Ref<String> echo = system.spawn("echo", (msg, ctx) -> probe.ref().tell(msg + "-echo"));
        echo.tell("ping");
        probe.expectMessage("ping-echo", Duration.ofSeconds(1));
    }

    @Test
    void expectMessageByType() {
        TestProbe<Object> probe = TestKit.probe(system, "probe");
        probe.ref().tell("typed-message");
        String msg = probe.expectMessage(String.class, Duration.ofSeconds(1));
        assertThat(msg).isEqualTo("typed-message");
    }

    @Test
    void expectNoMessagePassesWhenMailboxEmpty() {
        TestProbe<String> probe = TestKit.probe(system, "probe");
        probe.expectNoMessage(Duration.ofMillis(100));
    }

    @Test
    void expectNoMessageFailsWhenMessageArrives() throws InterruptedException {
        TestProbe<String> probe = TestKit.probe(system, "probe");
        probe.ref().tell("unexpected");
        Thread.sleep(50); // let message reach probe actor
        assertThatThrownBy(() -> probe.expectNoMessage(Duration.ofSeconds(1)))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("unexpected message");
    }

    @Test
    void expectTerminatedDetectsActorStop() {
        TestProbe<String> probe = TestKit.probe(system, "probe");
        Ref<String> dying = system.spawn("dying", (msg, ctx) -> {});
        dying.stop(); // async — expectTerminated sets up the watch and waits
        probe.expectTerminated(dying, Duration.ofSeconds(2));
    }

    @Test
    void probeReceivesPubSubMessage() {
        TestProbe<String> probe = TestKit.probe(system, "probe");
        system.pubsub().subscribe("news", probe.ref());
        system.pubsub().publish("news", "breaking");
        probe.expectMessage("breaking", Duration.ofSeconds(1));
    }
}
