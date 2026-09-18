# SPIKE-01 Result — KMP Android + Windows

Date: 2026-09-18
Decision: **ACCEPT — PLATFORM FEASIBILITY PASSED**

## Hypothesis

A HILTECH client can share Kotlin/Compose code between Android and Windows while retaining platform application entry points and native desktop packaging.

## Exact tested line

- Kotlin 2.4.20
- Compose Multiplatform 1.11.1
- Android Gradle Plugin 9.3.1
- Gradle 9.5.0 in CI
- JDK 17
- Android compile/target SDK 36
- Android min SDK 23

## Evidence

GitHub Actions run: 35293858617

Linux:
- Android SDK setup PASS.
- shared tests PASS.
- Android debug build PASS.
- desktop compile PASS.

Windows:
- desktop compile PASS.
- EXE package PASS.
- MSI package PASS.

## Findings

### Compose 1.12.0
Not selected for this stable spike line because its current Android dependency line required compileSdk 37.

### Stable Android line
The spike instead used Compose Multiplatform 1.11.1 with Android SDK 36.

### minSdk
21 failed because a current dependency required API 23.
API 23 passed.
Final company minimum SDK still requires actual HILTECH device inventory.

## What this accepts

- KMP as credible Android + Windows client foundation.
- Compose Multiplatform as credible shared UI/client foundation.
- platform-specific Android/Desktop entry points.
- Windows native packaging feasibility.

## What this does NOT accept yet

- final navigation/design.
- dense desktop 10k+ row viability.
- Arabic/RTL quality.
- Room/local persistence.
- offline conflict ergonomics.
- native OIDC/auth.
- update/rollback operations.
- final client architecture/version lock.

Those remain separate spike/freeze gates.

## Production status

Spike code is disposable evidence and is not production bootstrap.
