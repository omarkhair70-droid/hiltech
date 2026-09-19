# 22 — CI Supply-Chain / Action Pinning Contract

Status: **PASS / BOOTSTRAP IMPLEMENTED**
Date: 2026-09-18

## Purpose

Production CI/CD workflows must not depend on mutable action major tags.

GitHub recommends pinning third-party actions to a full-length commit SHA for strongest supply-chain immutability.

HILTECH baseline:
- every external GitHub Action in production/release/deploy/security workflows is pinned to an immutable full commit SHA,
- the human-readable release tag is written as an inline comment,
- Dependabot monitors GitHub Actions and proposes reviewed SHA updates.

Spike/history workflows are evidence and do not need retroactive rewriting merely to match production policy.

---

# 1. Approved baseline action pins

## Checkout

Release:
`actions/checkout v7.0.1`

Production reference:
`actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1`

## Java

Release:
`actions/setup-java v6.0.1`

Production reference:
`actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1`

## Gradle

Release:
`gradle/actions v6.3.0`

Production setup-gradle reference:
`gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb # v6.3.0`

The same repository commit may be used for its other approved sub-actions when those sub-actions are intentionally selected.

## OpenFGA contract test

Release:
`openfga/action-openfga-test v0.1.2`

Production/contract reference:
`openfga/action-openfga-test@e89aa8259796cd5ee5c1b1ae7d72c401029cb947 # v0.1.2`

## Dependency vulnerability review

The Bootstrap PR gate resolves the actual Gradle dependency graph for:
- server,
- shared KMP core,
- Android app,
- Desktop app.

A repository-owned Python gate queries the official OSV API by Maven coordinate/version and fails closed on query/parse errors or HIGH/CRITICAL known vulnerabilities.

This gate is PR-only.

The native GitHub Dependency Review Action was evaluated but requires the repository Dependency Graph feature. Rather than weaken or skip vulnerability review when that repository setting is unavailable, HILTECH uses the source-controlled OSV gate.

---

# 2. New action admission rule

Before adding any new external action to production workflow:

1. prefer GitHub/official vendor-maintained action.
2. inspect release provenance/maintenance.
3. choose a stable release, not a floating branch.
4. resolve tag to full commit SHA.
5. pin full SHA in workflow.
6. include tag/version comment on same line.
7. grant only required job permissions.
8. review action inputs/secrets exposure.
9. avoid actions that require broad write permission without a justified release/deploy use case.
10. record the action in FINAL_STACK / CI ledger if it becomes production-critical.

Examples needing this review later:
- DigiCert signing action/tool integration,
- OCI deployment/auth action if one is used instead of CLI/API,
- artifact upload/download action,
- security/scanning action.

No unknown action gets adopted just because a README uses `@main`.

---

# 3. Workflow permissions

Default workflow/job token:
- `contents: read`

Elevate narrowly per job.

Examples:
- dependency submission may need `contents: write` or required dependency permission.
- release metadata write job may need repository/release write permission.
- OCI deployment should use federated/short-lived cloud auth where supported, not broad GitHub repository write permissions.
- production signing job gets only the KeyLocker/auth secrets it needs.

PR from untrusted forks:
- never receives production environment secrets.
- never executes production signing/deploy.

---

# 4. Dependabot

Bootstrap now includes `.github/dependabot.yml`:

- package-ecosystem: github-actions
- package-ecosystem: gradle
- directory: /
- schedule: weekly

Dependabot may update SHA-pinned actions when the version comment/tag is on the same line.

Every update PR:
- runs CI,
- shows old/new action release,
- is reviewed before merge,
- production/release action updates are not silently auto-merged by default.

Security updates get expedited review.

---

# 5. Repository Actions policy

Preferred repository/organization policy where available:

- require actions to be pinned to full-length commit SHA.
- allow GitHub-owned + explicitly approved third-party actions.
- deny arbitrary actions by default for production workflows where policy granularity permits.

If account/plan settings cannot enforce this globally:
- CI policy/check script verifies production workflow `uses:` references contain full SHAs.

---

# 6. Runner version / Node runtime compatibility

Production uses GitHub-hosted runners baseline unless an explicit self-hosted need appears.

Because modern actions use Node 24:
- do not introduce an old self-hosted runner.
- if self-hosted runner is later used, its minimum supported runner version is part of the deployment/CI contract.

No production dependency on a developer desktop runner.

---

# 7. Gradle build reproducibility

Production Gradle build:
- uses Gradle Wrapper where repository bootstrap creates it.
- wrapper distribution URL/version committed.
- wrapper checksum validation enabled.
- setup-gradle action pinned by SHA.
- dependency locking/version catalog used for explicit production dependencies.
- Spring Boot-managed transitive versions can remain BOM-managed, while resolved dependency lock/report is captured in CI.

Spike harness using `gradle-version: 9.5.0` remains technical evidence; production repository should commit the wrapper rather than download an implicit current version.

---

# 8. OCI / Signing secrets

Production GitHub environments:
- staging
- production

Use environment protection/approval for production.

Secrets:
- never available to pull_request from untrusted source.
- never printed.
- KeyLocker private signing key itself never exists as a GitHub secret/PFX.
- OCI deployment authentication should prefer federated/short-lived identity; if bootstrap static credential is temporarily needed, scope/rotation is documented.

---

# 9. Action update rule near Freeze

Immediately before first production Freeze:
- confirm each baseline action release still exists/not revoked.
- no automatic upgrade solely because a newer major exists.
- security/correctness issue can trigger a focused compatibility validation.
- record final exact SHA set in FINAL_STACK.md.

After Freeze:
- action updates are normal dependency maintenance through reviewed PRs.
- a major update that changes build semantics may require a focused compatibility gate, not full product rediscovery.

---

# 10. Current result

CI supply-chain policy is no longer open.

Baseline immutable pins:
- checkout v7.0.1
- setup-java v6.0.1
- gradle/actions v6.3.0
- OpenFGA test action v0.1.2

Bootstrap enforcement is active:
- external actions in the production Bootstrap workflow must use full 40-character SHAs,
- default workflow token remains `contents: read`,
- strong committed-secret signatures are rejected,
- mutable `:latest` runtime markers are rejected,
- dynamic Gradle versions are rejected,
- tracked real `.tfvars` files are rejected,
- Dependabot monitors GitHub Actions and Gradle,
- PR resolved-dependency OSV vulnerability review is required by workflow behavior before Bootstrap merge.

Remaining:
- enforce equivalent repository/organization Actions policy where account settings permit,
- add/pin DigiCert/OCI deployment actions only if they are actually selected,
- production environment approval/federated OCI auth remain cutover work.
