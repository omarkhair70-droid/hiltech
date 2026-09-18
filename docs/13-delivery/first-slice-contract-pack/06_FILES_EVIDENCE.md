# 06 — Files / Evidence Contract

Status: **PRE-FREEZE TEMPLATE**

## Locked Technical Protocol

- binary content is outside normal JSON command payload.
- server reserves upload.
- client uploads directly to short-lived S3-compatible target.
- expected SHA-256 is part of contract.
- finalize verifies stored bytes/metadata independently.
- restricted evidence has no permanent public URL.
- retry after failed/corrupt upload is safe.
- local evidence persists until authoritative finalize or explicit discard.

---

# Reality-Required Evidence Policy

For each first-slice WorkOrder type freeze:

| Work type | Evidence item | Mandatory | Capture source | Offline | Reviewer | Retention | Client-visible |
|---|---|---:|---|---:|---|---|---|
| TBD | photo | TBD | camera/file | YES | TBD | TBD | TBD |
| TBD | measurement/test | TBD | manual/import | YES/TBD | engineer/supervisor TBD | TBD | TBD |
| TBD | checklist | TBD | app | YES | TBD | TBD | TBD |
| TBD | signature | TBD | app/external | TBD | TBD | TBD | TBD |

Do not make GPS/photo/signature universally mandatory without real work/site justification.

---

# Evidence Metadata To Freeze

Candidate:
- evidenceId,
- target object/work ID,
- evidenceType,
- contentType,
- originalFileName safe metadata,
- sizeBytes,
- sha256,
- capturedAt/clientOccurredAt,
- capturedBy subject,
- device reference where policy needs it,
- instruction/work version context,
- storage state,
- authoritative finalizedAt,
- visibility/classification,
- supersession/version relation if applicable.

Every field needs a retention/security justification.

---

# Storage Key Rule

Object-storage key is infrastructure identity, not business meaning exposed to client.

Freeze:
- bucket/container separation policy,
- opaque key generation,
- tenant/org partition if required,
- lifecycle/versioning,
- encryption,
- quarantine/scanning if required,
- retention/legal hold if applicable.

Provider remains open until provider/ops freeze.

---

# Upload State Machine

Candidate semantics:
- LOCAL_ONLY
- RESERVATION_REQUIRED
- RESERVED
- UPLOADING
- UPLOADED_UNVERIFIED
- READY
- RETRYABLE
- REJECTED/CORRUPT

Freeze exact state names with local/server ownership.

READY is server-authoritative only.

---

# Required Tests

- correct upload/finalize,
- corrupt/truncated upload rejected,
- retry succeeds,
- unauthorized reservation denied,
- unauthorized download denied,
- expired signed target,
- local evidence survives process death,
- local evidence survives sync conflict,
- file type/size validation,
- checksum mismatch,
- duplicate finalize/idempotency,
- work cancellation/reassignment while evidence is pending.
