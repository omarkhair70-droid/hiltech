# 02 — First-Slice API / Command / Read-Model Contracts

Status: **PRE-FREEZE TEMPLATE**

## Accepted Cross-Cutting Wire Rules

Locked technical:
- Ktor Client 3.5.2.
- Android OkHttp engine.
- JVM Desktop CIO engine.
- bearer OIDC access token.
- `X-Correlation-Id`.
- W3C `traceparent`.
- `Idempotency-Key` for retry-sensitive commands.
- `baseVersion` in concurrency-sensitive command body.
- JSON through shared typed DTOs.
- explicit commands, not unrestricted CRUD.
- binary files use reservation/direct upload/finalization.

Major API prefix `/v1` is accepted as current baseline.
Exact resource grammar is frozen here before bootstrap.

---

# Command Freeze Table

| Command | Route | Actor relation | Online/offline | baseVersion | Idempotency | Request schema | Success schema | Error set | Status |
|---|---|---|---|---:|---:|---|---|---|---|
| CreateWorkOrder | TBD | PM/create authority | online | policy | YES | TBD | TBD | TBD | REALITY_REQUIRED |
| AssignWork | TBD | PM/assign authority | online | YES | YES | TBD | TBD | conflict/permission/state | REALITY_REQUIRED |
| StartWork | TBD | assigned executor | offline replay | YES | YES | TBD | TBD | conflict/permission/state | REALITY_REQUIRED |
| BlockWork | TBD | assigned executor/supervisor | offline replay | YES | YES | TBD | TBD | TBD | REALITY_REQUIRED |
| ResumeWork | TBD | assigned executor/supervisor | offline replay | YES | YES | TBD | TBD | TBD | REALITY_REQUIRED |
| SubmitWorkCompletion | TBD | assigned executor | offline replay | YES | YES | evidence refs TBD | TBD | evidence/conflict/state | REALITY_REQUIRED |
| AcceptWork | TBD | reviewer relation | online | YES | YES | TBD | TBD | conflict/permission/state | REALITY_REQUIRED |
| RequestRework | TBD | reviewer relation | online | YES | YES | reason/evidence TBD | TBD | TBD | REALITY_REQUIRED |
| CancelWork | TBD | cancel authority | online | YES | YES | reason TBD | TBD | conflict/state | REALITY_REQUIRED |
| ReserveAsset | TBD | warehouse/policy | online | YES | YES | TBD | TBD | allocation conflict | REALITY_REQUIRED |
| CheckoutAsset | TBD | warehouse authority | online default | YES | YES | custody context TBD | TBD | custody/version conflict | REALITY_REQUIRED |
| ReturnAsset | TBD | warehouse receipt | online final | YES | YES | condition/accessories TBD | TBD | TBD | REALITY_REQUIRED |
| ReserveEvidenceUpload | TBD | assigned/evidence permission | online | context version | YES | file metadata | signed upload target | validation/permission | PROPOSED_FOR_REVIEW |
| FinalizeEvidence | TBD | evidence owner/context | online/replay | context | YES | uploadSessionId | READY metadata | checksum/storage errors | PROPOSED_FOR_REVIEW |

---

# Query / Read Model Freeze Table

| Read model | Consumer | Required fields | Sensitive filters | Pagination | Offline cache | Status |
|---|---|---|---|---|---|---|
| PMProjectCommandCenter | PM Desktop | reality/design freeze | field-level policy | TBD | no/limited | DESIGN_REQUIRED |
| TechnicianTodayItem | Technician Android | work identity/readiness/time | assigned only | cursor TBD | YES | DESIGN_REQUIRED |
| TechnicianJobBundle | Technician Android | Project/Site/Work/evidence/assets | least required field subset | n/a | YES durable | REALITY_REQUIRED |
| WarehouseAssetPassport | Warehouse | asset/custody/condition/calibration | cost restricted | n/a | maybe | REALITY_REQUIRED |
| WarehouseCheckoutContext | Warehouse | asset + recipient + project/work | restricted fields | n/a | online-first | REALITY_REQUIRED |
| SupervisorReviewView | Supervisor/Engineer | submitted version/evidence/tests | assigned/reviewer | cursor if queue | limited | REALITY_REQUIRED |
| PMWorkProgressItem | PM Desktop | authoritative state/version/exception | PM context | cursor | hint/cache | REALITY_REQUIRED |

---

# Standard Error Families

Accepted semantic families:
- UNAUTHENTICATED
- REAUTH_REQUIRED
- PERMISSION_DENIED
- OBJECT_NOT_VISIBLE
- REJECTED_VALIDATION
- REJECTED_STATE
- VERSION_CONFLICT
- DUPLICATE_REPLAY
- FAILED_RETRYABLE
- FAILED_PERMANENT
- INTEGRATION_PENDING
- INTEGRATION_UNKNOWN

Before freeze, every command above must list exactly which errors it may return and the safe `details` schema.

---

# Conflict Contract

SPIKE-15 validated:
- stale `baseVersion` does not overwrite authoritative state,
- local intent/evidence remains,
- dependent local command can become BLOCKED_BY_CONFLICT.

First-slice freeze must define exact `conflictType` and `allowedRecoveryActions` per Work/Asset collision.

Never return a generic conflict with no recovery semantics to field UI.
