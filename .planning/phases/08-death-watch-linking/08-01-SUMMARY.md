# Phase 8, Plan 1: Signal Infrastructure & Death Watch — SUMMARY

## Status: Complete

## Commits

- `2000f40` — feat(08-01): signal infrastructure — Terminated, WatchHandle, signalListeners, Envelope signals
- `0a0b863` — test(08-01): DeathWatchTest — watch on crash, graceful stop, unwatch, ctx.watch

## What Was Built

**New files:**
- `Signal.java` — public sealed interface (`permits Terminated, LinkedActorDied`)
- `Terminated.java` — public record: `Terminated(String actorId) implements Signal`
- `LinkedActorDied.java` — public record stub (fully used in Plan 2): `LinkedActorDied(String actorId, Throwable cause) implements Signal`
- `WatchHandle.java` — public interface: `cancel()`
- `WatchHandleImpl.java` — package-private: holds target runner + listener consumer; `cancel()` removes from `signalListeners`
- `DeathWatchTest.java` — 4 test cases

**Modified files:**
- `Envelope.java` — 3-field record `(M payload, Signal signal, CompletableFuture<Object> replyFuture)`; new `signal(Signal)` factory; `isSignal()` method
- `AbstractActorRunner.java` — added `signalListeners` (CopyOnWriteArrayList); `signal(Signal)` delivery method; loop dispatches signals via `processSignalEnvelope()`; non-abstract `handleSignal()` no-op; `finally` block fires `Terminated` to all listeners after `stopFuture.complete(null)`
- `BayouSystem.java` — added `runners` map (ConcurrentHashMap); `registerRunner()`, `lookupRunner()`, `unregisterActor()` clears runners too; `watch(target, watcher)` and `unwatch(handle)` public API; all `spawn*()` methods populate runners map
- `BayouContext.java` — added `watch(Ref<?> target)` and `unwatch(WatchHandle handle)`
- `BayouContextImpl.java` — implemented `watch()` and `unwatch()` using `system.lookupRunner()` and `runner::signal`
- `actor/Actor.java` — added `default void onSignal(Signal, BayouContext<M>)`
- `actor/StatefulActor.java` — added `default S onSignal(S, Signal, BayouContext<M>)`
- `actor/EventSourcedActor.java` — added `default List<E> onSignal(S, Signal, BayouContext<M>)`
- `StatelessActorRunner.java` — overrides `handleSignal()` → calls `actor.onSignal()`
- `StatefulActorRunner.java` — overrides `handleSignal()` → calls `actor.onSignal()`, reverts state on error
- `EventSourcedActorRunner.java` — overrides `handleSignal()` → calls `actor.onSignal()`, persists emitted events

## Key Design Decisions

- Signals share the actor's mailbox (FIFO preserved between signals and normal messages)
- `BayouSystem.runners` map added alongside `actors` — enables package-private runner lookup without casting
- `Terminated` fires unconditionally in `finally` for BOTH crash and graceful stop paths
- `handleSignal()` is non-abstract in `AbstractActorRunner` (no-op default) — `SupervisorRunner` inherits it unchanged
- Signal listeners cleared after actor dies to prevent memory leaks

## Deviations from Plan

- Test `watch_receivesTerminated_onCrash`: had to use a `preStart`-throw pattern instead of a lambda crasher. `StatelessActorRunner.processEnvelope` wraps all `handle()` exceptions in try-catch (calling `onError()`), so handler exceptions never reach `loop()`'s `catch(Exception e)`. Only exceptions from `initialize()`/`preStart()` propagate as `terminalCause`. The production code is correct; the test approach was adjusted to match actual semantics.

## Test Results

63/63 tests passing (59 pre-existing + 4 new DeathWatchTest):
- `watch_receivesTerminated_onCrash` ✓
- `watch_receivesTerminated_onGracefulStop` ✓
- `unwatch_preventsTerminatedDelivery` ✓
- `watch_fromWithinActor_usingCtxWatch` ✓
