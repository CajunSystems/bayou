package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class BackPressureTest {

    BayouSystem system;

    @BeforeEach
    void setUp() throws Exception {
        system = BayouTestSupport.freshSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    /**
     * REJECT strategy: filling the mailbox to capacity and then sending one more message
     * should throw MailboxFullException from tell().
     */
    @Test
    void bounded_reject_throwsMailboxFullException() throws Exception {
        // Latch to block the actor inside handle() so mailbox remains drainable
        CountDownLatch processingLatch = new CountDownLatch(1);

        Ref<String> actor = system.spawn("reject-actor",
                (Actor<String>) (msg, ctx) -> {
                    if ("block".equals(msg)) {
                        try {
                            processingLatch.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    }
                    ctx.reply(msg);
                },
                MailboxConfig.bounded(2, OverflowStrategy.REJECT));

        // Block the actor — ask will not complete until processingLatch is released.
        // Once the actor's loop polls the "block" envelope, mailbox is empty.
        CompletableFuture<String> blockFuture = actor.ask("block");

        // Wait until the actor is processing the block message (mailbox is empty)
        await().atMost(5, TimeUnit.SECONDS)
               .pollInterval(10, TimeUnit.MILLISECONDS)
               .until(() -> !blockFuture.isDone());

        // Small sleep to ensure the actor is blocked inside processingLatch.await()
        Thread.sleep(50);

        // Fill mailbox to capacity=2
        actor.tell("msg1");
        actor.tell("msg2");

        // Third tell should throw MailboxFullException
        assertThatThrownBy(() -> actor.tell("msg3"))
                .isInstanceOf(MailboxFullException.class)
                .satisfies(e -> {
                    MailboxFullException mfe = (MailboxFullException) e;
                    assertThat(mfe.actorId()).isEqualTo("reject-actor");
                    assertThat(mfe.capacity()).isEqualTo(2);
                });

        // Release the actor
        processingLatch.countDown();
    }

    /**
     * OverflowListener is called on each overflow event.
     */
    @Test
    void bounded_overflowListener_calledOnOverflow() throws Exception {
        AtomicInteger overflowCount = new AtomicInteger(0);
        CountDownLatch processingLatch = new CountDownLatch(1);

        OverflowListener listener = (id, cap, strategy) -> overflowCount.incrementAndGet();

        Ref<String> actor = system.spawn("listener-actor",
                (Actor<String>) (msg, ctx) -> {
                    if ("block".equals(msg)) {
                        try {
                            processingLatch.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    }
                    ctx.reply(msg);
                },
                MailboxConfig.bounded(2, OverflowStrategy.DROP_NEWEST, listener));

        // Block the actor
        CompletableFuture<String> blockFuture = actor.ask("block");

        // Wait until the actor is blocked
        await().atMost(5, TimeUnit.SECONDS)
               .pollInterval(10, TimeUnit.MILLISECONDS)
               .until(() -> !blockFuture.isDone());

        Thread.sleep(50);

        // Fill mailbox to capacity=2
        actor.tell("msg1");
        actor.tell("msg2");

        // Send 2 overflow messages
        actor.tell("overflow1");
        actor.tell("overflow2");

        // Overflow listener should have been called twice
        assertThat(overflowCount.get()).isEqualTo(2);

        // Release the actor
        processingLatch.countDown();
    }

    /**
     * DROP_OLDEST: when full, oldest message is removed to make room for the newest.
     */
    @Test
    void bounded_dropOldest_keepsNewest() throws Exception {
        CountDownLatch processingLatch = new CountDownLatch(1);
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        Ref<String> actor = system.spawn("drop-oldest-actor",
                new Actor<>() {
                    @Override
                    public void handle(String msg, BayouContext<String> ctx) {
                        if ("block".equals(msg)) {
                            try {
                                processingLatch.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                        } else {
                            received.add(msg);
                        }
                        ctx.reply(msg);
                    }
                },
                MailboxConfig.bounded(2, OverflowStrategy.DROP_OLDEST));

        // Block the actor
        CompletableFuture<String> blockFuture = actor.ask("block");

        // Wait until the actor is blocked
        await().atMost(5, TimeUnit.SECONDS)
               .pollInterval(10, TimeUnit.MILLISECONDS)
               .until(() -> !blockFuture.isDone());

        Thread.sleep(50);

        // Fill mailbox to capacity=2: mailbox=[msg1, msg2]
        actor.tell("msg1");
        actor.tell("msg2");

        // Send msg3 — drops msg1, mailbox=[msg2, msg3]
        actor.tell("msg3");

        // Send msg4 — drops msg2, mailbox=[msg3, msg4]
        actor.tell("msg4");

        // Release the actor
        processingLatch.countDown();

        // Actor should process msg3 and msg4 only (msg1 and msg2 were dropped)
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(received).containsExactly("msg3", "msg4"));
    }

    /**
     * Regression test: unbounded actors are unaffected and receive all messages.
     */
    @Test
    void unbounded_actorUnaffected() throws Exception {
        AtomicInteger count = new AtomicInteger(0);

        Ref<String> actor = system.spawn("unbounded-actor",
                (Actor<String>) (msg, ctx) -> count.incrementAndGet());

        for (int i = 0; i < 100; i++) {
            actor.tell("msg-" + i);
        }

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(count.get()).isEqualTo(100));
    }
}
