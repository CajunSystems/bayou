# Timers

Bayou timers let an actor schedule a message to itself — either once after a delay, or
repeatedly on a fixed interval. The message lands in the actor's own mailbox and is
processed like any other message, so no locking or thread-safety concerns arise.

---

## 1. One-shot timer

`ctx.scheduleOnce(delay, message)` fires once after `delay` and returns a `TimerRef`.
Cancel it before it fires with `TimerRef.cancel()`.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.time.Duration;

public class OneShotTimerExample {

    sealed interface Msg permits Msg.Start, Msg.Timeout {
        record Start() implements Msg {}
        record Timeout() implements Msg {}
    }

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            system.spawn("session", (Actor<Msg>) (msg, ctx) -> {
                switch (msg) {
                    case Msg.Start s -> {
                        ctx.logger().info("Session started — expires in 5 s");
                        ctx.scheduleOnce(Duration.ofSeconds(5), new Msg.Timeout());
                    }
                    case Msg.Timeout t -> ctx.logger().info("Session expired");
                }
            });

            system.<Msg>lookup("session").orElseThrow().tell(new Msg.Start());
            Thread.sleep(6_000);
            system.shutdown();
        }
    }
}
```

---

## 2. Periodic timer

`ctx.schedulePeriodic(interval, message)` delivers the message every `interval` until
cancelled. The first firing occurs after one full interval — there is no immediate delivery.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.time.Duration;

public class PeriodicTimerExample {

    sealed interface Msg permits Msg.Start, Msg.Stop, Msg.Tick {
        record Start() implements Msg {}
        record Stop()  implements Msg {}
        record Tick()  implements Msg {}
    }

    static class HeartbeatActor implements Actor<Msg> {
        private TimerRef ticker;

        @Override
        public void handle(Msg msg, BayouContext<Msg> ctx) {
            switch (msg) {
                case Msg.Start s -> {
                    ctx.logger().info("Heartbeat started");
                    ticker = ctx.schedulePeriodic(Duration.ofSeconds(1), new Msg.Tick());
                }
                case Msg.Tick t  -> ctx.logger().info("♥ tick");
                case Msg.Stop s  -> {
                    if (ticker != null) ticker.cancel();
                    ctx.logger().info("Heartbeat stopped");
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            Ref<Msg> hb = system.spawn("heartbeat", new HeartbeatActor());
            hb.tell(new Msg.Start());
            Thread.sleep(3_500);
            hb.tell(new Msg.Stop());
            Thread.sleep(500);
            system.shutdown();
        }
    }
}
```

---

## 3. Cancelling a timer

Both `scheduleOnce` and `schedulePeriodic` return a `TimerRef`. Call `cancel()` at any
time — it is idempotent and safe to call after the timer has already fired.

```java
TimerRef timer = ctx.scheduleOnce(Duration.ofSeconds(30), new Msg.Timeout());

// Later, if the operation completes before the timeout:
timer.cancel();

// Check whether it already fired or was cancelled:
if (timer.isCancelled()) { ... }
```

For periodic timers, `cancel()` stops all future firings. Cancellation takes effect
before the next scheduled delivery; an already-queued tick may still be processed.

---

## 4. Replacing a timer

A common pattern is to reset a timer when activity occurs — for example, a sliding
session expiry. Store the `TimerRef` as actor state and cancel before rescheduling:

```java
static class SlidingTimeoutActor implements Actor<Msg> {
    private TimerRef timeout;

    @Override
    public void handle(Msg msg, BayouContext<Msg> ctx) {
        switch (msg) {
            case Msg.Activity a -> {
                if (timeout != null) timeout.cancel();
                timeout = ctx.scheduleOnce(Duration.ofMinutes(10), new Msg.Timeout());
                ctx.logger().info("Activity — timeout reset");
            }
            case Msg.Timeout t -> ctx.logger().info("Idle timeout — closing session");
            default -> {}
        }
    }
}
```

---

## 5. Timer lifecycle

- Timers are owned by the actor that created them.
- When an actor stops (gracefully or via crash), all of its pending timers are
  automatically cancelled — no cleanup required.
- Timers use a single shared `ScheduledExecutorService` inside `BayouSystem`; they are
  not heavy resources.

---

## 6. Choosing a timer type

| Need | Use |
|---|---|
| Run something once after a delay | `scheduleOnce` |
| Run something repeatedly | `schedulePeriodic` |
| Reset the deadline on each activity | `scheduleOnce`, cancel + reschedule on each event |
| Retry with backoff | `scheduleOnce` with increasing `Duration` |
| Rate-limit outbound calls | `schedulePeriodic` as a token-bucket tick |
