# Testing Actors with TestKit

Bayou ships `TestKit` and `TestProbe<M>` for synchronous, deterministic actor testing.
No `Thread.sleep`, no `CountDownLatch`, no flaky timing — just assertions.

---

## 1. TestKit Setup

Create a fresh `BayouSystem` in `@BeforeEach` using `InMemoryPersistenceAdapter` so
nothing touches the filesystem, and shut it down in `@AfterEach`.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import org.junit.jupiter.api.*;

class MyActorTest {

    BayouSystem system;

    @BeforeEach
    void setUp() throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();
        SharedLogService log = SharedLogService.open(config);
        system = new BayouSystem(log);
    }

    @AfterEach
    void tearDown() {
        system.shutdown(); // stops all actors and closes the log
    }
}
```

`TestKit.probe(system, id)` registers a special actor that captures incoming messages:

```java
TestProbe<String> probe = TestKit.probe(system, "probe");
Ref<String> probeRef = probe.ref(); // use this to send messages to the probe
```

Use a **unique probe ID per test** (or per system) — duplicate IDs throw
`IllegalArgumentException`.

---

## 2. Basic Message Assertion

Tell the probe directly or wire it up as a recipient of another actor's output, then
call `expectMessage` to assert a specific message arrives within a timeout.

```java
@Test
void probeReceivesDirectMessage() {
    TestProbe<String> probe = TestKit.probe(system, "probe");

    probe.ref().tell("hello");

    probe.expectMessage("hello", Duration.ofSeconds(1));
}

@Test
void echoActorForwardsToProbe() {
    TestProbe<String> probe = TestKit.probe(system, "probe");
    Ref<String> echo = system.spawn("echo",
            (msg, ctx) -> probe.ref().tell(msg + "-echo"));

    echo.tell("ping");

    probe.expectMessage("ping-echo", Duration.ofSeconds(1));
}
```

`expectMessage` blocks the calling thread until the message arrives or the timeout
expires. On timeout it throws `AssertionError`.

---

## 3. Type-Based Assertion

When your actor sends a sealed interface message and you only care about the runtime
type, use the `Class<T>` overload. It returns the cast value for further assertions.

```java
sealed interface Response {
    record Ok(String body)  implements Response {}
    record Err(String code) implements Response {}
}

@Test
void expectMessageByType() {
    TestProbe<Object> probe = TestKit.probe(system, "probe");

    probe.ref().tell("typed-message");

    String msg = probe.expectMessage(String.class, Duration.ofSeconds(1));
    assertThat(msg).isEqualTo("typed-message");
}

@Test
void actorSendsOkResponse() {
    TestProbe<Response> probe = TestKit.probe(system, "probe");
    Ref<String> handler = system.spawn("handler",
            (msg, ctx) -> probe.ref().tell(new Response.Ok("done")));

    handler.tell("go");

    Response.Ok ok = probe.expectMessage(Response.Ok.class, Duration.ofSeconds(1));
    assertThat(ok.body()).isEqualTo("done");
}
```

---

## 4. Asserting Silence

`expectNoMessage(duration)` asserts that **no** message arrives within the given
duration. Use this for negative tests — checking that a filtered message is silently
dropped, or that a timer has not fired yet.

```java
@Test
void filteredMessageIsDropped() {
    TestProbe<String> probe = TestKit.probe(system, "probe");
    Ref<String> filtered = system.spawn("filtered", (msg, ctx) -> {
        if (!msg.startsWith("allowed:")) return; // drop
        probe.ref().tell(msg);
    });

    filtered.tell("blocked-message");

    // Assert nothing reached the probe
    probe.expectNoMessage(Duration.ofMillis(200));
}
```

`expectNoMessage` waits the full duration before returning — keep it short (100–300 ms)
to avoid slow tests.

---

## 5. Watching for Actor Death

`expectTerminated(ref, timeout)` watches the target actor for termination and asserts
that it stops within the timeout. This works for both graceful stops and crashes.

```java
@Test
void actorStopsCleanly() {
    TestProbe<String> probe = TestKit.probe(system, "probe");
    Ref<String> shortLived = system.spawn("short-lived", (msg, ctx) -> {});

    shortLived.stop(); // async — drains mailbox, then stops

    // expectTerminated registers a watch and blocks until stopped
    probe.expectTerminated(shortLived, Duration.ofSeconds(2));
}

