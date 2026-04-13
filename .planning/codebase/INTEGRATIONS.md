# Integrations

## Gumbo SharedLog (Primary)

**Dependency:** `com.github.CajunSystems:gumbo:main-SNAPSHOT` via JitPack

Gumbo is the only external framework Bayou depends on. It provides an append-only, tagged log with pluggable persistence adapters.

### API Surface Used

```java
// Open
SharedLogConfig config = SharedLogConfig.builder()
    .persistenceAdapter(new FileBasedPersistenceAdapter(path))
    .build();
SharedLogService log = SharedLogService.open(config);

// Get scoped view
LogView view = log.getView(LogTag.of("bayou.events", actorId));

// Read
List<LogEntry> entries = view.readAll().join();
byte[] data = entry.dataUnsafe();

// Write
view.append(bytes).join();

// Close
log.close();
```

### Log Tag Namespaces

| Tag | Used By | Format |
|---|---|---|
| `bayou.events` | `EventSourcedActorRunner` | `bayou.events:<actorId>` |
| `bayou.snapshots` | `StatefulActorRunner` | `bayou.snapshots:<actorId>` |

### Persistence Adapters

| Adapter | Environment |
|---|---|
| `FileBasedPersistenceAdapter` | Production |
| `InMemoryPersistenceAdapter` | Tests |

## Logging (SLF4J + Logback)

Each actor gets its own logger named `"bayou.actor." + actorId`. No external log config files are present in the repo — Logback runs with defaults.

## Serialization

Bayou exposes a pluggable `BayouSerializer<T>` interface:

```java
public interface BayouSerializer<T> {
    byte[] serialize(T value) throws IOException;
    T deserialize(byte[] bytes) throws IOException;
}
```

**Built-in:** `JavaSerializer<T extends Serializable>` — uses `ObjectOutputStream` / `ObjectInputStream`. Intended for development only; no production serialization library is included.

**Expected production implementations:** Kryo, Protobuf, Jackson (user-supplied).

## CI/CD (GitHub Actions)

`.github/workflows/ci.yml`:
- Trigger: push to `main`, `claude/*`, `feature/*`; PRs to `main`
- Runner: `ubuntu-latest`
- Setup: `actions/setup-java@v4`, Temurin JDK 21, Maven cache
- Command: `mvn -B verify`

## What's NOT Present

| Category | Absent |
|---|---|
| Databases | No ORM, no JDBC, no NoSQL |
| Message queues | No Kafka, RabbitMQ, etc. |
| HTTP | No HTTP client or server |
| Config system | No Spring, Quarkus, Typesafe Config |
| Dependency injection | None |
| Service discovery | None |

Bayou is a self-contained library. All external state flows through Gumbo.
