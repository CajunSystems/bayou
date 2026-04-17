# Phase 10, Plan 1: PubSub / Process Groups — SUMMARY

## Status: Complete

## Commits

- `f41d05e` — feat(10-01): BayouPubSub — topic subscribe/publish/unsubscribe; ctx.self()
- `71e699e` — test(10-01): PubSubTest — broadcast, unsubscribe, dead-actor cleanup, topic isolation, ctx.self

## What Was Built

**New files:**
- `BayouPubSub.java` — topic-based pub/sub registry; ConcurrentHashMap<String, CopyOnWriteArrayList<Ref<?>>>; lazy dead-actor cleanup on publish; @SuppressWarnings("unchecked") for type-erased Ref cast
- `PubSubTest.java` — 5 test cases

**Modified files:**
- `BayouSystem.java` — `private final BayouPubSub pubsub` field + `pubsub()` accessor
- `BayouContext.java` — `Ref<M> self()` added to interface
- `BayouContextImpl.java` — `self()` returns `runner.toRef()`

## Key Design Decisions

- `BayouPubSub` is a plain class (not an actor) — no mailbox overhead for routing
- Subscriptions: `ConcurrentHashMap<String, CopyOnWriteArrayList<Ref<?>>>` — concurrent subscribe/unsubscribe safe during publish iteration
- `publish()` swallows all `tell()` exceptions — dead actors, overflowed mailboxes silently ignored
- Lazy cleanup: `removeIf(!isAlive())` only runs when a dead ref is detected during publish — avoids per-publish overhead
- `ctx.self()` returns `runner.toRef()` — already used in `link()`; zero new coupling

## Deviations from Plan

(none)

## Test Results

77/77 tests passing (72 pre-existing + 5 new PubSubTest):
- `multipleSubscribers_allReceivePublished` ✓
- `unsubscribe_stopsDelivery` ✓
- `deadActor_silentlySkippedOnPublish` ✓
- `topicIsolation_onlyCorrectTopicReceives` ✓
- `ctxSelf_allowsInActorSubscription` ✓
