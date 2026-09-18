# SPIKE-07 Result — Windows Packaging / Update / Rollback

Date: 2026-09-18
Decision: **ACCEPT — WINDOWS MSI OPERATIONAL LIFECYCLE PASSED**

## Evidence

GitHub Actions run: **35317815398**
Spike branch head: `72660d29ef5a89f13b35ef461588be199a522b69`

Final marker:

`HILTECH_WINDOWS_LIFECYCLE_PASS install=PASS signature=PASS deep_link=PASS failed_update=PASS update=PASS rollback=PASS state_preserved=PASS`

## Proven

On a real GitHub-hosted Windows runner:

- Compose Desktop/jpackage produced a real MSI for v1.0.0.
- A disposable Authenticode signing identity was generated and the embedded MSI signer matched that certificate.
- v1 installed silently with `msiexec`.
- The installed packaged executable started and reported version 1.0.0.
- Local durable state was written under LocalAppData.
- `hiltech://` was registered at the Windows installation/update lifecycle boundary and targeted the packaged executable.
- `hiltech://work/WO-42` reached the installed app.
- A deliberately corrupt MSI update failed without destroying the installed v1 or LocalAppData state.
- v1 was removed through the controlled installer-swap path while LocalAppData remained.
- v2.0.0 was built, signed and installed.
- v2 started with the correct version and preserved v1 local state.
- v2 was removed and retained v1 was reinstalled as rollback.
- rollback started as v1.0.0 with local state intact.
- final uninstall did not silently delete LocalAppData.

## Accepted Direction

The pre-freeze Windows operational baseline is a **controlled signed-installer swap**:

1. package versioned MSI,
2. preserve user/local data outside the installation directory,
3. install target package through managed lifecycle tooling,
4. retain a previous signed package for rollback,
5. keep local schema/settings migration-compatible across supported versions.

URL/deep-link association belongs to the Windows installation/update boundary rather than requiring the running GUI process to mutate registry state.

## Important Signing Boundary

The spike certificate is intentionally self-signed and disposable.

The spike proves:
- the MSI is Authenticode signed,
- the embedded signer identity is the expected generated certificate.

It does **not** claim a production Windows trust chain is selected or proven.

Still required for production:
- trusted code-signing certificate/provider,
- certificate protection/rotation policy,
- release-channel policy,
- enterprise distribution/MDM/updater choice.

## Still Open

- production code-signing certificate/provider,
- exact enterprise deployment channel,
- user-facing updater/bootstrapper UX,
- in-place MSI major-upgrade semantics if later preferred over installer swap,
- production Room/local schema migrations across real releases,
- final deep-link route catalog,
- HILTECH office IT/device policy.

## Production Status

Disposable operational evidence only. No production client code was created by this spike.
