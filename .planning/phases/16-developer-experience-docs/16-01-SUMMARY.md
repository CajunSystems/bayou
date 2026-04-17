# Phase 16, Plan 1: DX — Exception Messages + Core Guides — Summary

## Status: Complete

## Tasks Completed

### Task 1 — Exception message improvements
- Commit: e279624
- BayouSystem: duplicate actor ID message now includes hint to stop/use unique ID
- BayouSystem: snapshotInterval message now includes bad value
- MailboxFullException: message includes actorId, capacity, and OverflowStrategy hint
- RestartWindow: maxRestarts message includes bad value

### Task 2 — CHANGELOG.md + docs/getting-started.md
- Commit: f9f0033
- CHANGELOG.md: version 0.1.0 history covering all 3 milestones
- docs/getting-started.md: 9-section guide from zero to supervised event-sourced actor + persistent topic + TestKit

### Task 3 — docs/actor-flavours.md + docs/testing.md
- Commit: ce8416f
- docs/actor-flavours.md: decision table + code example per flavor
- docs/testing.md: TestKit assertions + Awaitility comparison + tips

## Deviations
None

## Verification
- mvn test: all green, no regressions
- docs/: getting-started.md, actor-flavours.md, testing.md present
