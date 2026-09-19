# 34 — Bootstrap Evidence / Object Storage

Date: 2026-09-19
Status: **PASS / BOOTSTRAP VERIFIED**

## Purpose

Materialize the frozen binary-evidence storage boundary without moving provider SDK concerns into business logic.

Canonical sources:
- `06_FILES_EVIDENCE.md`,
- `18_PRODUCTION_INFRASTRUCTURE_CONTRACT.md`,
- ADR-014,
- accepted SPIKE-12.

## Production boundary

Implemented in:
`server/application/src/main/kotlin/com/hiltech/server/documents/storage/EvidenceObjectStorage.kt`

Primary implementation commit:
`7f7196023a462de43d1222069bb851676b3ce5de`

The adapter boundary is S3-compatible and the production provider remains OCI Object Storage with production bucket encryption/KMS configured by infrastructure, not by business/domain code.

## Frozen first-slice invariants

The implementation enforces:
- private-object delivery model,
- opaque organization/evidence/object-version key:
  `org/{organizationId}/evidence/{evidenceId}/objects/{objectVersionId}`,
- maximum object size 16 MiB,
- one signed PUT,
- no multipart baseline,
- lower-case 64-character SHA-256 contract,
- expected size in reservation specification,
- checksum included in the signed upload contract,
- short-lived upload target,
- private signed GET,
- download expiry capped at five minutes.

## Authoritative finalization

Provider metadata/checksum is defense-in-depth only.

Finalization:
1. HEADs the private object.
2. verifies expected content length.
3. verifies HILTECH evidence/checksum metadata.
4. reads the stored bytes.
5. recomputes SHA-256 over the actual stored bytes.
6. rejects size/hash mismatch.
7. returns VERIFIED only when bytes satisfy the frozen contract.

Therefore a provider/emulator accepting a truncated/corrupt upload cannot make evidence authoritative.

## Failure classification

Storage verification distinguishes:
- `Verified`,
- `Rejected`,
- `Retryable`.

Missing/not-yet-visible object and transient provider failure remain retryable.
Integrity/contract mismatch is rejected.

The evidence business state machine remains authoritative in PostgreSQL; this storage adapter does not independently mark an Evidence row READY.

## Configuration

Environment-backed bootstrap properties:
- `HILTECH_EVIDENCE_STORAGE_ENABLED`,
- `HILTECH_EVIDENCE_STORAGE_ENDPOINT`,
- `HILTECH_EVIDENCE_STORAGE_REGION`,
- `HILTECH_EVIDENCE_STORAGE_ACCESS_KEY`,
- `HILTECH_EVIDENCE_STORAGE_SECRET_KEY`,
- `HILTECH_EVIDENCE_STORAGE_BUCKET`,
- `HILTECH_EVIDENCE_UPLOAD_EXPIRY_SECONDS`,
- `HILTECH_EVIDENCE_DOWNLOAD_EXPIRY_SECONDS`.

No production credential is committed.

## Contract verification

Canonical run:
`35409534337` (#27) — **SUCCESS**

The dedicated `evidence-storage-contract` job used a disposable Moto 5.2.3 S3-compatible endpoint with the production adapter and proved:
- private bucket protocol,
- checksum-bearing signed PUT,
- frozen object-key structure,
- truncated/corrupt upload cannot finalize,
- retry using correct bytes succeeds,
- finalization recomputes and matches SHA-256/size,
- unsigned private read is denied,
- signed GET returns exact bytes,
- >16 MiB specification is rejected.

The same run also proved:
- Shared tests PASS,
- committed Room schema PASS,
- Android debug build PASS,
- Desktop compile PASS,
- Server tests PASS,
- PostgreSQL migrations/constraints PASS,
- jOOQ generation/verification PASS,
- server compile against generated jOOQ PASS.

## Deliberate boundary

This Bootstrap gate proves the object-storage adapter.

It does not prematurely implement every first-vertical Evidence API/application command. Reserve/finalize business transactions will wire this adapter to `evidence` and `evidence_upload_session` when the production vertical is implemented.

Production KMS key/bucket policy belongs to OCI IaC.

## Next

Local/dev platform services, observability baseline, OCI IaC skeleton and final CI/security hardening.
