# HILTECH Sync Engine Model

Status: ARCHITECTURE MODEL v0.1 / NOT IMPLEMENTATION FROZEN

## Objective
Safely converge device-local work and authoritative server state.

---

# 1. Sync Responsibilities

- pull server changes relevant to user/context
- push queued commands
- upload binaries
- preserve command order/dependencies
- retry transient failure
- stop/review permanent failure
- identify version conflict
- update local authoritative state after server acceptance
- expose sync state to UI
- produce diagnostics/observability

---

# 2. Command, Not Raw Record Merge

Prefer sending business commands where possible.

Example:
Instead of:
update asset.currentCustodian = X

Send:
CheckoutAsset(assetId, recipientId, projectId, baseVersion, operationId)

Server validates:
- permission
- lifecycle state
- current version
- reservation
- duplicate/idempotency
- invariants

This avoids unsafe generic last-write-wins.

---

# 3. Idempotency

Every retriable command needs stable operation/idempotency identity.

If device retries after timeout:
server can return previous result rather than execute twice.

Critical for:
- material consumption
- checkout/return
- task completion
- evidence registration
- expense submission
- any future payment-related request

---

# 4. Pull Model

Potential:
- since-cursor/change feed
- object version
- scoped subscriptions when online
- explicit refresh

Exact protocol is not frozen.

Local data remains optimized for UI reads.

---

# 5. Dependency Graph

Example:
1. Create evidence metadata
2. Upload photo
3. Submit job completion referencing evidence

Completion cannot sync before required evidence exists.

Queue must understand dependency, not only timestamp.

---

# 6. Background Sync

Triggers:
- connectivity regained
- application foreground
- periodic allowed work
- explicit user retry
- push/event hint

OS constraints must be respected.

---

# 7. Live + Sync

When online:
realtime events may update local DB.

UI should still render from consistent local state where practical rather than maintaining parallel "socket state" and "database state".

---

# 8. Conflict Output

Sync engine does not guess every conflict.

It produces structured:
- rejected command
- current server version
- conflict reason
- allowed recovery actions

UI/domain decides whether:
- auto-rebase
- ask user
- discard local
- create new version
- escalate

---

# 9. Diagnostics

Need:
- pending queue count
- oldest pending operation
- failed operations
- last successful pull/push
- binary backlog
- integration vs sync failure distinction
- per-user/device trace identifiers

Support/admin tooling must be able to diagnose without reading raw private data unnecessarily.

---

# 10. Sync Security

Every server application of an offline command re-checks:
- current identity/session authority
- current permission
- object state
- version/invariants

Offline permission at capture time does not guarantee later acceptance after offboarding/revocation.

## Completion gate
Requires:
- protocol choice
- local DB design
- server command handlers
- change-feed strategy
- background-worker strategy
- observability
- load/performance test
- comprehensive conflict tests.
