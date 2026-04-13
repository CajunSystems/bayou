# Conventions

## Naming

| Element | Convention | Examples |
|---|---|---|
| Classes | PascalCase | `BayouSystem`, `StatefulActorRunner` |
| Interfaces | PascalCase, no `I` prefix | `ActorRef`, `BayouContext`, `Actor` |
| Impl classes | `*Impl` suffix | `ActorRefImpl`, `BayouContextImpl` |
| Abstract classes | `Abstract*` prefix | `AbstractActorRunner` |
| Methods | camelCase | `spawnStateful()`, `processEnvelope()` |
| Boolean methods | `is*` prefix | `isAlive()`, `isAsk()` |
| Factory methods | descriptive verb | `freshSystem()`, `toActorRef()` |
| Generic params | `<M>` message, `<S>` state, `<E>` event, `<R>` reply |
| Test classes | `*Test` suffix | `StatelessActorTest` |
| Log tags | `bayou.<type>:<actorId>` | `bayou.events:counter` |

## Code Style

- **Indentation:** 4 spaces
- **Braces:** opening brace on same line
- **Visibility:** prefer package-private over public for internal types
- **Final classes:** all runner/impl classes are `final` to prevent subclassing
- **Section comments** for organization:
  ```java
  // ── Spawn: stateless ────────────────────────────────────────────────────
  ```

## Key Patterns

### @FunctionalInterface for Actors
```java
@FunctionalInterface
public interface Actor<M> {
    void handle(M message, BayouContext context);
    default void preStart(BayouContext context) {}
    default void postStop(BayouContext context) {}
    default void onError(M message, Throwable error, BayouContext context) { ... }
}
// Enables: system.spawn("id", (msg, ctx) -> ...)
```

### Records for Immutable Data
```java
record Envelope<M>(M payload, CompletableFuture<Object> replyFuture) {
    static <M> Envelope<M> tell(M msg) { ... }
    static <M> Envelope<M> ask(M msg) { ... }
}
```

### Sealed Interfaces for ADTs (in tests, expected in user code)
```java
sealed interface TallyCmd {
    record Count(String word) implements TallyCmd {}
    record Get(String word)   implements TallyCmd {}
    record GetAll()           implements TallyCmd {}
}
```

### Template Method for Runner Specialization
```java
abstract class AbstractActorRunner<M> {
    protected abstract void initialize();
    protected abstract void processEnvelope(Envelope<M> envelope);
    protected abstract void cleanup();
}
```

### Package-private Accessor (avoid getters)
```java
// private field, package-private accessor — no public getter
private final SharedLog sharedLog;
SharedLog sharedLog() { return sharedLog; }
```

### Defensive Copies for Immutable State
```java
Tally add(String word) {
    var next = new HashMap<>(counts);   // copy, never mutate
    next.merge(word, 1, Integer::sum);
    return new Tally(next);
}
```

## Error Handling

- **Unchecked exceptions** as default; constructors throw `IllegalArgumentException`
- **Checked exceptions** wrapped: `throw new RuntimeException("context msg", e)`
- **Two-level error recovery:** `handle()` → throws → `onError()` → throws → log + continue
- **State rollback** on reduce errors in `StatefulActorRunner` (explicit `previousState` capture)
- **Never swallows silently** at the actor-facing level; always logs at minimum

## Documentation Style

- **Class-level Javadoc** with `<pre>{@code ...}</pre>` usage examples on all public types
- **Method-level Javadoc** on all public methods (params, return, exceptions)
- **Section banners** within larger classes for visual grouping
- **Inline comments** for non-obvious logic (e.g., "prevent stale reply() calls")

## Thread Safety Conventions

- Actors process messages sequentially — handlers are single-threaded per actor
- Cross-actor state sharing via `CopyOnWriteArrayList`, `AtomicInteger`, etc. in tests
- `ConcurrentHashMap` for the actor registry in `BayouSystem`
- `AtomicBoolean running` for lifecycle state in runners
