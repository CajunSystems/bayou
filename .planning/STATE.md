# Project State

## Current Phase

**Phase 16: Developer Experience & Docs** — in progress

## Phase Status

| Phase | Status | Notes |
|---|---|---|
| 1 — Crash Signal Infrastructure | complete | 01-01-SUMMARY.md |
| 2 — Supervision Strategy Model | complete | 02-01-SUMMARY.md |
| 3 — Supervisor Actor | complete | 03-01-SUMMARY.md |
| 4 — Restart Mechanics | complete | 04-01-SUMMARY.md |
| 5 — Death Spiral Guard | complete | 05-01-SUMMARY.md |
| 6 — Testing & Polish | complete | 06-01-SUMMARY.md, 06-02-SUMMARY.md, 06-03-SUMMARY.md |
| 7 — Timer Messages | complete | 07-01-SUMMARY.md |
| 8 — Death Watch & Linking | complete | 08-01-SUMMARY.md, 08-02-SUMMARY.md |
| 9 — Back-pressure | complete | 09-01-SUMMARY.md |
| 10 — PubSub / Process Groups | complete | 10-01-SUMMARY.md |
| 11 — GenStateMachine / FSM | complete | 11-01-SUMMARY.md |
| **Milestone 3** | | |
| 12 — Persistent Topic Core | complete | 12-01-PLAN.md, 12-01-SUMMARY.md |
| 13 — Durable Subscriptions | complete | 13-01-SUMMARY.md |
| 14 — Message Replay | complete | 14-01-SUMMARY.md |
| 15 — TestKit | complete | 15-01-SUMMARY.md |
| 16 — Developer Experience & Docs | in progress | 16-01-SUMMARY.md |

## Last Action

Phase 16, Plan 1 complete — 2026-04-17

## Accumulated Decisions

- `linkedRunners` (ConcurrentHashMap key set) per-actor; `volatile boolean trapExits`; `processSignalEnvelope` throws on non-trapped `LinkedActorDied`; graceful stop propagates via link (terminalCause=null wraps cleanly)
- `system.link(a,b)` / `system.unlink(a,b)` + `ctx.link()` / `ctx.unlink()` / `ctx.trapExits()`
- `Signal` sealed interface (`Terminated`, `LinkedActorDied`) — signals share actor mailbox via `Envelope.signal()` factory; FIFO preserved
- `signalListeners` (CopyOnWriteArrayList) in AbstractActorRunner; `Terminated` fires in `finally` for both crash and graceful stop
- `BayouSystem.runners` map added alongside `actors` — package-private runner lookup without casting; all `spawn*()` populate it
- `handleSignal()` non-abstract default (no-op) in AbstractActorRunner — SupervisorRunner inherits it; 3 actor runners override it
- Actor crashes in handler (`handle()` throws) do NOT set `terminalCause` — `StatelessActorRunner.processEnvelope` swallows all exceptions via try-catch; only `initialize()`/`preStart()` exceptions propagate as terminal

- `BayouContext<M>` is now generic — enables type-safe `scheduleOnce(Duration, M)` / `schedulePeriodic(Duration, M)` without casts; lambda actors unaffected
- Timer delivery via `runner.tell(message)` directly — bypasses `Ref` layer; context holds `AbstractActorRunner<M> runner` field set in constructor via `setRunner(this)`
- Active timers tracked in `AbstractActorRunner.activeTimers` (ConcurrentHashMap key set); cancelled in `finally` block before `cleanup()` on actor stop
- `ScheduledExecutorService` in `BayouSystem` uses single-threaded platform daemon thread — more reliable for timing than virtual threads
- One-shot timers self-remove from `activeTimers` after firing to prevent set growth

