# HILTECH Final Stack / Version Review — 2026-09-18

Status: **PASS — FINAL FIRST-SLICE VERSION REVIEW COMPLETE**
Purpose: final explicit pin review before FIRST_SLICE_FREEZE.

## Review rule

HILTECH does **not** upgrade merely because a newer version exists.

Freeze preference order:
1. stable,
2. security/correctness acceptable,
3. proven by HILTECH spikes/contract tests,
4. compatible across Android + Windows + server,
5. reproducible.

A newer major/minor release does not automatically beat a slightly older version already proven end to end.

Focused patch upgrade is allowed when:
- it stays inside the accepted compatibility line,
- fixes a material build/security/correctness issue,
- a focused compatibility validation passes.

---

# Client / KMP

## Kotlin

Pin:
**2.4.20**

Decision:
KEEP.

Reason:
- accepted/proven HILTECH KMP line.
- current reviewed stable line.
- no discovered reason to move compiler before first-slice Freeze.

## Compose Multiplatform

Pin:
**1.11.1**

Current upstream has newer **1.12.0** stable.

Decision:
KEEP 1.11.1 for first slice.

Reason:
- HILTECH already proved Android + Windows behavior on 1.11.1.
- 1.12.0 includes migration/deprecation changes.
- no required first-slice feature/security fix forces the upgrade.
- upgrading visual/client framework immediately before Freeze would invalidate useful proof without product benefit.

Revisit:
post-first-slice dependency maintenance or a concrete blocker.

## Android Gradle Plugin

Accepted line:
**9.3.x**

Previously proven:
9.3.1.

Official patch line now contains:
- 9.3.2 lint/JDK17 fix.
- 9.3.3 additional D8/R8/Windows/build correctness fixes.

Decision:
**PIN 9.3.3 for the first production slice.**

Focused validation evidence:

AGP 9.3.2:
- branch: `validation/agp-9.3.2-20260918`
- run: `35388858253`
- result: PASS
- shared tests: PASS
- Android debug build: PASS
- Desktop compile: PASS
- Windows EXE: PASS
- Windows MSI: PASS

AGP 9.3.3:
- same isolated validation branch
- head: `b5f6db88cfd22ab8ddaa7d2fff6b7453caf55312`
- run: `35389326629`
- result: PASS
- shared tests: PASS
- Android debug build: PASS
- Desktop compile: PASS
- Windows EXE: PASS
- Windows MSI: PASS

Therefore 9.3.3 is the newest focused-validated patch in the accepted 9.3 line.

Do not jump to AGP 9.4.0 for first slice merely because it is newer.

## Gradle

Pin:
**9.5.0**

Decision:
KEEP.

Reason:
- AGP 9.3 official minimum/default.
- accepted HILTECH harness.
- no product benefit from bumping independently before Freeze.

Production repository:
commit Gradle Wrapper for 9.5.0 rather than relying on a floating setup alias.

## Android SDK

compileSdk:
**36**

targetSdk:
**36**

minSdk:
**23**

Decision:
KEEP first-slice proven values.

AGP can support a newer API, but HILTECH does not raise compile/target level simply to chase maximum API without a requirement/test pass.

## Room3

Pin:
**3.0.3**

Decision:
KEEP.

Reason:
- current stable reviewed release.
- includes fixes for transaction wrapper/coroutine transaction issues.
- accepted HILTECH offline architecture.

## Bundled SQLite

Pin:
**2.7.1**

Decision:
KEEP accepted line.

## KSP

Pin:
**2.3.10**

Upstream reviewed release:
2.3.12 exists.

Decision:
KEEP 2.3.10 first slice.

Reason:
- HILTECH Room/KMP spike evidence exists on 2.3.10.
- no discovered security/correctness blocker requires 2.3.12.
- KSP is build tooling, not a reason to invalidate accepted offline proof right before Freeze.

Revisit through normal dependency maintenance after bootstrap.

## kotlinx.coroutines

Pin:
**1.11.0**

Decision:
KEEP.

Reviewed as current stable release.

## WorkManager

Pin:
**2.11.2**

Decision:
KEEP.

Reason:
- current stable branch.
- accepted process-death/reconnect HILTECH evidence.
- 2.12 line is not required for first slice.

## CameraX

Pin:
**1.6.2**

Decision:
KEEP.

Reason:
- current stable reviewed release.
- accepted QR/evidence camera proof.

## Ktor Client

Pin:
**3.5.2**

Decision:
KEEP.

Reason:
- current reviewed stable release.
- exact shared Android/Windows transport proven in SPIKE-15.
- ADR-007 accepted.

