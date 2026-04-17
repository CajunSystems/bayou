# Changelog

All notable changes to this project will be documented in this file.

## [0.2.0] — 2026-04-17

### Added

#### Milestone 2: Erlang/Elixir Feature Parity
- Timer messages — `ctx.scheduleOnce(Duration, M)` / `ctx.schedulePeriodic(Duration, M)` / `TimerRef.cancel()`
- Death watch — `system.watch(target, watcher)` / `ctx.watch(target)` → `Terminated` signal
- Actor linking — `system.link(a, b)` / `ctx.link(ref)` → `LinkedActorDied` exit signal
- Exit trapping — `ctx.trapExits(true)` converts exit signals to deliverable signals
- Back-pressure — `MailboxConfig.bounded(int)` with `DROP_NEWEST`, `DROP_OLDEST`, `REJECT` strategies
- `OverflowListener` — observability hook for mailbox overflow events
- PubSub — `system.pubsub()` returns `BayouPubSub`; `ctx.self()` for in-actor subscription
- `StateMachineActor<S, M>` — FSM behavior with `onEnter`/`onExit` callbacks

#### Milestone 3: Persistent PubSub & Developer Experience
- `BayouTopic<M>` — durable log-backed topic via `system.topic(name, serializer)`
- Durable subscriptions — `topic.subscribe(subscriptionId, ref)` tracks position; `topic.unsubscribeDurable(id)` forgets it
- Message replay — `topic.subscribeFrom(offset, ref)` / `topic.subscribeFromBeginning(ref)` / `topic.latestOffset()`
- `TestKit` / `TestProbe<M>` — synchronous actor testing; `expectMessage`, `expectNoMessage`, `expectTerminated`
- gumbo 0.2.0 — `readAfter`, `getLatestSeqnum`, `setValue/getValue`, push subscriptions
- Six documentation guides: `getting-started`, `actor-flavours`, `testing`, `persistent-pubsub`, `supervision`, `patterns`

### Changed
- `BayouContext<M>` is now generic — type-safe timer and self-reference APIs
- `Ref<M>` replaces `ActorRef<M>` in all public APIs
- Exception messages now include bad values and actionable hints (`MailboxFullException`, `RestartWindow`, `BayouSystem` duplicate ID and snapshotInterval)
- README overhauled from 534-line reference manual to ~90-line landing page; full reference moved to guides

[0.2.0]: https://github.com/CajunSystems/bayou/releases/tag/0.2.0

---

## [0.1.0] — 2026-04-17

### Added

#### Milestone 1: Supervision
- `SupervisorActor` — declare child actors and a supervision strategy
- `OneForOneStrategy` / `AllForOneStrategy` — restart policies with `RestartWindow`
- `RestartDecision` — `RESTART`, `RESTART_ALL`, `STOP`, `ESCALATE`
- Death spiral guard — supervisor escalates after exceeding restart window
- Nested supervisors — multi-level supervision trees
- Dynamic children — `SupervisorRef.spawnChild(ChildSpec)` at runtime

[0.1.0]: https://github.com/CajunSystems/bayou/releases/tag/0.1.0
