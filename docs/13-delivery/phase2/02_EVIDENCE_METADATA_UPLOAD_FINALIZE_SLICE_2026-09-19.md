# Phase 2 / Slice 02 — Evidence Metadata + Upload / Finalize

Date: 2026-09-19  
Status: **CONTRACTED / IMPLEMENTATION AUTHORIZED**

## Goal

Materialize the frozen Evidence application lifecycle over the already-verified private object-storage adapter:

1. reserve authoritative Evidence metadata,
2. issue a short-lived signed upload target,
3. upload bytes directly to private object storage,
4. finalize only after independent server verification,
5. expose authoritative metadata and safe download behavior,
6. preserve authorization, idempotency, audit, policy binding and retry semantics.

This slice does **not** redesign Evidence storage or Work execution.

## Canonical sources

Mandatory:
- `AGENTS.md`
- `docs/00-program/CURRENT_PROGRAM_STATUS.md`
- `docs/13-delivery/IMPLEMENTATION_ORDER.md`
- `docs/13-delivery/first-slice-contract-pack/06_FILES_EVIDENCE.md`
- `docs/13-delivery/first-slice-contract-pack/34_BOOTSTRAP_EVIDENCE_OBJECT_STORAGE_2026-09-19.md`
- `docs/13-delivery/first-slice-contract-pack/05_AUTHORIZATION_POLICY_TESTS.md`
- `docs/13-delivery/first-slice-contract-pack/openfga/first-slice-model.fga`
- `docs/13-delivery/first-slice-contract-pack/00_CONFIGURATION_POLICY_SCHEMAS.md`
- `database/migrations/V0003__configuration__first_slice.sql`
- `database/migrations/V0005__work__execution.sql`
- `database/migrations/V0007__evidence_documents__evidence.sql`
- Reality registers for field-capture usability.

Reuse:
- verified `EvidenceObjectStoragePort`,
- Phase 2 Slice 01 canonical HTTP/error/idempotency runtime,
- Phase 1 authenticated identity/device/session context,
- OpenFGA fail-closed authorization adapter,
- authorization projection outbox,
- append-only audit writer,
- safe telemetry.

## Reality alignment

Reported field reality says technicians/engineers need fast contextual photo/file/voice/problem/completion capture without turning every observation into a heavy form.

This slice provides the formal binary Evidence lifecycle only.

It must:
- be fast enough to underpin later low-friction field capture,
- keep binary upload separate from JSON commands,
- keep formal metadata/authority/state structured,
- avoid inventing a full chat feature,
- avoid making photo/GPS/signature globally mandatory.

## Initial authoritative target scope

### Supported now
`targetType = WORK_ORDER`

For this target:
- `targetId == workOrderId`,
- organization is derived through `work_order.project_id -> project.organization_id`,
- WorkOrder policy binding is authoritative,
- reservation authority is `work_order.can_submit_completion`.

### Unsupported now
Any other target type, including Project / Site / Asset / Warehouse / generic object, returns a typed fail-closed rejection.

Do **not**:
- grant broad organization-member upload authority,
- invent project/asset authority before their production domain phases,
- use client role/title text as authorization truth.

Adding a later target type requires its own target adapter + current source-authority guard + contract tests.

## WorkOrder authority rule

Reserve requires current source truth **and** OpenFGA permission.

Candidate source paths for `work_order.can_submit_completion`:
- current active USER assignment matching the actor,
- current active TEAM assignment where the actor is a current team member.

Only source-current candidate relations are used as fail-closed guard tuples.

Do not guard unrelated inactive/non-authorizing OR branches.

If no source-current assignment path exists: deny before OpenFGA.

## Evidence policy binding

The client supplies:
- operationId,
- targetType,
- targetId / workOrderId,
- evidenceRequirementKey,
- evidenceTypeCode as stale-client assertion,
- contentType,
- originalFileName?,
- sizeBytes,
- expectedSha256,
- capturedAt,
- clientOccurredAt?,
- supersedesEvidenceId?.

The server derives from the WorkOrder-bound EvidencePolicy revision:
- evidencePolicyId,
- evidencePolicyRevision,
- canonical evidenceTypeCode,
- allowedContentTypes,
- system maximum size,
- classificationCode,
- retentionPolicyCode where relevant,
- clientVisibilityMode,
- securityScanClass.

Rules:
- `evidenceRequirementKey` is required for this binary lifecycle.
- supplied `evidenceTypeCode` must match the bound requirement.
- content type must be policy-allowed.
- size must be >0 and <= min(policy system maximum, 16 MiB).
- classification/visibility are never accepted as client authority.
- bound historical policy revision is used even if a newer policy is active today.

## Reserve transaction

`ReserveEvidenceUpload` is idempotent.

Within one PostgreSQL transaction:
- validate operation/header binding,
- authorize current WorkOrder target,
- load WorkOrder + Project organization + bound policy snapshot,
- validate requirement + content/size,
- create Evidence row in `RESERVED`,
- create EvidenceUploadSession in `RESERVED`,
- generate opaque object key reference using server-generated objectVersionId,
- write projection intents for:
  - `evidence:{id}#work_order@work_order:{workOrderId}`,
  - `user:{actorId}#creator@evidence:{id}`,
- write safe audit event,
- complete idempotent command semantic result.

