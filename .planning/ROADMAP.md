# Roadmap — Bayou Supervision

Add Erlang/Akka-style supervisor trees to Bayou. Six phases taking the system from silent actor death to a full "let it crash" supervision hierarchy.

## Phases

### ~~Phase 1: Crash Signal Infrastructure~~ ✓ Complete
**Goal:** Runners signal their supervisor when they crash rather than dying silently.

- Add nullable `supervisorRef` field to `AbstractActorRunner`
- Modify `loop()` to catch fatal unhandled exceptions and fire a `ChildCrash` signal to the parent
- Define `ChildCrash` internal record (actorId, cause, runner reference)
- Ensure existing runners without a parent continue to behave exactly as before (no-op path)

**Done when:** A crashing actor's virtual thread death is observable by a parent reference rather than only visible in the log.

---

### ~~Phase 2: Supervision Strategy Model~~ ✓ Complete
**Goal:** Public API types that represent supervision decisions — no behavior yet, just the vocabulary.

- `SupervisionStrategy` interface with `RestartDecision decide(String childId, Throwable cause)`
- `RestartDecision` enum: `RESTART`, `STOP`, `ESCALATE`
- `RestartWindow` value type: max restart count + duration (e.g. 5 restarts in 60s)
- `OneForOneStrategy` built-in implementation
- `AllForOneStrategy` built-in implementation
- All types public; no dependencies outside stdlib

**Done when:** A user can write `new OneForOneStrategy(new RestartWindow(5, Duration.ofSeconds(60)))` and get a strategy object.

---

### ~~Phase 3: Supervisor Actor~~ ✓ Complete
**Goal:** A concrete supervisor type that holds children and reacts to crashes.

- `SupervisorActor` interface (user-facing): declares children + chooses strategy
- `SupervisorRunner` (package-private): owns child runners, receives `ChildCrash` signals, dispatches to strategy
- `BayouSystem.spawnSupervisor(id, supervisorActor)` factory method — spawns supervisor and its declared children
- `SupervisorRef` (or reuse `ActorRef<Void>`) as the handle returned to the caller
- Children are registered in `BayouSystem.actors` as normal; supervisor is their parent

**Done when:** `system.spawnSupervisor(...)` returns a ref; the supervisor's virtual thread starts alongside its children's threads.

---

### ~~Phase 4: Restart Mechanics~~ ✓ Complete
**Goal:** Supervisors can actually restart children after receiving a crash signal.

- `restart(runner)` utility: calls `cleanup()`, re-runs `initialize()`, starts a new virtual thread
- Stateless restart: fresh start (no state to restore)
- Stateful restart: re-reads latest snapshot from Gumbo log
- EventSourced restart: replays full event log from Gumbo
- One-for-one: restart only the crashed child
- All-for-one: stop all siblings first, then restart all children in declaration order

**Done when:** A supervised stateful actor that crashes comes back online with its last snapshot restored, verified by test.

---

### ~~Phase 5: Death Spiral Guard~~ ✓ Complete
**Goal:** Supervisors stop retrying after too many crashes and escalate up the tree.

- Per-child restart timestamp ring buffer (bounded by max-restarts count)
- After each restart: check if restart count within window >= max → if yes, trigger escalation
- Escalation: supervisor treats itself as crashed, fires its own `ChildCrash` signal to *its* parent
- Top-level supervisor (no parent): log critical error, stop the supervisor and all children gracefully
- `RestartWindow(max, duration)` drives the check; a window of `(Integer.MAX_VALUE, Duration.ZERO)` means unlimited

**Done when:** A child that crashes 6 times in 10s (with window set to 5/10s) causes the supervisor to escalate rather than restart again.

---

### ~~Phase 6: Testing & Polish~~ ✓ Complete
**Goal:** Full test coverage, documentation, and cleanup.

