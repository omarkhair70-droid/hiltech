# 19 — Windows Release / Update Contract

Status: **CONTRACT CANDIDATE v0.1 / SIGNING + DISTRIBUTION BASELINE CLOSED**
Date: 2026-09-18

Canonical:
- `docs/11-architecture/ADR/ADR-012-WINDOWS-PACKAGING-UPDATE.md`
- `docs/11-architecture/ADR/ADR-019-WINDOWS-SIGNING-DISTRIBUTION.md`

## Purpose

Define the production-facing release objects/API/client behavior that turn the SPIKE-07 MSI lifecycle into a safe HILTECH-controlled Windows distribution system.

---

# 1. ReleaseArtifact

Fields:

- id: UUID
- platform: WINDOWS
- packageType: MSI
- appVersion: String
- buildNumber: Long
- gitCommitSha: String
- artifactSha256: String
- artifactSizeBytes: Long
- objectStorageRef: opaque ref
- publisherIdentity: String
- signingCertificateSerial: String?
- signingCertificateThumbprint: String?
- timestampVerifiedAt: Instant?
- migrationCompatibility: ReleaseMigrationCompatibility
- minimumServerContractVersion: Int?
- minimumSupportedClientVersion: String?
- previousRollbackReleaseId: UUID?
- createdAt
- createdBy
- version: Long

Immutable after publication except operational state/revocation metadata.

---

# 2. Release

Fields:

- id: UUID
- artifactId: UUID
- channel: INTERNAL / PILOT / STABLE
- lifecycleState: DRAFT / READY / PUBLISHED / PAUSED / REVOKED / RETIRED
- publishedAt: Instant?
- mandatoryAfter: Instant?
- rolloutPercent: Int?
- targetDeviceGroupIds: List<UUID>
- releaseNotesSummary: String?
- createdAt/by
- approvedAt/by?
- revokedAt/by?
- revokeReason?
- version: Long

Rules:
- artifact must be correctly signed/verified before READY.
- PUBLISHED release is not mutated to point to different artifact.
- rollback target cannot be REVOKED/unsupported.
- rolloutPercent 0..100 where used.

---

# 3. WindowsDeviceReleaseContext

Server read model:

- deviceId
- currentAppVersion
- currentBuildNumber
- assignedChannel
- minimumAllowedVersion
- latestEligibleRelease
- updateRequired: Boolean
- updateReasonCode?
- localSchemaCompatibilityHint
- serverContractCompatibility
- releasePolicyVersion

Device-reported version is diagnostics/input; server does not trust it for authorization identity.

---

# 4. API

## Get update state

`GET /v1/windows/releases/current`

Authenticated native headers:
- X-Client-Platform: windows
- X-Client-Version
- X-Device-Installation-Id

Result:
- current device release context.
- no direct permanent public artifact URL.

## Reserve release download

`POST /v1/windows/releases/{releaseId}/download-reservation`

Checks:
- authenticated/authorized device/user.
- release PUBLISHED.
- channel/target eligibility.
- not revoked.
- compatibility.

Returns:
- short-lived private OCI Object Storage URL.
- expected SHA-256.
- expected size.
- expected publisher identity.
- expiry.
- correlationId.

## Report update result

`POST /v1/windows/releases/{releaseId}/installation-results`

Payload:
- operationId
- previousVersion
- attemptedVersion
- result: SUCCESS / FAILED / ROLLED_BACK
- safe error code
- installer exit code?
- clientOccurredAt
- resultingVersion?
- localDataPreserved check/result where measurable

No sensitive machine dumps in normal payload.

---

# 5. Release administration

Permissioned commands:

- CreateReleaseDraft
- AttachSignedArtifact
- MarkReleaseReady
- PublishRelease
- PauseRelease
- ResumeRelease
- RevokeRelease
- ChangeRolloutTarget
- MarkMandatoryAfter

All online-authoritative and audited.

Critical Publish/Revoke requires configured release authority and may require re-auth/approval.

---

# 6. Artifact storage

Use separate private OCI bucket/container or strongly isolated prefix for Windows release artifacts, distinct from Evidence.

Object key candidate:

`windows/releases/{releaseId}/{artifactId}/hiltech-{appVersion}-{buildNumber}.msi`

This path may expose non-sensitive version metadata but no secret/customer/user data.

Production:
- private.
- server-side encryption.
- immutable artifact after publication.
- signed download reservation.
- retention keeps rollback-eligible artifacts.
- revoked artifacts remain audit-preserved but no longer downloadable to normal devices.

---

# 7. Signing workflow

