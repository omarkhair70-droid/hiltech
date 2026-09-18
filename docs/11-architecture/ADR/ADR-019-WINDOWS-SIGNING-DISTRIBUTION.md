# ADR-019 — Windows Production Signing & Distribution

Status: **ACCEPTED / PRE-FREEZE**
Date: 2026-09-18

## Context

ADR-012 / SPIKE-07 already accepted:
- Compose Desktop/jpackage MSI,
- controlled signed-installer swap,
- failed-update safety,
- rollback to retained previous installer,
- durable LocalAppData outside install directory.

The production gap was:
- trusted Authenticode signing,
- secure CI signing-key custody,
- enterprise/internal update distribution,
- update metadata/rollback policy.

HILTECH cannot assume Intune, SCCM, Group Policy, or Microsoft Store management exists today.

The distribution architecture must work independently while allowing future MDM to deploy the same signed MSI.

---

# Signing Decision

Use:

**DigiCert Organization Validated (OV) Code Signing Certificate**
provisioned in
**DigiCert KeyLocker cloud HSM**.

Rationale:
- public/trusted Authenticode identity for MSI/EXE.
- OV is appropriate for internal/B2B application distribution.
- Microsoft no longer treats EV as an automatic SmartScreen-reputation bypass, so EV cost/validation is not justified merely for SmartScreen.
- KeyLocker keeps the private key in managed FIPS-capable cloud HSM storage rather than exporting a PFX/private key to GitHub runners.
- DigiCert supports automated SignTool/GitHub CI signing workflows.

Azure Artifact Signing was considered but is not the first baseline because current Microsoft geographic availability for organizations does not include Egypt.

Self-signed production code signing is rejected as the baseline because HILTECH should not depend on manually installing a private root on every future Windows machine.

---

# Signing Identity

Publisher legal identity:
- must match the verified HILTECH legal organization identity used in the DigiCert certificate request.

Production signing certificate:
- OV Code Signing.
- KeyLocker provisioning.
- SHA-256 file digest.
- RFC3161 timestamp through DigiCert-supported timestamp service.
- certificate/key renewal is operationally planned before expiry.

Certificate lifecycle is external operational identity, not embedded into app domain schema.

---

# CI Signing Boundary

Unsigned build:
GitHub Windows runner
→ package MSI/EXE
→ tests
→ hash/build provenance
→ DigiCert KeyLocker remote signing
→ Authenticode verification
→ publish signed immutable artifact.

Rules:
- no exportable code-signing private key in GitHub.
- no PFX committed/stored as normal CI secret.
- KeyLocker API credential/client-auth credential is environment-protected.
- production signing job uses GitHub protected production environment.
- signing cannot run on arbitrary pull-request code.
- production signing is allowed only from approved release commit/tag.
- signed artifact is verified with Windows SignTool before publication.
- timestamp is mandatory.

---

# Artifact Identity

Every Windows release has:

- releaseId
- semantic app version
- build number
- git commit SHA
- MSI SHA-256
- MSI size
- Authenticode publisher identity
- certificate thumbprint/serial metadata for audit
- signedAt/timestamp verification
- minSupportedAppVersion
- migration compatibility class
- release channel
- rollout state
- createdBy/approvedBy
- release notes ref

Artifact bytes are immutable after signing.

Changing one bit creates a different release artifact.

---

# Distribution Decision

Primary baseline:
**HILTECH Update Service**

It consists of:

1. authenticated HILTECH server release manifest,
2. private OCI Object Storage release artifact,
3. short-lived authorized download target,
4. Windows updater/launcher that:
   - downloads installer,
   - verifies SHA-256,
   - verifies trusted Authenticode signature/publisher,
   - checks release compatibility,
   - launches controlled MSI update,
   - confirms resulting app version/health,
   - retains previous signed installer for rollback according policy.

This extends the already-proven ADR-012 installer-swap model.

The HILTECH API controls which authenticated device/user sees which release/channel.

---

# Why not make MDM mandatory?

Future HILTECH IT may use:
- Microsoft Intune,
- Group Policy,
- SCCM/MECM,
- another RMM/MDM.

Those tools can deploy the same signed MSI.

They are optional distribution adapters, not the only way HILTECH OS can update.

This keeps the product deployable today and enterprise-manageable later.

---

# Release Channels

Typed channels:
- INTERNAL
- PILOT
- STABLE

First production baseline:
- HILTECH office/field Windows devices normally consume STABLE.
- selected test devices can be enrolled in PILOT.
- INTERNAL is engineering/admin only.

