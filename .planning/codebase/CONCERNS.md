# Concerns

## Critical

### C1 — Java Serialization Security Vulnerability
**File:** `JavaSerializer.java`  
**Issue:** `ObjectInputStream.readObject()` is a known deserialization gadget attack vector. Any untrusted data deserialized through this path can execute arbitrary code.  
**Risk:** RCE if event/snapshot data originates from untrusted sources.  
**Fix:** Mark `@Deprecated(since="0.1.0", forRemoval=true)`, add runtime warning log on instantiation. Update README to require production users supply Kryo/Protobuf/Jackson impl.

### C2 — Unbounded Mailbox (DoS / OOM)
**File:** `AbstractActorRunner.java:29`  
**Issue:** `new LinkedBlockingQueue<>()` — no capacity limit. A slow handler + fast sender = unbounded memory growth.  
**Risk:** OutOfMemoryError; trivial denial-of-service.  
**Fix:** Add configurable capacity to `BayouSystem` spawn methods (default e.g. 10,000). Check `offer()` return value and handle overflow.

### C3 — Blocking `.join()` in Virtual Thread Handlers
**Files:** `EventSourcedActorRunner.java:40,57`, `StatefulActorRunner.java:44,87`  
**Issue:** Event appends and snapshot reads block the virtual thread carrier via `.join()`. Acceptable for low concurrency but undocumented — users don't know not to add more blocking in handlers.  
**Risk:** Scalability ceiling; latency spikes at high concurrency.  
**Fix:** Document in `Actor.handle()` Javadoc that handlers must be non-blocking or quick. Consider async log append if Gumbo supports it.

---

## High

### H1 — Silent Snapshot Failures (Data Loss)
**File:** `StatefulActorRunner.java` — `takeSnapshot()`  
**Issue:** If `snapshotView.append()` fails, the exception is logged and swallowed. `messagesSinceSnapshot` is **not reset**, causing count drift. On restart, the actor recovers from a stale snapshot.  
**Risk:** Silent state loss, hard-to-reproduce bugs in production.  
**Fix:** Propagate to `onError()` or stop the actor (fail-fast). Reset counter in the catch block.

### H2 — Event Persistence Atomicity Gap
**File:** `EventSourcedActorRunner.java`  
**Issue:** The sequence is: `handle()` → call `apply()` (mutates memory) → append to log. If append fails, in-memory state is inconsistent with the log. Ask-pattern callers also hang (no reply in error path).  
**Risk:** State inconsistency after I/O failures; hanging futures.  
**Fix:** Serialize and append event **before** calling `apply()`. Only update memory after successful write.

### H3 — No Supervision / Restart Policy
**Issue:** If an actor's virtual thread crashes (unhandled exception escaping `loop()`), the actor dies silently. No parent-child hierarchy, no restart-on-failure, no escalation.  
**Risk:** Silent service degradation requiring manual intervention.  
**Fix:** Document as a known gap. Add optional `RestartPolicy` to a future milestone.

### H4 — TOCTOU Race in spawn()
**File:** `BayouSystem.java:60-66` (all three spawn methods)  
**Issue:** `checkNotDuplicate(actorId)` and `actors.put(actorId, ref)` are not atomic. Two concurrent spawns with the same ID can both pass the check.  
**Risk:** Duplicate actors registered; lost references.  
**Fix:** Use `actors.compute(actorId, ...)` or `putIfAbsent` to make registration atomic.

---

## Medium

### M1 — No Shutdown Timeout
**File:** `BayouSystem.java:153`  
**Issue:** `CompletableFuture.allOf(stops).join()` blocks indefinitely if any actor's `cleanup()` hangs.  
**Fix:** `.orTimeout(30, SECONDS)` — make timeout configurable in constructor.

### M2 — ask() on Dead Actor Hangs Forever
**File:** `AbstractActorRunner.java`  
**Issue:** If `isAlive()` is false, `tell()` silently drops the message. But `ask()` already created the future before the check — that future never completes.  
**Fix:** In `ask()`, check `!running.get()` and complete the future exceptionally immediately.

### M3 — No Actor ID Validation
**File:** `BayouSystem.java` — all spawn methods  
**Issue:** Actor IDs are used as Gumbo log tag keys. No validation for empty string, special characters, or length.  
**Risk:** Tag injection; misleading error messages.  
**Fix:** Validate: non-empty, alphanumeric + `[-_.]`, max 256 chars.

### M4 — Unchecked Casts Throughout
**Files:** `BayouContextImpl.java:49`, `BayouSystem.java:176`, `AbstractActorRunner.java:91`, `JavaSerializer.java:30`  
**Issue:** Multiple `@SuppressWarnings("unchecked")` casts due to type erasure. Wrong type at runtime = `ClassCastException` in async code (hard to debug).  
**Fix:** Add runtime `instanceof` checks before casts in hot paths. Document type contract clearly.

### M5 — 100ms Poll Timeout Adds Latency
**File:** `AbstractActorRunner.java:53`  
**Issue:** `mailbox.poll(100, MILLISECONDS)` — idle actors wake every 100ms unnecessarily.  
**Fix:** Use `mailbox.take()` (blocks until message) during the running phase; switch to `poll(timeout)` only when draining during shutdown.

### M6 — `StatelessActor` Deprecation Incomplete
**File:** `actor/StatelessActor.java`  
**Issue:** `@Deprecated(forRemoval=true)` but no `since` attribute; no migration guide in README.  
**Fix:** Add `since="0.1.0"` and a README deprecation note.

---

## Low

### L1 — `BayouTestSupport.freshSystem()` Leaks Log on Exception
**File:** `BayouTestSupport.java`  
**Issue:** If `new BayouSystem(log)` throws, `SharedLogService` is never closed.  
**Fix:** Wrap in try-catch, close log on exception.

### L2 — Thread Safety of Handlers Not Documented
**Files:** `actor/Actor.java`, `actor/StatefulActor.java`, `actor/EventSourcedActor.java`  
**Issue:** It's not obvious from the interface that handlers are called sequentially by a single actor thread.  
**Fix:** Add to Javadoc: "This method is always called by the actor's dedicated thread. All invocations for the same actor are serialized."

### L3 — No Metrics or Observability
**Issue:** No queue depth monitoring, no message latency counters, no snapshot size logging, no replay duration logging.  
**Fix:** Add optional `ActorMetrics` interface or structured log lines for key events (replay start/end, snapshot taken, queue full).

### L4 — offer() Return Value Ignored
**File:** `AbstractActorRunner.java:84,90`  
**Issue:** `mailbox.offer()` returns `false` if full; return value is ignored. When/if queue becomes bounded, messages will silently drop.  
**Fix:** Assert or handle the return value now so behavior is explicit when capacity is added.
