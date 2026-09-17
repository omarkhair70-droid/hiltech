# HILTECH Offline-First Model

Status: PRODUCT / ARCHITECTURE MODEL v0.1 / NOT TECH-STACK FROZEN

## Thesis
HILTECH field work must continue through poor or absent connectivity without creating a second truth.

Offline-first does NOT mean every action is allowed offline.

The system divides operations by authority and risk.

---

# 1. Data Classes

## A. Local-First Working Data
Can be created/edited locally and queued for sync.

Examples:
- technician evidence
- field notes
- measurements
- job progress draft
- material usage draft
- stock count draft
- photos pending upload

## B. Cached Read
Server truth can be downloaded and read offline.

Examples:
- assigned jobs
- project/site context
- drawings/specs
- asset passport
- relevant contacts
- work instructions
- evidence requirements

## C. Server-Authoritative Write
Must be confirmed online before final state.

Examples:
- payroll approval
- payment execution
- stock write-off
- access administration
- final high-risk financial action
- critical permission changes
- some client/commercial approvals

## D. Integration-Dependent
Cannot be declared successful offline because external authority is required.

Examples:
- bank transfer
- door/access action
- camera live stream
- external message delivery
- remote NVR command

---

# 2. Offline Bundle

Before site work, HILTECH can prepare a scoped bundle containing:
- work order/task
- project/site identity
- latest allowed drawings/specifications
- required evidence schema
- assigned people
- reserved tools/materials
- relevant asset/site records
- contacts/access instructions
- permission snapshot/tokens as technically appropriate

Bundle must carry:
- fetched-at timestamp
- source versions
- expiry/freshness metadata

---

# 3. Local Mutation Queue

Every offline-capable mutation receives:
- local operation ID
- actor
- object
- base version
- command type
- payload
- created timestamp
- dependency order
- sync status

Statuses:
LOCAL_DRAFT / QUEUED / SENDING / ACCEPTED / REJECTED / CONFLICT / RETRY_WAIT / CANCELLED

Exact naming not frozen.

---

# 4. User-visible Sync State

Never hide sync truth.

At object/action level user can understand:
- Saved on this device
- Waiting for connection
- Uploading
- Synced
- Needs attention
- Rejected by server
- Conflict

Do not display a server-complete state for a merely local command.

---

# 5. Evidence

Photos/files:
- create local metadata record first
- retain local durable file until confirmed upload
- resumable/retriable upload strategy
- checksum/identity
- link upload to evidence object
- do not lose metadata if binary upload fails

---

# 6. Read Freshness

Cached data must expose freshness when relevant.

Examples:
- Drawing revision downloaded yesterday.
- Asset state last synced 45 minutes ago.
- Project summary cached.

For safety-critical or authority-critical views, stale state can block actions.

---

# 7. Deletion

Avoid destructive offline delete.

Prefer:
- mark/cancel local draft
- request archival/deletion online
- server decides according to permissions and object lifecycle

---

# 8. Shared Device / Lost Device

Offline storage must account for:
- encrypted sensitive local data
- session expiry
- remote revocation
- device lost
- employee offboarded
- cached client/employee data cleanup

Exact encryption/device-management strategy belongs to security/stack phase.

---

# 9. Offline Role Examples

## Technician
High offline capability.

## Supervisor
High offline capability for field plan/evidence/review drafts.

## Engineer
High read/evidence offline capability.

## Warehouse
Limited offline scan/count queue; final high-risk adjustment online.

## PM
Cached project read and selected field actions; commercial approvals online.

## Finance
Mostly online authoritative.

## Mohamed
Read cache possible; critical approvals online.

## Client
Read cache optional; final approval online.

---

# 10. Offline Invariants

1. No silent data loss.
2. No false success.
3. No duplicate business action from retries.
4. User can see unresolved operations.
5. Critical authority remains server-side.
6. Stale base version can trigger conflict.
7. Evidence preserves source/local identity until server confirms.
8. Offline action retains actor and device context for audit.

## Completion gate
Requires:
- sync protocol,
- conflict matrix,
- local DB schema,
- binary upload strategy,
- auth/offline credential design,
- encrypted storage,
- WorkManager/background behavior,
- per-feature offline classification,
- test matrix for airplane mode/crash/restart/duplicate retry/device loss.