- `ChildCrash` carries the runner reference — used in Phase 4 restart: `crash.runner().restart()`
- `Consumer<ChildCrash>` used for listener (no custom interface needed)
- Crash signal fires after `cleanup()` and `stopFuture.complete()` — child fully stopped first
- `processEnvelope()` still swallows handler exceptions — "let it crash" from handler deferred
- `RestartDecision` has 4 values: RESTART, RESTART_ALL, STOP, ESCALATE
- `SupervisionStrategy` is `@FunctionalInterface` — supports lambda custom strategies
- `RestartWindow` stored in strategy constructor — signature stable for Phase 5
- `SupervisorRunner extends AbstractActorRunner<ChildCrash>` — mailbox typed to crash signals
- `SupervisorRef extends ActorRef<Void>` — supervisors not user-messageable; stored in BayouSystem.actors
- `ChildSpec` sealed interface with 3 impls — factory methods only; `StatefulChildSpec` is public with `.snapshotInterval(int)` builder method
- `SupervisorActor.children()` is a `default` returning `List.of()` — dynamic-only supervisors only implement `strategy()`
- `SupervisorRef.spawnChild(ChildSpec)` — dynamic child addition at runtime; thread-safe via `CopyOnWriteArrayList`
- `AbstractActorRunner.stopFuture` is `volatile` (not final) — allows `restart()` to replace it
- `running.set(false)` in crash catch block — fixes `isAlive()` after crash; enables natural RESTART_ALL sibling filter
- `restart()` sets `running=true`, fresh `stopFuture`, new virtual thread — mailbox preserved
- `SupervisorRunner.childRunners` is `CopyOnWriteArrayList` — thread-safe for `spawnChild` from any thread
- `EscalationException extends RuntimeException` — thrown from `escalate()`, caught by existing `catch (Exception e)` in `loop()`, no changes to loop needed
- `escalate()` on `AbstractActorRunner` — checks `crashListener == null` to detect top-level (log critical); always throws EscalationException
- `cleanup()` unregisters children from `BayouSystem.actors` + clears `childRunners` — enables clean restart by parent
- `BayouSystem.unregisterActor()` — `actors.remove(actorId)`; safe from cleanup() thread
- Strategy `restartHistory` is `HashMap` (not ConcurrentHashMap) — `decide()` is called only from the supervisor's own virtual thread
- `SupervisorChildSpec` sealed record — 4th ChildSpec variant; `ChildSpec.supervisor()` factory creates nested supervisor specs
- `startAndRegister()` checks `instanceof SupervisorRunner` to register `SupervisorRef` vs `ActorRef` — ensures `system.lookup("child-sup")` returns a castable `SupervisorRef`
- Nested supervisor crash propagation uses existing `crashListener` machinery — no new wiring needed; escalation from child supervisor fires `ChildCrash` to parent supervisor's mailbox

- `MailboxConfig` record stores `(int capacity, OverflowStrategy, OverflowListener)` — `Integer.MAX_VALUE` = unbounded; factory methods `unbounded()`, `bounded(int)`, `bounded(int, OverflowStrategy)`, `bounded(int, OverflowStrategy, OverflowListener)`
- `OverflowStrategy` enum: `DROP_NEWEST`, `DROP_OLDEST`, `REJECT` — enforcement in `tell()` only; `signal()` and ask-envelope bypass it
- `MailboxFullException` package-private constructor — thrown from `tell()` on REJECT overflow; fields `actorId` + `capacity`
- `ChildSpec` sealed interface gains `mailboxConfig()` — all 4 record impls add `withMailbox(MailboxConfig)` builder; factory defaults to `unbounded()`
- `BayouSystem.spawn*(id, actor, MailboxConfig)` overloads — existing no-MailboxConfig signatures delegate to `unbounded()` variant

- `BayouPubSub` plain class owned by `BayouSystem` — `ConcurrentHashMap<String, CopyOnWriteArrayList<Ref<?>>>` subscriptions; `publish()` skips dead actors, swallows tell() exceptions, lazy `removeIf` cleanup
- `ctx.self()` returns `Ref<M>` via `runner.toRef()` — enables type-safe in-actor PubSub subscription from `preStart`/`handle`
- `BayouSystem.pubsub()` — single `BayouPubSub` instance per system, initialized eagerly as field

