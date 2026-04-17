# Death Watch

Death watch lets any actor observe when another actor stops — whether it crashed or was
shut down gracefully. The watching actor receives a `Terminated` signal and can react:
clean up resources, notify others, or start a replacement.

Death watch is *observation only*. The watcher is not affected by the watched actor's
death. For bidirectional fate sharing (both actors die together), use
[actor linking](actor-linking.md) instead.

---

## 1. Registering a watch

Call `ctx.watch(ref)` inside `preStart` or `handle`. The return value is a `WatchHandle`
you can use to cancel the watch later.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

public class DeathWatchExample {

    sealed interface Msg permits Msg.StartWorker, Msg.DoWork {
        record StartWorker() implements Msg {}
        record DoWork(String payload) implements Msg {}
    }

    static class MonitorActor implements Actor<Msg> {

        @Override
        public void handle(Msg msg, BayouContext<Msg> ctx) {
            switch (msg) {
                case Msg.StartWorker s -> {
                    Ref<String> worker = ctx.system().spawn("worker",
                            (work, wCtx) -> wCtx.logger().info("Working on: {}", work));
                    ctx.watch(worker);
                    ctx.logger().info("Watching worker");
                }
                case Msg.DoWork d -> ctx.system().<String>lookup("worker")
                        .ifPresent(w -> w.tell(d.payload()));
            }
        }

        @Override
        public void onSignal(Signal signal, BayouContext<Msg> ctx) {
            switch (signal) {
                case Terminated t -> ctx.logger().info("Worker {} has stopped", t.actorId());
                default -> {}
            }
        }
    }

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            Ref<Msg> monitor = system.spawn("monitor", new MonitorActor());
            monitor.tell(new Msg.StartWorker());
            Thread.sleep(100);
            system.<String>lookup("worker").ifPresent(Ref::stop);
            Thread.sleep(100);
            system.shutdown();
        }
    }
}
```

`Terminated` carries the `actorId` of the stopped actor. The watcher receives it
regardless of whether the watched actor crashed or was gracefully stopped.

---

## 2. Handling the signal

Implement `onSignal` on your actor. It is called on the actor's own virtual thread —
sequential with `handle`, never concurrent.

```java
@Override
public void onSignal(Signal signal, BayouContext<Msg> ctx) {
    if (signal instanceof Terminated t) {
        ctx.logger().info("Watched actor {} stopped — cleaning up", t.actorId());
        // restart, notify, or clean up here
    }
}
```

`Signal` is a sealed interface with two permitted subtypes: `Terminated` and
`LinkedActorDied`. Always use a pattern match or `instanceof` check so you only react
to the signal type you care about.

---

## 3. Cancelling a watch

Call `handle.cancel()` on the `WatchHandle` returned by `ctx.watch(ref)`. After
cancellation, no further `Terminated` signals are delivered for that target. Calling
`cancel()` after the target has already died is safe and has no effect.

```java
WatchHandle handle = ctx.watch(otherRef);

// Later, when the watch is no longer needed:
handle.cancel();
```

---

## 4. Watching multiple actors

Each `ctx.watch(ref)` call returns an independent `WatchHandle`. You can watch as many
actors as you like. Use the `actorId` inside `Terminated` to distinguish which one died.

```java
@Override
public void preStart(BayouContext<Msg> ctx) {
    ctx.watch(ctx.system().<String>lookup("db-writer").orElseThrow());
    ctx.watch(ctx.system().<String>lookup("cache").orElseThrow());
}

@Override
public void onSignal(Signal signal, BayouContext<Msg> ctx) {
    if (signal instanceof Terminated t) {
        switch (t.actorId()) {
            case "db-writer" -> ctx.logger().warn("DB writer gone — switching to read-only");
            case "cache"     -> ctx.logger().warn("Cache gone — falling back to DB");
        }
    }
}
```

---

## 5. Death watch vs supervision

| | Death Watch | Supervision |
|---|---|---|
| Relationship | Any actor watching any other | Parent supervising declared children |
| Effect on watcher | Receives `Terminated` signal; watcher keeps running | Supervisor decides to restart, stop, or escalate |
| Effect on watched | None — the watched actor is already dead | Child may be restarted |
| Set up via | `ctx.watch(ref)` | `system.spawnSupervisor(...)` |
| Use when | You need to react to a dependency's death | You own the lifecycle of the child |

Use death watch for *cross-cutting* observation: a coordinator that needs to know when
any of several independent services goes down. Use supervision when you own the child
and are responsible for its lifecycle.
