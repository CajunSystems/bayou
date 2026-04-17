# Phase 9, Plan 1: Back-pressure — SUMMARY

## Status: Complete

## Commits

- `b04460d` — feat(09-01): mailbox config — MailboxConfig, OverflowStrategy, bounded mailbox enforcement
- `50c41ea` — feat(09-01): spawn API — MailboxConfig overloads, ChildSpec.withMailbox(), SupervisorRunner pass-through
- `28bb131` — test(09-01): BackPressureTest — reject, overflowListener, dropOldest, unbounded regression

## What Was Built

**New files:**
- `OverflowStrategy.java` — enum: DROP_NEWEST, DROP_OLDEST, REJECT
- `MailboxConfig.java` — public record with factory methods: unbounded(), bounded(int), bounded(int, OverflowStrategy), bounded(int, OverflowStrategy, OverflowListener)
- `MailboxFullException.java` — extends RuntimeException; fields actorId, capacity
- `OverflowListener.java` — @FunctionalInterface void onOverflow(String actorId, int capacity, OverflowStrategy)
- `BackPressureTest.java` — 4 test cases

**Modified files:**
- `AbstractActorRunner.java` — mailboxConfig/mailboxCapacity fields; constructor accepts MailboxConfig; tell() enforces overflow
- `StatelessActorRunner.java`, `StatefulActorRunner.java`, `EventSourcedActorRunner.java`, `SupervisorRunner.java` — constructor accepts MailboxConfig
- `BayouSystem.java` — new spawn overloads; existing spawn calls delegate to MailboxConfig overloads
- `ChildSpec.java` — mailboxConfig() in sealed interface; factory defaults to unbounded()
- `StatelessChildSpec.java`, `StatefulChildSpec.java`, `EventSourcedChildSpec.java`, `SupervisorChildSpec.java` — mailboxConfig field + withMailbox builder
- `CrashSignalTest.java` — updated anonymous AbstractActorRunner subclasses to pass MailboxConfig.unbounded()

## Key Design Decisions

- Best-effort size check (mailbox.size() >= capacity) — TOCTOU is acceptable for actor systems
- tell() returns void; REJECT strategy throws MailboxFullException; DROP_* are silent
- ask() and signal() bypass overflow check — signals are critical; ask overflow leaves future unresolvable
- Unbounded actors: Integer.MAX_VALUE capacity means check never triggers — zero overhead
- OverflowListener is nullable — overflow still applied even without listener

## Test Results

72/72 tests passing (68 pre-existing + 4 new BackPressureTest):
- `bounded_reject_throwsMailboxFullException` ✓
- `bounded_overflowListener_calledOnOverflow` ✓
- `bounded_dropOldest_keepsNewest` ✓
- `unbounded_actorUnaffected` ✓
