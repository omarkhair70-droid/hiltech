# ADR-001 — Android + Windows Client Platform

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH requires:
- Android field/mobile client,
- Windows office/operations desktop client,
- one coherent product and domain model,
- meaningful shared code,
- ability to keep platform-specific boundaries where required,
- native Windows packaging.

Candidate direction was Kotlin Multiplatform + Compose Multiplatform.

## Decision

Use **Kotlin Multiplatform** as the primary shared client platform and **Compose Multiplatform** as the primary UI foundation for Android + Windows/JVM Desktop.

Keep:
- shared domain/application/client-data code where appropriate,
- shared UI where it genuinely improves consistency,
- separate Android and Desktop application entry points,
- platform-specific implementations for OS/device capabilities.

Do not force 100% shared UI if a platform-specific implementation is materially better.

## Evidence

SPIKE-01:
- GitHub Actions run 35293858617.
- shared KMP tests PASS.
- Android debug build PASS.
- Desktop JVM compile PASS.
- Windows EXE PASS.
- Windows MSI PASS.

Tested viable line:
- Kotlin 2.4.20,
- Compose Multiplatform 1.11.1,
- AGP 9.3.1,
- Android SDK 36,
- Android min SDK 23,
- JDK 17.

## Alternatives

Still relevant as fallback/revisit targets:
- native Android + .NET/WinUI/WPF desktop,
- web/Tauri/Electron desktop,
- Flutter.

These are not selected as the baseline because SPIKE-01 proved the KMP Android/Windows path viable.

## Consequences

Positive:
- Kotlin across client layers,
- meaningful shared product/domain code,
- shared state/UI primitives possible,
- Windows native packaging feasible.

Costs:
- desktop-specific ergonomics still require dedicated design/work,
- not every native platform capability belongs in common code,
- version alignment between Compose/Android dependencies must be managed deliberately.

## Not Proven By This ADR

This ADR does not claim:
- dense desktop data UX is already proven,
- Arabic/RTL is already proven,
- authentication is already proven,
- offline/sync is already fully proven,
- update/rollback operations are already proven.

Those remain their own spikes/gates.

## Revisit Triggers

Revisit if:
- SPIKE-06 shows unacceptable dense desktop performance/ergonomics,
- Windows deployment/update becomes operationally unsuitable,
- critical native integration requires excessive divergence,
- future supported versions break the tested architecture materially.


## SPIKE-06 follow-up — 2026-09-18

The dense-desktop revisit trigger has been tested and did **not** force a platform change.

GitHub Actions run 35305553872 passed:
- 50,000-row representative payroll data operations,
- 10,000-row bulk selection,
- desktop compile,
- a real Compose Desktop first rendered frame under Xvfb.

Result:
Compose Desktop remains the accepted Windows UI baseline. Final table component design and real office-hardware performance budgets remain open.