Protected GitHub release job:

1. checkout exact approved commit.
2. clean Windows build.
3. run tests/package MSI.
4. compute pre-sign build provenance.
5. invoke DigiCert KeyLocker signing through supported tooling.
6. Authenticode SHA-256 + timestamp.
7. run `signtool verify /pa /v`.
8. compute final signed artifact SHA-256.
9. upload immutable private artifact.
10. create ReleaseArtifact metadata.
11. only then allow release READY/PUBLISH workflow.

No production signing on PR/fork event.

---

# 8. Client verification

Before invoking MSI:

1. HTTPS/API release eligibility valid.
2. signed download reservation valid.
3. downloaded bytes size matches.
4. SHA-256 exact match.
5. Windows Authenticode chain valid.
6. signature timestamp valid.
7. publisher identity matches expected HILTECH publisher.
8. release not locally/server known revoked.
9. migration/update compatibility accepted.

Any failure:
- do not execute.
- delete/quarantine downloaded installer.
- report safe failure code.

---

# 9. Local updater state

Candidate states:

- IDLE
- UPDATE_AVAILABLE
- DOWNLOADING
- VERIFYING
- READY_TO_INSTALL
- INSTALLING
- RESTART_REQUIRED
- VERIFYING_INSTALL
- SUCCESS
- FAILED
- ROLLBACK_AVAILABLE
- ROLLING_BACK
- ROLLED_BACK

Updater state is operational UI, not server business truth.

---

# 10. Pending offline work / update

Before applying update:

- Room/local DB can be migrated from current supported schema.
- pending commands/evidence are preserved.
- if release cannot migrate a supported pending payload/schema safely:
  - update is blocked,
  - user/admin gets explicit compatibility reason,
  - no destructive reset.

Mandatory security update can require connection/support path but still cannot silently erase unsynced work.

---

# 11. Rollback

Previous signed installer:
- retained locally where policy allows.
- server Release points to eligible previousRollbackReleaseId.

Rollback requires:
- previous artifact not revoked.
- current local schema compatible.
- server contract accepts previous client.
- operator/user path authorized where required.

Rollback result is reported.

---

# 12. Update UX

Normal user:
- concise update available / downloading / ready / success.
- do not expose certificate internals.
- mandatory update explains why work is blocked only when truly required.

Support/Admin diagnostics:
- release ID.
- versions.
- digest verification.
- signer/publisher.
- installer exit code.
- local schema version.
- safe correlation ID.

---

# 13. MDM interoperability

If Intune/SCCM/RMM later exists:
- export/use same signed MSI.
- MDM may bypass HILTECH self-update UI and install under device-management policy.
- HILTECH app reports resulting version.
- no second packaging format is required by default.

If MSIX/Store is later justified, treat as separate distribution adapter, not rewrite of business/client architecture.

---

# 14. Certificate rotation

Release pipeline resolves active signing profile from protected operations config.

Rotation test:
- sign staging artifact with new cert.
- Windows trust verification.
- vN→vN+1 update.
- expected publisher identity continuity.
- server/client compatibility with new certificate metadata.

Client must trust valid public chain + expected publisher, not pin one certificate thumbprint forever.

---

# 15. SmartScreen / trust semantics

The contract guarantees:
- Authenticode signature.
- verified publisher identity.
- artifact integrity.
- timestamp.
- HILTECH-controlled authenticated distribution.

It does not guarantee immediate SmartScreen reputation for every new binary hash.

No release correctness logic depends on SmartScreen reputation.

---

# 16. Operational metrics

Track:
- published releases.
- device version distribution.
- update availability→success latency.
- download failures.
- digest/signature failures.
- MSI install failures.
- rollback count.
- revoked-release device exposure.

Do not collect unrelated personal desktop telemetry.

---

# 17. Pre-Freeze vs Bootstrap

Pre-code contract:
- signing provider/method selected.
- release/update objects/API/verification states defined.
- artifact storage/provider defined.
- rollback semantics defined.

Post-Freeze Bootstrap:
- implement Release tables/API.
- implement Windows updater client.
- implement protected signing workflow.
- configure KeyLocker credentials.
- run production-like signed MSI update/rollback tests.

Certificate purchase/issuance is an operational activation item, not a reason to redesign the contract.

---

# Decision

The Windows release path is contract-defined enough to remove generic:
- "which signer?",
- "where is update hosted?",
- "how does client know update?",
- "do we need Intune?",
- "how do we verify/rollback?"

from architecture uncertainty.

Remaining activation work is DigiCert organization validation/issuance + CI credentials + real production-device verification.
