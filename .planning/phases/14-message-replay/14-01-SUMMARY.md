# Phase 14, Plan 1 — Message Replay: Summary

## What Was Implemented

### Task 1 — Refactor LogView creation + SubscribeFrom support

- **`TopicCommand.java`**: Added `SubscribeFrom<M>(long offset, Ref<M> subscriber)` sealed record.
- **`TopicActor.java`**: Refactored constructor to accept `LogView` directly (removing `preStart()` and the `LogTag` import); added `SubscribeFrom` case in the `handle()` switch and `handleSubscribeFrom()` method that calls `readAfter(offset).join()`, delivers replay in-order, then adds to `liveSubscribers`.
- **`BayouTopic.java`**: Added `LogView` field; updated constructor to accept it; added three public methods: `subscribeFrom(long, Ref<M>)`, `subscribeFromBeginning(Ref<M>)`, and `latestOffset()`.
- **`BayouSystem.java`**: Updated `topic()` factory to create `LogView` first via `sharedLog.getView(LogTag.of("bayou.topic", k))`, then pass it to both `TopicActor` constructor and `BayouTopic` constructor. Added `LogView` and `LogTag` imports.

### Task 2 — MessageReplayTest

Created `src/test/java/com/cajunsystems/bayou/MessageReplayTest.java` with 5 test cases:

1. `doneWhen_subscribeFromBeginningReceivesAllHistoryThenLive` — publishes 100 messages, waits for all in log, subscribes from beginning, verifies 100 replayed + 1 live = 101 total.
2. `subscribeFromOffsetReceivesOnlyMessagesFromThatOffset` — publishes 10 messages, subscribes from offset 4, verifies exactly 5 messages received (seqnums 5-9).
3. `replayArrivesBeforeSubsequentLiveMessages` — verifies that 5 historical messages arrive in order before 5 live messages in a `ConcurrentLinkedQueue`.
4. `latestOffsetReflectsPublishedMessageCount` — verifies `latestOffset()` advances correctly with each batch of publishes.
5. `subscribeFromDoesNotInterfereWithLiveSubscribers` — verifies a plain live subscriber and a replay subscriber coexist correctly.

## Deviations from Plan

### 0-indexed seqnums

The plan stated `subscribeFromBeginning(ref)` as "the convenience alias (offset=0)" assuming 1-indexed seqnums. Gumbo uses **0-indexed seqnums** (empty log returns -1; first entry has seqnum 0). Since `readAfter(0)` skips the entry at seqnum 0, `subscribeFromBeginning` uses `subscribeFrom(-1, subscriber)` so that `readAfter(-1)` returns all entries.

This required a bug fix commit after Task 1, and the test file was committed together with the fix in one commit.

### Test offsets adjusted

Test 2 uses `subscribeFrom(4, ref)` (not `subscribeFrom(5, ref)` as the plan stated) to get exactly 5 messages (seqnums > 4 = seqnums 5,6,7,8,9). Test 3 uses `subscribeFrom(-1, ref)` instead of `subscribeFrom(0, ref)` to replay all 5 historical messages.

### 3 commits instead of 2

The implementation produced 3 commits rather than the planned 2:
- `6668172` — Task 1: feat(14-01): SubscribeFrom replay
- `dae7c6b` — Bug fix + Task 2: fix(14-01) + MessageReplayTest (committed together)

## Commit Hashes

- **Task 1** (feat): `6668172`
- **Task 2 + fix** (fix + test): `dae7c6b`

## Test Results

`mvn test` — 97 tests, 0 failures, 0 errors. Full suite green.