The signed upload URL itself must not be persisted in the idempotency result/audit/telemetry.

After commit:
- generate a fresh short-lived signed upload target from the authoritative reservation,
- return required headers + session expiry/constraints.

## Upload session

- DB upload-session expiry is authoritative for finalize.
- expired session cannot finalize.
- retrying the same reserve operation returns the same Evidence/upload-session identity.
- a later dedicated refresh operation may be introduced if production UX needs renewed signed targets; do not silently create a second Evidence row.

## Finalize authorization

Finalize re-authorizes.

Allowed paths are evaluated independently to avoid OR-branch over-deny:
1. current WorkOrder `can_submit_completion` authority, or
2. authoritative Evidence creator relation once projected/applied.

Pending/failed projection never grants.

WorkOrder cancellation/terminal obligations still apply.

## Finalize storage/state behavior

Server independently verifies:
- object exists / retryable visibility,
- expected content length,
- Evidence metadata binding,
- recomputed SHA-256 over stored bytes,
- content-signature policy where required.

Results:
- transient/not-visible -> typed retryable error, DB remains non-READY,
- size/hash/metadata mismatch -> `REJECTED`,
- trusted/native valid bytes -> `READY` only after all required checks,
- `ARBITRARY_FILE` -> `QUARANTINED` after integrity verification until malware scan PASS.

Do not mark `ARBITRARY_FILE` READY merely because checksum matched.

## Native media signature validation

For `NATIVE_MEDIA`, authoritative READY requires content-signature/MIME compatibility.

Initial validator may support a narrow reviewed set such as JPEG/PNG/WebP.

Unsupported native-media signatures fail closed rather than being mislabeled READY.

Extending types is policy + validator work, not a free-form MIME bypass.

## Evidence authorization projections

Reserve owns Evidence relation projection intents:
- evidence -> work_order,
- creator -> evidence.

Later reviewer/client-visible tuples are projected only when the corresponding authoritative relationship exists.

Do not create client visibility tuples merely because `clientVisibilityMode` says client-visible; explicit client object/organization authorization still applies.

## Metadata / read behavior

Minimum server read:
- get authoritative Evidence metadata by ID after current authorization.

Minimum download:
- creator/currently-authorized Evidence viewer may request delivery,
- INTERNAL / RESTRICTED: private signed GET <= five minutes,
- HIGHLY_RESTRICTED: direct signed URL is not allowed; streaming/proxy remains a later explicit implementation if no current contract path exists,
- unsupported viewer paths fail closed.

Permanent public URLs are forbidden.

## API surface

Candidate routes:

- `POST /v1/evidence/reservations`
- `POST /v1/evidence/{evidenceId}/finalize`
- `GET /v1/evidence/{evidenceId}`
- `POST /v1/evidence/{evidenceId}/download-target`

All command routes:
- use canonical `ErrorEnvelope`,
- preserve `X-Correlation-Id`,
- require `Idempotency-Key` where retry-sensitive,
- use shared authenticated client boundary.

## Shared client / offline boundary

Shared Ktor client owns typed reserve/finalize/read/download DTOs.

Android/Windows engine details do not leak upward.

For later offline field capture:
- local binary may exist before reservation,
- local file must persist until authoritative READY or explicit discard,
- replay uses a stable operation ID,
- server re-authorizes at replay time.

This slice need not build the complete camera/voice UX, but must not make that later flow impossible.

## Audit / telemetry hygiene

Audit may record:
- actor,
- evidence ID,
- WorkOrder target,
- operation type,
- state transition,
- policy revision refs,
- safe reason/result,
- correlation ID.

Never audit/trace:
- signed URLs,
- raw bytes,
- access tokens,
- object-storage credentials,
- arbitrary filenames when classification policy says unsafe,
- private content payload.

Telemetry dimensions remain IDs/types/result codes only.

## Required evidence

Before VERIFIED:

1. real PostgreSQL reserve/finalize contract,
2. real S3-compatible production adapter,
3. real OpenFGA authorization check for WorkOrder reserve,
4. unauthorized reservation denied,
5. unsupported target type denied,
6. exact duplicate reserve returns same Evidence/session identity,
7. changed semantic request with same operation ID rejected,
8. correct upload -> READY for supported safe class,
9. truncated/hash mismatch -> REJECTED,
10. missing/transient object -> retryable,
11. expired upload session -> deny finalize,
12. arbitrary-file -> QUARANTINED, never READY without scan PASS,
13. native-media signature mismatch -> REJECTED,
14. reassignment/cancellation while pending re-evaluates authority/obligation,
15. private download authorization,
16. shared Android/Windows client tests,
17. Bootstrap / Phase 1 / Slice 01 regressions green,
18. Spring Modulith boundary green.

## Deliberate non-scope

- full Work execution UI,
- camera/gallery/voice UX,
- Project/Asset generic Evidence targets,
- malware-scanner vendor activation,
- retention deletion/legal hold engine,
- client portal evidence sharing,
- multipart >16 MiB,
- public URLs,
- generic document management,
- full chat.

## Verification gate

Mark Slice 02 VERIFIED only on the exact head where:
- required evidence above passes,
- no authorization shortcut exists,
- no client-controlled classification/visibility exists,
- no corrupt/quarantined binary can become READY,
- all inherited regression gates are green.
