# Stack

## Language & Runtime

| | |
|---|---|
| Language | Java 21 |
| JVM target | 21 (virtual threads required) |
| Build tool | Apache Maven 4.0.0 |
| Encoding | UTF-8 |

## Dependencies

### Compile

| Dependency | Version | Purpose |
|---|---|---|
| `com.github.CajunSystems:gumbo` | `main-SNAPSHOT` | Append-only shared log (persistence backbone) |
| `org.slf4j:slf4j-api` | 2.0.12 | Logging facade |
| `ch.qos.logback:logback-classic` | 1.5.3 | SLF4J implementation |

### Test

| Dependency | Version | Purpose |
|---|---|---|
| `org.junit.jupiter:junit-jupiter` | 5.10.2 | Test framework (JUnit 5) |
| `org.assertj:assertj-core` | 3.25.3 | Fluent assertions |
| `org.awaitility:awaitility` | 4.2.1 | Async test polling |

### Repositories

- Maven Central (standard deps)
- JitPack (`https://jitpack.io`) — hosts Gumbo snapshot builds

## Maven Plugins

| Plugin | Version | Config |
|---|---|---|
| `maven-compiler-plugin` | 3.13.0 | `release=21` |
| `maven-surefire-plugin` | 3.2.5 | Default test execution |

## Key Java 21 Features Used

- **Virtual threads** — each actor backed by `Thread.ofVirtual()`
- **Records** — `Envelope`, domain model types in tests
- **Sealed interfaces** — message command/event ADTs in tests
- **Pattern matching** — implicit via sealed types
- **CompletableFuture** — ask-pattern async replies

## CI/CD

- **GitHub Actions** — `.github/workflows/ci.yml`
- Triggers: push to `main`, `claude/*`, `feature/*`; PRs to `main`
- Runner: `ubuntu-latest`, Temurin JDK 21, Maven cache
- Command: `mvn -B verify`
