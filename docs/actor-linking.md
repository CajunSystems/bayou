# Actor Linking

A link is a bidirectional death pact between two actors. If either actor dies — whether
from a crash or a graceful stop — the other receives a `LinkedActorDied` signal. By
default, that signal causes the receiving actor to crash too, propagating the exit.

This is Erlang's `Process.link` semantic: tightly coupled actors live and die together.
For one-directional observation without propagation, use
[death watch](death-watch.md) instead.

---

## 1. Linking two actors

Call `ctx.link(ref)` from within either actor. The link is bidirectional — you only need
to call it once.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

public class ActorLinkingExample {

    sealed interface CoordMsg permits CoordMsg.Start, CoordMsg.Work {
        record Start() implements CoordMsg {}
        record Work(String payload) implements CoordMsg {}
    }

    static class CoordinatorActor implements Actor<CoordMsg> {

        @Override
        public void preStart(BayouContext<CoordMsg> ctx) {
            Ref<String> backend = ctx.system().spawn("backend",
                    (msg, bCtx) -> bCtx.logger().info("Backend handling: {}", msg));
            ctx.link(backend);
            ctx.logger().info("Coordinator linked to backend");
        }

        @Override
        public void handle(CoordMsg msg, BayouContext<CoordMsg> ctx) {
            if (msg instanceof CoordMsg.Work w) {
                ctx.system().<String>lookup("backend")
                        .ifPresent(b -> b.tell(w.payload()));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            system.spawn("coordinator", new CoordinatorActor());
            Thread.sleep(100);

            // Stopping backend sends LinkedActorDied to coordinator,
            // which (without trapExits) causes coordinator to crash too.
            system.<String>lookup("backend").ifPresent(Ref::stop);
            Thread.sleep(100);
            system.shutdown();
        }
    }
}
```

---

## 2. The `LinkedActorDied` signal

When a linked actor dies, `onSignal` is called with `LinkedActorDied(actorId, cause)`.

- `actorId` — the actor that died
- `cause` — the `Throwable` that caused the death, or `null` for a graceful stop

By default, receiving `LinkedActorDied` **crashes the receiving actor** — the virtual
thread throws the cause (or a generic `RuntimeException` for graceful stops). This is the
correct default for tightly-coupled actors: if your backend is gone, your coordinator
should not silently continue in a broken state.

---

## 3. Trapping exits

Call `ctx.trapExits(true)` to convert `LinkedActorDied` from a crash into a delivered
signal. With exit trapping enabled, `onSignal` is called and the actor keeps running.
Use this when the actor can meaningfully handle the partner's death rather than crashing.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.Actor;

public class TrapExitsExample {

    sealed interface Msg permits Msg.Connect, Msg.Request {
        record Connect()         implements Msg {}
        record Request(String q) implements Msg {}
    }

    static class ResilientCoordinator implements Actor<Msg> {

        @Override
        public void preStart(BayouContext<Msg> ctx) {
            ctx.trapExits(true);  // receive LinkedActorDied as a signal, don't crash
            connectToBackend(ctx);
        }

        private void connectToBackend(BayouContext<Msg> ctx) {
            Ref<String> backend = ctx.system().spawn("backend-" + System.nanoTime(),
                    (msg, bCtx) -> bCtx.logger().info("Backend: {}", msg));
            ctx.link(backend);
            ctx.logger().info("Connected to {}", backend.actorId());
        }

        @Override
        public void handle(Msg msg, BayouContext<Msg> ctx) {
            if (msg instanceof Msg.Request r) {
                ctx.system().<String>lookup("backend").ifPresent(b -> b.tell(r.q()));
            }
        }

        @Override
        public void onSignal(Signal signal, BayouContext<Msg> ctx) {
            if (signal instanceof LinkedActorDied d) {
                ctx.logger().warn("Backend {} died ({}), reconnecting", d.actorId(), d.cause());
                connectToBackend(ctx);
            }
        }
    }
}
```

---

## 4. Removing a link

Call `ctx.unlink(ref)` to remove the bidirectional link. After unlinking, neither actor
will receive `LinkedActorDied` if the other dies. This is useful when you hand off
responsibility for a resource and no longer want to be coupled to its lifecycle.

```java
ctx.link(workerRef);
// ... coordinate with worker ...
ctx.unlink(workerRef);  // release the coupling; worker can now stop independently
```

---

## 5. Linking vs death watch vs supervision

| | Actor Linking | Death Watch | Supervision |
|---|---|---|---|
| Direction | Bidirectional | One-directional | Parent → child |
| Default effect on receiver | Crashes (unless trapping exits) | Receives `Terminated`; keeps running | Restarts, stops, or escalates |
| Signal | `LinkedActorDied(actorId, cause)` | `Terminated(actorId)` | — |
| Set up via | `ctx.link(ref)` | `ctx.watch(ref)` | `system.spawnSupervisor(...)` |
| Use when | Two actors must live and die together | Observing a dependency's lifecycle | You own and manage the child's lifecycle |

**Rule of thumb:** use linking when the two actors are so tightly coupled that one
cannot function without the other. Use `trapExits(true)` only when the surviving actor
can reconnect or degrade gracefully.
