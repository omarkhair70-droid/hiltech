# 39 — Bootstrap Security Change Control: Embedded Tomcat

Date: 2026-09-19
Decision: **PATCH REQUIRED / CONTRACT PRESERVED**

## Trigger

The Bootstrap PR dependency gate initially scanned all Gradle configurations and found build/test/tooling advisories mixed with runtime dependencies.

The review scope was corrected to the shipping graphs only:
- server `runtimeClasspath`,
- Android `releaseRuntimeClasspath`,
- Desktop `runtimeClasspath`.

PR run `35412308683` (#41) then resolved **397 shipped coordinates** and found exactly one affected shipping component:

`org.apache.tomcat.embed:tomcat-embed-core:11.0.24`

Blocking advisories:
- GHSA-9xv2-5v5q-p794 — CRITICAL,
- GHSA-gcx9-497g-6cp6 — CRITICAL,
- GHSA-h3x4-894j-xpx5 — CRITICAL.

No shipped Netty or Bouncy Castle blocker remained after runtime scoping.

## Resolution

Keep the frozen framework decision:
- Spring Boot **4.1.1**,
- Java 21,
- embedded Tomcat major/minor **11.0**.

Advance only the security patch:
- Tomcat **11.0.24 → 11.0.26**.

The server applies the pin consistently to `org.apache.tomcat.embed` dependencies through Gradle resolution strategy and records the exact patch in the version catalog.

## Why this is not architecture redesign

This is a security patch inside the already-selected embedded container family.

It changes neither:
- server framework,
- public API contract,
- persistence,
- auth architecture,
- module ownership,
- deployment architecture,
- client contract.

No vulnerability suppression or allowlist is used.

## Verification required before merge

The Bootstrap PR must prove on the patched head:
1. shipped-runtime OSV review has zero HIGH/CRITICAL blockers,
2. server tests PASS,
3. PostgreSQL/Flyway/jOOQ PASS,
4. Android/Desktop regressions PASS,
5. Evidence/local-platform/Terraform/supply-chain gates remain green.

Only then may PR #18 merge.