Android engine:
OkHttp.

JVM Desktop engine:
CIO.

---

# Server

## Java

Server feature line:
**Java 21 LTS**

Client/Android build feature line:
**Java 17**

Decision:
KEEP feature lines.

CI should consume current security patch within the selected Temurin LTS feature line rather than permanently freezing an obsolete JDK patch.

## Spring Boot

Pin:
**4.1.1**

Decision:
KEEP.

Reviewed as current stable 4.1 release.

## Spring Modulith

Pin:
**2.1.1**

Decision:
KEEP.

Accepted HILTECH modular-monolith line.

## PostgreSQL

Pin:
**18.6**

Decision:
KEEP.

Reviewed current 18.x maintenance release.
Production provider:
OCI Database with PostgreSQL, subject to service image/version availability supporting the accepted major/minor baseline.

## jOOQ

Pin:
**3.21.8**

Decision:
KEEP accepted reviewed line.

## pgJDBC

Pin:
**42.7.13**

Decision:
KEEP.

Reviewed current release; includes maintenance fixes after the preceding security release.

## Flyway

Version ownership:
**Spring Boot 4.1.1 managed dependency line**

Decision:
do not invent a separate pre-code override.

At repository bootstrap:
- dependency lock/report records the exact resolved Flyway version,
- compatibility is verified against PostgreSQL 18.6,
- explicit override is added only if a concrete migration/tooling reason exists.

---

# Identity / Authorization

## Keycloak

Pin:
**26.7.4**

Decision:
KEEP accepted current reviewed line.

Native:
OIDC Authorization Code + PKCE S256.

## OpenFGA Server

Pin:
**1.20.0**

Decision:
KEEP.

Reviewed current release.
First-slice authorization model CI is already green.

## OpenFGA CLI for contract validation

Pin:
**0.7.20**

Decision:
KEEP exact validated tool pin.

## OpenFGA test GitHub Action

Release:
v0.1.2

Immutable production/contract SHA:
`e89aa8259796cd5ee5c1b1ae7d72c401029cb947`

---

# Observability

## OpenTelemetry Java

Pin/line:
**1.66.0**

Decision:
KEEP reviewed stable Java telemetry line.

Instrumentation remains vendor-neutral.

Collector:
pin exact production image digest/version during Bootstrap/deployment generation after OCI compatibility check.

---

# Windows

Packaging:
Compose Desktop / jpackage MSI.

Signing:
DigiCert OV + KeyLocker.

Distribution:
HILTECH Update Service.

Windows minimum:
Windows 10 x86-64 first-slice baseline.

Exact JBR/JDK packaging runtime:
resolve/pin during production Desktop packaging bootstrap and record in generated release provenance.

---

# Infrastructure

Provider:
OCI.

Primary candidate:
`me-jeddah-1`.

DR candidate:
`me-riyadh-1`.

Runtime:
OCI Container Instances preferred.
OCI Compute container fallback.

IaC:
Terraform + OCI Provider.
Exact Terraform CLI / OCI provider versions are pinned during Bootstrap after tenancy/API validation and then recorded in lockfiles.

Reason:
these provider tooling versions cannot be meaningfully validated against HILTECH tenancy before the tenancy exists; architecture/provider choice is already frozen.

---

# GitHub Actions production baseline

All production external actions:
**full immutable commit SHA**, tag comment on same line.

Approved baseline:

## checkout
`actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1`

## setup-java
`actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1`

## setup-gradle
`gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb # v6.3.0`

## OpenFGA test
`openfga/action-openfga-test@e89aa8259796cd5ee5c1b1ae7d72c401029cb947 # v0.1.2`

Policy:
`docs/13-delivery/first-slice-contract-pack/22_CI_SUPPLY_CHAIN_PINNING_CONTRACT.md`

Dependabot:
weekly GitHub Actions update PRs after production workflow bootstrap.

---

# Explicit non-upgrades before first-slice Freeze

Do NOT move solely for recency to:
- Compose Multiplatform 1.12.0.
- AGP 9.4.0.
- preview/RC WorkManager.
- Room3 3.1 alpha.
- Spring Boot preview.
- other preview/EAP server/client dependencies.

First slice values proof over novelty.

---

# Final review result

All first-slice application/server dependency and production CI-action decisions required for pre-code Freeze have been reviewed.

Final AGP pin:
**9.3.3**

Evidence:
- 9.3.2 focused run `35388858253` PASS,
- 9.3.3 focused run `35389326629` PASS.

Result:
`FINAL_STACK_REVIEW = PASS`

The remaining actual first-slice pre-code blocker is rendered design proof.