- `SupervisionTest`: one-for-one strategy — verify only crashed child restarts
- `SupervisionTest`: all-for-one strategy — verify all siblings restart
- `SupervisionTest`: death spiral — verify escalation when window exceeded
- `SupervisionTest`: nested supervisors — verify crash propagates correctly up a two-level tree
- `SupervisionTest`: backward compatibility — existing spawn*() tests still pass
- `SupervisionTest`: restart state semantics — stateful/event-sourced actors resume correctly
- Javadoc on all new public types
- README section on supervision

**Done when:** All tests green; `mvn verify` passes; existing tests unmodified and passing.

---

## Phase Order Rationale

Phases are sequentially dependent:
1. Crash signals must exist before anything can react to them
2. Strategy model must be defined before a supervisor can use it
3. Supervisor actor type builds on both signals and strategies
4. Restart mechanics require a working supervisor to trigger them
5. Death spiral guard requires working restart mechanics to count restarts
6. Tests validate the complete, integrated system

## Out of Scope (this roadmap)

- Remote supervision
- Persistent supervisor tree in Gumbo
- Dynamic child add/remove at runtime
- Death watch (non-supervisor lifecycle monitoring)

---

# Milestone 2: Erlang/Elixir Feature Parity

Add core Erlang/Elixir ecosystem primitives to Bayou. Five phases delivering timer messages, death watch/linking, back-pressure, PubSub, and a GenStateMachine behavior — each with tests woven in.

## Phases

### Phase 7: Timer Messages
**Goal:** Schedule messages to self after a delay or on a recurring interval — the `Process.send_after` equivalent.

- `BayouContext.scheduleOnce(Duration delay, M message)` returns a `TimerRef`
- `BayouContext.schedulePeriodic(Duration interval, M message)` returns a `TimerRef`
- `TimerRef.cancel()` — cancel a pending or recurring timer
- Timers deliver as normal mailbox messages — no special actor changes needed
- Tests: timer fires after delay, cancel prevents delivery, periodic fires N times, timer stops when actor stops

**Done when:** An actor schedules a message to itself after 100ms, receives it, and a cancelled timer never delivers.

---

### Phase 8: Death Watch & Linking
**Goal:** Any actor can monitor another for death; linked actors propagate crashes bidirectionally.

- `system.watch(target, watcher)` — watcher receives `Terminated(actorId)` when target dies (crash or stop)
- `system.unwatch(target, watcher)` — cancel a death watch
- `system.link(a, b)` — bidirectional: if either dies, the other receives an exit signal and crashes (unless trapping exits)
- `system.unlink(a, b)` — cancel a link
- `BayouContext.trapExits(true)` — convert incoming exit signals to `ExitSignal` messages instead of crashing
- Builds on the crash signal infrastructure from Milestone 1

**Done when:** A watching actor receives `Terminated` when the watched actor crashes; linked actors propagate deaths bidirectionally; an actor with `trapExits(true)` survives a linked partner's death.

---

### ~~Phase 9: Back-pressure~~ ✓ Complete (1 plan)
**Goal:** Bounded mailboxes with configurable overflow strategies protect against runaway producers.

- `MailboxConfig` value type: `MailboxConfig.bounded(int capacity)` and `MailboxConfig.unbounded()` (default)
- `BayouSystem.spawn*(id, actor, MailboxConfig)` — optional mailbox configuration overload
- Overflow strategies: `DROP_OLDEST`, `DROP_NEWEST`, `REJECT` (tell() throws `MailboxFullException`)
- Overflow metrics hook: pluggable `OverflowListener` for observability
- Tests: bounded mailbox fills, each strategy behaves correctly, unbounded actors unaffected

**Done when:** An actor with `MailboxConfig.bounded(10)` receiving 20 rapid messages applies the configured overflow strategy correctly; existing unbounded actors are unaffected.

---

### ~~Phase 10: PubSub / Process Groups~~ ✓ Complete (1 plan)
**Goal:** Named topic-based publish/subscribe — actors join groups; any publisher broadcasts to all members.

