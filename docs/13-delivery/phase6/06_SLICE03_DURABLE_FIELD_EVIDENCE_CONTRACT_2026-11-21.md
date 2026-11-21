# Phase 6 / Slice 03 — Durable Field Evidence Capture and Verified Upload

Date: 2026-11-21
Status: **ENGINEERING BUILDING / PR DRAFT — NOT PRODUCTION ACCEPTED**
Base: S02 merge `13c49c8b07060bd5495f6dcb8f219efe18ffb5c5`, postmerge Bootstrap `44875987623` PASS.

## Reused canonical authorities

- Phase2 Evidence lifecycle: `POST /v1/evidence/reservations`, signed private binary PUT, `POST /v1/evidence/{id}/finalize`, `GET /v1/evidence/{id}`; server derives classification, requirement, content type and max size from bound WorkOrder EvidencePolicy, with current source assignment and OpenFGA `can_submit_completion`. Existing server authorization, idempotency, audit and S3 object integrity are not rewritten.
- Phase4 Field JobBundle: online/current WorkOrder, exact assignment ID/version, binding evidencePolicy ID/revision, requirement key, allowed MIME list, max count and `offlineCaptureAllowedByPolicy`. Client snapshots are **capture hints only**, not permission to upload after revocation.
- Phase6 S01: scoped Room durable read cache; cold-start OfflineFieldReadOnly **never creates new field work/evidence intent**.
- Phase6 S02: actor and installation scoped WorkManager/online bootstrap, stable operation IDs. Never send another actor's captures.
- Existing Room `local_evidence` remains the local evidence source of truth; v2→v3 migration is additive, preserving all old pending commands/evidence and adding immutable capture source references.

## Small, explicit native scope

- Android accepts a selected image/file through the system document picker (camera capture may use an app-private destination with scoped FileProvider). Content is copied to a **randomized app-private file** (no public URL) before inserting metadata into Room.
- During local capture, require: authenticated online-verified *current session*, selected current JobBundle, same user and installation, valid WorkOrder assignment and EvidencePolicy binding, matching requirement and permitted MIME, optional `minCount` NOT treated as extra mandatory when policy does not require it, max 16 MiB hard cap. Do not require location, signature, or photo for every WorkOrder.
- Hash while copying: SHA-256 and exact byte count. Reject zero bytes, oversize/truncated reads, unsupported MIME. Use an atomic temporary file and rename; never insert Room metadata before bytes are durable.
- Store only relative, opaque internal file references in `local_evidence`; validate traversal/symlink and hash again before every upload. Store assignment ID/version, WorkOrder version and evidencePolicy binding revision at capture, not inferred from stale UI after reconnect.
- Use stable deterministic reserve/finalize operation IDs derived from immutable local evidence UUID; never regenerate new Evidence when retrying after response loss, process death or upload error. Reserve result is a server Evidence ID and upload-session ID; signed upload URLs **must never be persisted**.
- Reauthorize online actor at replay and again on the server. If identity, installation, assignment or policy changed, block/review; never quietly auto-rebase a capture against another WorkOrder/actor.
- Retry 429/503/network without discarding the file. 401 pauses for reauth, 403/404/409 and policy rejection preserve the local file and expose conflict. Expired signed upload sessions need an explicitly supported refresh path; don't silently create a second reservation.
- Finalize result `READY` means verified; `QUARANTINED` / `REJECTED` must **never** display READY or count toward completion. Keep the local file until READY or explicit user-authorized discard. A metadata read may confirm READY after an uncertain response; do not assume 2xx binary PUT is READY.
- The existing binary API does not implement a typed form Evidence payload. Don't serialize arbitrary forms into fake JPEG/PDF; typed forms require a separately frozen Policy/schema authority.

## Change control CR-S03-001 — causally proven S02 version advancement

Review of the actual S02→S03 offline sequence identified an unavoidable conflict: a photo may be captured against WorkOrder version V while the same technician's queued StartWork changes the authoritative version to V+1 immediately before Evidence replay. Pure exact-version equality would incorrectly strand normal offline captures.

**Narrow accepted engineering exception:** a captured WorkOrder version V may be followed by source version V+1 **only** when Room contains an APPLIED S02 Start/Block/Resume command for the **same actor, installation and WorkOrder**, with `base_version=V` and the **server-confirmed** `last_server_version=V+1`. Current assignment ID/version, bound EvidencePolicy ID/revision, source-authorized capture allowance, current status and server Evidence authorization must still match. Persist the *original* capture version; do not alter/rebase the Evidence row. Any unrelated/unknown source change, different device/actor, missing authoritative S02 ACK, or greater version drift remains REVIEW_REQUIRED with bytes preserved.

When Evidence has **already been finalized** but is awaiting a server scan (`UPLOADED_UNVERIFIED` with server evidence ID), a metadata-only READY/status read is authorized by the existing Evidence endpoint itself. It must not be blocked by subsequent WorkOrder version changes; no new reservation/upload/command is performed in that path.

No new backend lifecycle, FGA permission, EvidencePolicy or material authorization is introduced by this exception.

## Failure and reality gates

1. Local evidence/command Room v1→v2→v3 migrations preserving pending writes, evidence rows and actor isolation.
2. Android process kill/reopen with file and metadata still present; offline capture in a verified session, read-only cold start locked.
3. SHA / count / MIME / 16MiB / invalid path / tampering / empty file hard failures.
4. Reserve, signed PUT, Finalize and READY/pending-scan/QUARANTINED/REJECTED classification using existing test S3/PG/OpenFGA; duplicates and partial failures.
5. Current assignment revoked, wrong device/actor, upload session expired, network 401/409/429/503, safe retry without duplicate Evidence.
6. Representative phone UX, camera/document permission, storage / battery constraints and real technician approval remain independent of synthetic CI.

`P6_S03_ENGINEERING = BUILDING`
`P6_S03_UPLOAD_READY = NOT_YET_PROVEN`
`P6_S03_REAL_OPERATOR_ACCEPTANCE = OPEN`