- `StateMachineActor<S, M>` in `actor/` package — `transition(S, M, BayouContext<M>)` returns `Optional<S>`; `onEnter`/`onExit` callbacks; `onEnter` fires for initial state in `initialize()`
- `StateMachineActorRunner` tracks `currentState`; `onExit(old)` + `onEnter(new)` fire on real transitions; callback exceptions swallowed via `onError`; actor NOT crashed by callback errors
- `BayouSystem.spawnStateMachine(id, actor, initialState)` + `...MailboxConfig` overload — consistent with all other spawn patterns

- `BayouTopic<M>` public facade wrapping `TopicActor<M>` (package-private `Actor<TopicCommand<M>>`); `TopicCommand<M>` sealed interface with Publish/Subscribe/Unsubscribe records
- `LogView` at `LogTag.of("bayou.topic", name)` — initialized in `preStart`; `publish()` appends bytes then delivers to liveSubscribers (CopyOnWriteArrayList, lazy removeIf cleanup)
- `BayouSystem.topics` ConcurrentHashMap — `topic()` idempotent via `computeIfAbsent`; actor registered as `"bayou-topic-" + name`

- `TopicCommand` gains `SubscribeDurable<M>` and `UnsubscribeDurable<M>` sealed records
- `TopicActor.durableSubscribers` HashMap — position stored as KV `"sub:" + subscriptionId` on the topic's LogView; `readAfter(storedSeqnum)` replays on reconnect; `getLatestSeqnum()` records join point for new subscriptions; dead durable refs removed from map without advancing position
- `BayouTopic.subscribe(String, Ref<M>)` and `unsubscribeDurable(String)` — public durable subscription API

## Accumulated Decisions (continued)

- `LogView` creation moved to `BayouSystem.topic()` and passed to both `TopicActor` (constructor) and `BayouTopic` (field); `TopicActor.preStart` removed
- `TopicCommand.SubscribeFrom<M>(long offset, Ref<M>)` — `handleSubscribeFrom` calls `readAfter(offset)`, delivers replay in-order, then adds to `liveSubscribers`
- `BayouTopic.subscribeFrom(long, Ref<M>)`, `subscribeFromBeginning(Ref<M>)`, `latestOffset()` — public replay API; `latestOffset()` calls `logView.getLatestSeqnum()` synchronously
- `subscribeFromBeginning` uses `subscribeFrom(-1, subscriber)` because gumbo seqnums are 0-indexed; `readAfter(-1)` returns all entries (including seqnum 0), while `readAfter(0)` would skip the first entry

## Accumulated Decisions (Phase 15)

- `TestProbe<M>` in `com.cajunsystems.bayou` — `LinkedBlockingQueue<M> messages` + `LinkedBlockingQueue<Signal> signals`; backed by `ProbeActor<M>` (package-private `Actor<M>`)
- `TestKit.probe(system, id)` static factory; `expectMessage(M, Duration)`, `expectMessage(Class<T>, Duration)`, `expectNoMessage(Duration)`, `expectTerminated(Ref<?>, Duration)`
- `expectTerminated` calls `system.watch` then polls signals queue; `InterruptedException` wrapped as `RuntimeException`; handles race where actor stops before watch is registered via `isAlive()` check and `IllegalArgumentException` catch; also pre-checks for already-queued `Terminated` signals

## Active Plan

Phase 16, Plan 2 — `.planning/phases/16-developer-experience-docs/16-02-PLAN.md`

Phase 16 has 2 plans total:
- 16-01: Exception messages + CHANGELOG + getting-started + actor-flavours + testing docs
- 16-02: persistent-pubsub + supervision + patterns docs + README overhaul