@Test
void crashedActorIsDetected() {
    TestProbe<String> probe = TestKit.probe(system, "probe");
    Ref<String> crasher = system.spawn("crasher",
            (msg, ctx) -> { throw new RuntimeException("deliberate crash"); });

    crasher.tell("trigger");

    probe.expectTerminated(crasher, Duration.ofSeconds(2));
}
```

---

## 6. PubSub with TestKit

Subscribe the probe's `ref()` to a `BayouPubSub` topic or a `BayouTopic`, then publish
and assert delivery.

```java
@Test
void probeReceivesPubSubMessage() {
    TestProbe<String> probe = TestKit.probe(system, "probe");

    system.pubsub().subscribe("news", probe.ref());
    system.pubsub().publish("news", "breaking");

    probe.expectMessage("breaking", Duration.ofSeconds(1));
}

@Test
void probeReceivesPersistentTopicMessage() {
    TestProbe<String> probe = TestKit.probe(system, "probe");

    BayouTopic<String> topic = system.topic("events", new JavaSerializer<>());
    topic.subscribe(probe.ref());
    topic.publish("event-1");

    probe.expectMessage("event-1", Duration.ofSeconds(1));
}
```

---

## 7. Testing with Awaitility

TestKit is the right tool for most actor tests. Use Awaitility when you have external
state (a shared map, database, metrics counter) that is updated asynchronously by an
actor and you need to poll until a condition is met.

```java
import org.awaitility.Awaitility;
import java.util.concurrent.atomic.AtomicInteger;

@Test
void counterActorIncrementsSharedState() {
    AtomicInteger counter = new AtomicInteger(0);

    Ref<String> incrementer = system.spawn("incrementer",
            (msg, ctx) -> counter.incrementAndGet());

    incrementer.tell("inc");
    incrementer.tell("inc");
    incrementer.tell("inc");

    // Poll until counter reaches 3 (or 2 seconds elapse)
    Awaitility.await()
              .atMost(java.time.Duration.ofSeconds(2))
              .untilAtomic(counter, org.hamcrest.Matchers.equalTo(3));
}
```

**TestKit vs Awaitility:**

| Scenario | Use |
|---|---|
| Assert actor sent a message | `TestProbe.expectMessage` |
| Assert actor sent no message | `TestProbe.expectNoMessage` |
| Assert actor stopped | `TestProbe.expectTerminated` |
| Assert external shared state changed | `Awaitility.await().until(...)` |
| Assert a `CompletableFuture` resolves | `future.get(1, SECONDS)` + AssertJ |

---

## 8. Tips

**Use unique actor IDs per test.** Each `BayouSystem` maintains a registry; duplicate
IDs throw `IllegalArgumentException`. Either include the test method name in the ID or
create a fresh system per test (the `@BeforeEach` pattern above).

**Always shut down in `@AfterEach`.** `system.shutdown()` drains all mailboxes and
closes the gumbo log. Skipping it leaks virtual threads and log handles between tests.

**Use `InMemoryPersistenceAdapter` in tests.** It is fast, zero-configuration, and
completely isolated between test runs (a new instance is always empty).

**Keep timeouts short.** `Duration.ofSeconds(1)` is enough for in-process actor
delivery under normal conditions. Only use longer timeouts for `expectTerminated` where
the actor may be doing cleanup work.

**Avoid `Thread.sleep` in assertions.** If you find yourself sleeping to "let the actor
process," use `probe.expectMessage(...)` instead — it blocks exactly as long as needed
and fails fast on timeout.
