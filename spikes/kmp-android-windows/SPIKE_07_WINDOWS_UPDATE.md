# SPIKE-07 — Windows Packaging / Update / Rollback

Status: RUNNING

## Goal

Prove the HILTECH Windows desktop client can be operated safely in an office environment, not merely compiled.

## Package

Compose Desktop / jpackage MSI.

Two disposable versions are built:
- 1.0.0
- 2.0.0

## Lifecycle Under Test

1. build MSI v1.0.0,
2. test-sign MSI using a disposable CI code-signing certificate,
3. verify Authenticode signature,
4. silently install v1.0.0,
5. run installed app lifecycle probes,
6. write local HILTECH state under LocalAppData,
7. register `hiltech://` URL protocol under HKCU from the Windows install/update lifecycle harness, targeting the installed packaged executable,
8. invoke `hiltech://work/WO-42` and verify the installed app receives it,
9. submit an intentionally corrupt update MSI and require installer failure,
10. verify v1.0.0 still starts and LocalAppData state is intact,
11. uninstall v1.0.0 as part of the controlled installer-swap strategy,
12. verify local state remains,
13. build + test-sign v2.0.0,
14. install v2.0.0,
15. verify version changed while local state survived,
16. uninstall v2.0.0,
17. reinstall retained v1.0.0 as rollback,
18. verify version rolled back while local state survived,
19. final uninstall,
20. verify local state is not silently deleted by uninstall.

## Update Strategy Proven

This spike proves a controlled **installer swap** update/rollback strategy:

- application binaries live in the installation directory,
- durable local user data lives outside the installation directory,
- updater/management tooling may uninstall old package and install the target package,
- rollback uses the previously retained signed installer,
- local DB/settings must remain version-migration compatible.

It does not claim in-place MSI major-upgrade semantics are already frozen.

## Deep Link

The spike registers `hiltech://` at the Windows installation/update lifecycle boundary and routes the URI to the packaged executable. The application owns URI handling after launch; it does not need to mutate registry state from inside the running GUI process.

The production product may realize the association through the installer, enterprise deployment tooling, or an updater/bootstrapper. Exact production registration mechanics remain a release decision.

## Pass

ACCEPT if a real Windows GitHub runner proves:
- MSI packages,
- test signature verifies,
- silent install/uninstall succeeds,
- packaged app starts,
- deep link reaches packaged app,
- LocalAppData state survives update,
- LocalAppData state survives rollback,
- LocalAppData state survives uninstall,
- corrupt/failed update does not destroy the installed version or LocalAppData,
- rollback package starts with the expected version.

## Still Open

- production certificate/vendor,
- enterprise MDM/software-distribution choice,
- user-facing updater UX,
- exact release-channel policy,
- final deep-link routes,
- production Room DB migration compatibility across real releases.

## Production status

Disposable deployment evidence only.
