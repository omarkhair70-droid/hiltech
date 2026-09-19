# 37 — Bootstrap OCI IaC / CI / Supply Chain

Date: 2026-09-19
Status: **PASS / BOOTSTRAP VERIFIED**

## Purpose

Materialize the frozen OCI repository/IaC shape and CI supply-chain rules without inventing production tenancy values or performing a premature cloud apply.

## IaC implementation

Commits:
- `1a417a6b21500b71926b4c60cffcea25f789efcd` — core OCI module contracts,
- `2e11904f52365a0588e3e980b5fb0ddf6ee8f9a7` — platform OCI module contracts,
- `4581a68d5139ff0cc848a8ead8280c5330c60e0d` — staging/production validation roots.

Bootstrap-resolved pins:
- Terraform **1.16.3**,
- Oracle/OCI Terraform Provider **8.29.0**.

Module interfaces:
- network,
- load-balancer,
- postgres,
- object-storage,
- kms-secrets,
- container-runtime,
- registry,
- observability,
- iam.

Environment roots:
- staging,
- production.

The roots intentionally contain no fake tenancy OCIDs, production secret values, final runtime shapes or authorized apply.

## Terraform gate

The Bootstrap CI proves:
- canonical `terraform fmt`,
- provider init with backend disabled,
- staging `terraform validate`,
- production `terraform validate`,
- exact Terraform/provider pins.

Run `35411033450` (#34) proved the Terraform contract job green.

## CI / supply-chain implementation

Primary hardening commit:
`ce867b04eb6c3720f214e8a21859594741d4312d`

Implemented:
- default `contents: read`,
- immutable full-SHA external action verification,
- committed strong-secret signature scan,
- dynamic Gradle version denial,
- mutable `:latest` marker denial,
- tracked real `.tfvars` denial,
- weekly Dependabot for GitHub Actions + Gradle,
- PR-only resolved Gradle dependency vulnerability review against the official OSV API.

The initial native GitHub Dependency Review Action attempt correctly failed because this repository does not have GitHub Dependency Graph enabled. The gate was not disabled: it was replaced with a repository-owned OSV review that resolves all Gradle module dependency reports and fails closed on HIGH/CRITICAL known vulnerabilities or API/parser failure.

## Verification

Canonical comprehensive branch run:
`35411127169` (#35) — **SUCCESS**

Passed jobs:
- foundation,
- database-contract,
- local-platform-contract,
- evidence-storage-contract,
- terraform-contract,
- supply-chain-contract.

`dependency-review` is intentionally skipped on branch push and executes the resolved-dependency OSV gate on the Bootstrap pull request.

## Production boundary

No OCI production apply is authorized by this gate.

Still activation/cutover work:
- tenancy/compartment OCIDs,
- Jeddah subscription/quota/capacity confirmation,
- approved network CIDRs,
- final DB/runtime shapes,
- OCI Resource Manager state/locking,
- KMS/Secret resource identities,
- immutable deployment image digests,
- staging deploy/recovery/security smoke,
- production approval.