- `system.pubsub()` returns a `BayouPubSub` registry (one per `BayouSystem`)
- `pubsub.subscribe(String topic, ActorRef<M> subscriber)` — actor joins topic
- `pubsub.unsubscribe(String topic, ActorRef<?> subscriber)` — actor leaves topic
- `pubsub.publish(String topic, M message)` — delivers to all live subscribers on that topic
- Dead actor references silently skipped; thread-safe for concurrent subscribe/publish
- Tests: multi-subscriber broadcast, unsubscribe stops delivery, dead-actor cleanup, topic isolation

**Done when:** Three actors subscribed to a topic all receive a published message; an unsubscribed actor does not; a stopped actor's slot is silently skipped.

---

### ~~Phase 11: GenStateMachine / FSM~~ ✓ Complete (1 plan)
**Goal:** Finite state machine behavior — actors declare states and transitions; the framework manages state and fires callbacks.

- `StateMachineActor<S extends Enum<S>, M>` interface — generic over a state enum and message type
- `transition(S currentState, M message, BayouContext ctx)` returns `Optional<S>` — empty = stay in current state
- `onEnter(S state, BayouContext ctx)` and `onExit(S state, BayouContext ctx)` lifecycle callbacks
- `BayouContext.currentState()` — readable from handlers and callbacks
- `BayouSystem.spawnStateMachine(id, actor, S initialState)` factory
- Tests: valid transition, invalid/ignored transition, enter/exit callbacks, full lifecycle, stop in any state

**Done when:** A traffic-light FSM correctly cycles RED→GREEN→YELLOW→RED with `onEnter`/`onExit` callbacks firing in declaration order.

---

## Out of Scope (Milestone 2)

- Remote/distributed actors
- Hot code reloading
- TestKit / test probes (deterministic actor testing — deferred to Milestone 3)
- Persistent PubSub (topic subscriptions surviving restart)

---

# Milestone 3: Persistent PubSub & Developer Experience

Replace the in-memory PubSub with a durable, log-backed topic system built on gumbo. Topics survive restarts; late subscribers can catch up on history; durable subscriptions resume from their last position automatically. Alongside this, add a TestKit for deterministic actor testing and a comprehensive documentation overhaul that makes Bayou approachable to new developers.

## Phases

### Phase 12: Persistent Topic Core
**Goal:** Replace in-memory `BayouPubSub` with a `BayouTopic<M>` actor backed by gumbo. Publishing appends to the log *and* delivers to live subscribers simultaneously. Topic history survives process restarts.

- `system.topic(String name, BayouSerializer<M> serializer)` — returns a `BayouTopic<M>` (idempotent; same topic name always returns the same instance)
- `topic.publish(M message)` — appends to `bayou.topic:<name>` log view and delivers to all live subscribers
- `topic.subscribe(Ref<M> subscriber)` — live delivery only (from "now"); existing in-memory `BayouPubSub` remains as-is for lightweight use cases
- `topic.unsubscribe(Ref<M> subscriber)` — remove a live subscriber
- `BayouTopic<M>` is a `StatelessActorRunner` internally — owns its `LogView`, handles publish/subscribe messages through its own mailbox
- Tests: publish survives restart (replay count matches), multiple live subscribers, unsubscribe, topic isolation

**Done when:** An actor publishes 10 messages to a topic, the process simulates a restart by recreating `BayouSystem`, and a new subscriber receives all 10 messages replayed from the log.

---

### Phase 13: Durable Subscriptions
**Goal:** Named subscribers track their read position in the topic log. When a durable subscriber restarts, it automatically catches up from the last processed offset — no messages are lost or redelivered.

