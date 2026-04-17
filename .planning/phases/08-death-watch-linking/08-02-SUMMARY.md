# Phase 8, Plan 2: Linking & trapExits — SUMMARY

## Status: Complete

## Commits

- `b9ee6b8` — feat(08-02): actor linking and trapExits — bidirectional crash propagation
- `5c361dc` — test(08-02): LinkingTest — crash propagation, trapExits, unlink, graceful stop

## What Was Built

**Modified files:**
- `AbstractActorRunner.java` — added `linkedRunners` (ConcurrentHashMap key set) and `volatile boolean trapExits`; `processSignalEnvelope` now throws on non-trapped `LinkedActorDied`; `finally` block fires `LinkedActorDied` to all linked runners after Terminated
- `BayouSystem.java` — added `link(a, b)` and `unlink(a, b)` public API
- `BayouContext.java` — added `link(Ref<?> other)`, `unlink(Ref<?> other)`, `trapExits(boolean flag)`
- `BayouContextImpl.java` — implemented all three via `system.link(runner.toRef(), other)` and `runner.trapExits = flag`

**New files:**
- `LinkingTest.java` — 5 test cases

## Key Design Decisions

- `linkedRunners` stores `AbstractActorRunner<?>` references directly — avoids string lookups at death time; `ConcurrentHashMap.newKeySet()` for thread-safe add/remove
- `processSignalEnvelope` throws `RuntimeException("Linked actor '...' died", cause)` on non-trapped `LinkedActorDied` — propagates to `catch(Exception e)` in `loop()`, setting `terminalCause` and triggering the full crash chain (signals, crashListener)
- Graceful stop propagates via link: `terminalCause=null` fires `LinkedActorDied(actorId, null)` to linked runners; receiving runner still throws (null cause wraps cleanly in RuntimeException)
- `trapExits=true` routes `LinkedActorDied` to `handleSignal()` → `actor.onSignal()` instead of throwing; actor survives

## Deviations from Plan

- `Actor.preStart()` does not declare `throws Exception` in the interface. The `spawnCrashOnLatch` test helper used a `preStart` that called `latch.await()` — which throws `InterruptedException`. Fix: wrapped in try/catch inside `preStart`, rethrowing as `RuntimeException`. Semantically identical; no interface change needed.

## Test Results

68/68 tests passing (63 pre-existing + 5 new LinkingTest):
- `link_bothStop_whenOneDies` ✓
- `link_propagatesOnGracefulStop` ✓
- `trapExits_convertsExitToSignal` ✓
- `unlink_preventsCrashPropagation` ✓
- `link_fromWithinActor_usingCtx` ✓
