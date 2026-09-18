# ADR-012 — Windows Packaging / Update / Rollback

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH Windows must be deployable and recoverable as an office operating surface, not merely compilable.

The client needs:
- versioned Windows packaging,
- silent/managed installation,
- durable local data outside the install directory,
- deep-link capability,
- failed-update safety,
- predictable rollback,
- production code-signing later.

## Decision

Use **Compose Desktop/jpackage MSI** as the accepted Windows packaging direction.

Use a **controlled installer-swap lifecycle** as the pre-freeze operational baseline:

- build a versioned MSI,
- Authenticode-sign release packages,
- keep durable user/local data outside the application installation directory,
- uninstall/install the target package through managed lifecycle tooling when required,
- retain the previous signed installer for rollback,
- keep local schema/settings migration-compatible across supported versions.

The exact enterprise distribution mechanism remains open. It may be:
- MDM/software distribution,
- an updater/bootstrapper,
- an IT-managed release process,
- another Windows-appropriate deployment channel.

The `hiltech://` association is owned by the installation/update lifecycle boundary. The packaged app owns handling the URI after launch.

## Evidence

SPIKE-07, GitHub Actions run **35317815398**.

Passed on a real Windows runner:
- MSI packaging,
- disposable Authenticode signer identity verification,
- silent install/uninstall,
- packaged executable start,
- deep-link delivery,
- corrupt-update failure safety,
- LocalAppData preservation,
- v1 -> v2 installer swap,
- rollback v2 -> retained v1,
- state preservation through update/rollback/final uninstall.

Final marker:

`HILTECH_WINDOWS_LIFECYCLE_PASS install=PASS signature=PASS deep_link=PASS failed_update=PASS update=PASS rollback=PASS state_preserved=PASS`

## Consequences

Positive:
- Windows is now operationally credible as a first-class HILTECH surface.
- rollback does not depend on restoring application-owned data from the install directory.
- deployment tooling can evolve independently from domain/client code.
- deep-link behavior has a clear Windows lifecycle owner.

Costs:
- production certificate management is mandatory.
- local DB/settings migrations need strict forward/backward support policy.
- release tooling must retain rollback artifacts.
- installer swap may be less seamless than a future dedicated updater.

## Not Decided Here

- production certificate/provider,
- exact MDM/updater/distribution vendor,
- release-channel UX,
- whether a future in-place major-upgrade path replaces installer swap,
- final production Room migration policy.

## Revisit Triggers

Revisit if:
- HILTECH adopts an enterprise software-distribution platform with different packaging constraints,
- seamless in-place upgrade becomes a hard requirement,
- code-signing/provider rules require a different package format,
- Windows Store/MSIX or another channel becomes operationally superior,
- real local-schema migrations reveal rollback incompatibility.