- `topic.subscribe(String subscriptionId, Ref<M> subscriber)` — registers a durable subscription; position stored in `bayou.topic.pos:<name>:<subscriptionId>`
- On each delivery, the subscription advances its stored offset atomically in gumbo
- `topic.unsubscribeDurable(String subscriptionId)` — cancel and forget the position
- Acknowledgement model: at-least-once — offset advances after successful `tell()`; actor crashes do not advance offset
- Tests: durable subscriber misses messages during downtime, resumes from correct offset; non-durable subscriber starts fresh; two subscriptions on same topic progress independently

**Done when:** Subscriber A (durable) and subscriber B (non-durable) are both subscribed. 5 messages published. B unsubscribed. 5 more published. B re-subscribes. A has all 10; B only sees the final 5.

---

### Phase 14: Message Replay
**Goal:** Subscribers can request historical messages — replay from the beginning of the topic or from a specific sequence offset. Enables "new consumer catches up" and audit-log patterns.

- `topic.subscribeFrom(long offset, Ref<M> subscriber)` — deliver from sequence number `offset` onwards (0 = from beginning)
- `topic.subscribeFromBeginning(Ref<M> subscriber)` — convenience alias for `subscribeFrom(0, ref)`
- `topic.latestOffset()` — returns the current end-of-log sequence number
- Replay is delivered in-order before any new live messages; no gaps
- Tests: subscribe from beginning receives all historical messages, subscribe from offset N receives only messages from N onwards, replay then live messages arrive in order

**Done when:** A topic with 100 messages has a new subscriber call `subscribeFromBeginning` and receives all 100 in order, followed by a new live publish that arrives as message 101.

---

### Phase 15: TestKit
**Goal:** Deterministic testing utilities for actor systems. `TestProbe<M>` is a controllable actor that records received messages and exposes synchronous assertion methods — no `CountDownLatch` or Awaitility needed for basic actor tests.

- `TestProbe<M> probe = TestKit.probe(system, "probe-id")` — spawns a probe actor
- `probe.expectMessage(M expected, Duration timeout)` — blocks until message arrives or times out
- `probe.expectMessage(Class<M> type, Duration timeout)` — untyped variant
- `probe.expectNoMessage(Duration within)` — asserts nothing arrives in the window
- `probe.expectTerminated(Ref<?> ref, Duration timeout)` — assert a watched actor has stopped
- `probe.ref()` — the `Ref<M>` for wiring into the system (subscribe to topics, link, watch)
- Thread-safe: `expectMessage` blocks the test thread; actor thread enqueues to an internal `BlockingQueue`
- Tests: TestKit tests itself — probe receives tell, probe receives pubsub message, expectNoMessage passes and fails correctly, expectTerminated

**Done when:** A test using only `TestProbe` (no Awaitility) can spawn an actor, tell it a message, and assert the reply arrives within 1 second — one line per assertion.

---

### Phase 16: Developer Experience & Docs
**Goal:** Comprehensive documentation that makes Bayou genuinely approachable. A new developer should be able to go from zero to a working actor system in under 10 minutes, and find answers to common patterns without reading source code.

- `docs/` directory with dedicated guides:
  - `getting-started.md` — from zero to first actor in 5 minutes
  - `actor-flavours.md` — when to use stateless vs stateful vs event-sourced vs FSM
  - `patterns.md` — request-reply, fan-out, work queues, circuit breakers, pipelines
  - `testing.md` — how to test actors with TestKit and without
  - `persistent-pubsub.md` — live vs durable vs replay subscriptions explained
  - `supervision.md` — expanded supervision guide with decision tree
- `CHANGELOG.md` — version history starting at 0.1.0
- Better exception messages — actor ID, current state, and actionable hint in every exception
- README overhaul — shorter, links to guides, focuses on "why Bayou" rather than API reference

**Done when:** An external developer (unfamiliar with the codebase) can follow `getting-started.md` and have a working supervised, event-sourced actor publishing to a persistent topic, all within the quickstart guide.

---

## Out of Scope (Milestone 3)

- Remote/distributed actors (cross-JVM messaging)
- Hot code reloading
- Schema evolution for persistent topic messages
- Topic compaction / log truncation
