# Phase 13, Plan 1 — Durable Subscriptions: Summary

## What Was Implemented

### TopicCommand.java
Added two new sealed records to the `TopicCommand<M>` sealed interface:
- `SubscribeDurable<M>(String subscriptionId, Ref<M> subscriber)` — registers a named durable subscriber
- `UnsubscribeDurable<M>(String subscriptionId)` — removes a durable subscriber and deletes its stored position

### TopicActor.java
Full rewrite to add durable subscription support alongside existing live-subscriber logic:

- New field: `HashMap<String, Ref<M>> durableSubscribers`
- New imports: `AppendResult`, `LogEntry`, `ByteBuffer`, `HashMap`, `Iterator`, `List`, `Map`
- `handleSubscribeDurable`: looks up stored KV position (`"sub:" + subscriptionId`); if present, calls `readAfter(storedSeqnum)` and replays all missed entries to the subscriber; if absent (new subscription), records current `getLatestSeqnum()` as the join point
- `handleUnsubscribeDurable`: removes from map, deletes KV position via `logView.deleteValue`
- `handlePublish` refactored: captures `AppendResult.seqnum()` from `logView.append`; calls new `deliverToLiveSubscribers` helper; then iterates `durableSubscribers`, skipping/removing dead refs, delivering payload and advancing stored position via `logView.setValue`
- `deliverToLiveSubscribers`: extracted from old `handlePublish` — delivers to `liveSubscribers` with lazy dead-ref cleanup

### BayouTopic.java
Two new public methods added:
- `subscribe(String subscriptionId, Ref<M> subscriber)` — sends `SubscribeDurable` command
- `unsubscribeDurable(String subscriptionId)` — sends `UnsubscribeDurable` command

### DurableSubscriptionTest.java
Four test cases covering the full durable subscription contract:
1. `doneWhen_durableReceivesAllNonDurableMissesGap` — durable subscriber sees all messages; non-durable misses the gap
2. `durableSubscriberCatchesUpOnResubscribe` — dead actor replaced by new actor with same ID; catches up exactly 5 missed messages
3. `unsubscribeDurableForgetPosition` — after `unsubscribeDurable`, a reconnect with the same ID starts fresh (receives only 1 new message, not 5 missed)
4. `twoSubscriptionsProgressIndependently` — two durable subscribers with different IDs track positions independently

## Deviations from Plan

None. The implementation followed the plan exactly:
- All APIs and method signatures match the plan
- Dead ref removal from `durableSubscribers` happens lazily during `handlePublish` (not `handleUnsubscribeDurable`) — this is by design per plan: position is preserved even when the actor dies, enabling catch-up on reconnect
- `deliverToLiveSubscribers` extracted as specified

## Test Results

All 4 `DurableSubscriptionTest` cases pass. Full suite: **92 tests, 0 failures, 0 errors**.

## Commit Hashes

- Task 1 (implementation): `bc3a6ab`
- Task 2 (tests): `c0db44d`