Channel assignment is server-side device/release configuration.

No separate application binaries are needed per role.

---

# Release Manifest

Authenticated read model candidate:

- releaseId
- channel
- appVersion
- buildNumber
- artifactSha256
- artifactSizeBytes
- publishedAt
- minSupportedVersion
- migrationCompatibility
- mandatoryAfter?
- rolloutPercent / target device groups where used
- releaseNotesSummary
- short-lived download reservation endpoint/ref
- publisherIdentityExpected
- signingCertificateMetadata
- previousRollbackReleaseId?

The manifest itself is authoritative server data over authenticated HTTPS.

The MSI signature remains independent cryptographic artifact authenticity.

---

# Update Eligibility

Before offering/applying release:

- device/user authorized.
- channel matches.
- current version supported.
- no known migration incompatibility.
- release not revoked.
- rollout targeting permits it.
- local unsynced/offline state compatibility checked according client migration contract.

A mandatory security update may restrict old app operations only through an explicit server compatibility policy, not by silently breaking API semantics.

---

# Download

Windows client:
1. fetches authenticated release manifest.
2. requests download reservation.
3. receives short-lived private OCI Object Storage download URL or API proxy.
4. downloads to app-private/update staging directory.
5. checks exact SHA-256.
6. runs Authenticode verification.
7. checks expected publisher.
8. only then invokes updater.

A downloaded MSI failing any integrity/signature check is deleted/quarantined and never executed.

---

# Installation / Privilege

MSI installation follows the Windows installer/UAC policy proven by SPIKE-07.

HILTECH Update Service does not attempt to bypass Windows privilege controls.

If managed enterprise devices later use MDM/RMM, that system may install with its authorized device-management privilege.

---

# Rollback

Retain:
- current signed installer,
- previous rollback-eligible signed installer.

Rollback allowed only when:
- server marks previous release compatible with current DB/local schema state,
- ADR-012 state-preservation rules hold,
- no security revocation forbids old build.

Database/server compatibility follows separate release/migration strategy.

No arbitrary downgrade to unsupported version.

---

# Release Revocation

If a release is bad/compromised:

Server can:
- mark release REVOKED,
- stop offering its artifact,
- make next safe release mandatory,
- prevent rollback to revoked artifact.

Already installed compromised build requires incident runbook/device remediation; release manifest alone cannot remotely erase it.

---

# Signing Certificate Rotation

Certificate renewal/rotation:

- acquire/validate new DigiCert code-signing certificate in KeyLocker before old certificate expiry.
- update expected publisher/certificate trust metadata without changing publisher identity.
- timestamped old artifacts remain historically verifiable according platform/certificate semantics.
- test signing + install/update on staging devices.
- production pipeline switches certificate through protected configuration.

No source-code change required.

---

# SmartScreen

Authenticode signing proves publisher/integrity.

Do not promise that a new signed file will never show SmartScreen reputation prompts.

Reputation is operational/platform behavior.

HILTECH-controlled internal distribution plus consistent publisher identity reduces reliance on ad-hoc unsigned installers.

---

# Audit

Record:
- release artifact digest,
- source commit,
- build workflow/run,
- signing request/result,
- certificate identity,
- release approval,
- channel publication,
- revocation,
- rollback action.

Never log private signing credentials.

---

# Optional MDM Adapter

If HILTECH later adopts Intune/RMM/MDM:
- integrate release publishing/export.
- MDM installs the same Authenticode-signed MSI.
- HILTECH server can still expose release inventory/device compatibility.
- do not fork packaging architecture.

---

# Pre-Production Gates

- [ ] HILTECH legal publisher identity validated by DigiCert.
- [ ] OV Code Signing certificate issued.
- [ ] KeyLocker enabled.
- [ ] protected GitHub signing credentials configured.
- [ ] production signing workflow signs MSI.
- [ ] SignTool verification passes.
- [ ] fresh Windows machine recognizes trusted publisher.
- [ ] HILTECH Update manifest/download works from private OCI artifact storage.
- [ ] v1→v2 update passes on production-like device.
- [ ] failed/corrupt installer does not apply.
- [ ] rollback to previous allowed version passes.
- [ ] certificate-rotation rehearsal documented.

These are operational activation gates, not packaging architecture questions.

---

# Decision

Production signing:
**DigiCert OV Code Signing + DigiCert KeyLocker**.

Primary distribution:
**HILTECH Update Service using authenticated release manifests + private OCI artifact download + Authenticode verification + ADR-012 controlled MSI installer swap.**

MDM:
optional future adapter, not baseline dependency.
