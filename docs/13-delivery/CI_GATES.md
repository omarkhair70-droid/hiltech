# HILTECH CI Gates

Status: PRE-CODE DELIVERY SPEC v0.1

## Goal

Every change should prove the layer it touches before merge.

---

# Pull Request Gates

## Universal
- compile/build.
- formatting.
- lint/static analysis.
- unit tests.
- secrets scan.
- dependency/security scan.
- changed-file ownership review where configured.

## Server
- module architecture verification.
- domain/application tests.
- PostgreSQL integration tests for relevant modules.
- Flyway validation.
- API contract tests.

## Client Shared
- KMP compile for supported targets.
- shared unit tests.
- local DB migration tests.
- sync tests where changed.

## Android
- build.
- Compose/UI representative tests.
- lint.
- package validation.

## Desktop
- build.
- critical UI/data-grid tests.
- packaging check where release branch.

## Infrastructure
- validate IaC/config.
- no secrets.
- environment diff review.

---

# Main Branch Gates

After merge:
- full test suite.
- server artifact.
- Android artifact.
- Windows artifact.
- staging deployment.
- migration dry-run.
- smoke suite.
- observability health.

---

# Release Candidate Gates

Must pass:
- all main gates.
- end-to-end critical workflows.
- security checks.
- DB backup confirmation.
- migration rehearsal.
- release notes.
- rollback/forward-fix plan.
- signed artifacts.
- staged/internal distribution.

---

# Protected Rules

No:
- direct production database edits.
- skipping migration validation.
- committing secrets.
- merging failed architecture tests.
- disabling tests to release.
- force-approving critical security findings without explicit documented acceptance.

---

# Fast Path / Emergency

Emergency change can reduce ceremony only if:
- incident documented,
- authorized owner,
- minimal focused tests pass,
- rollback available,
- full post-change validation follows.

Never bypass:
- authentication,
- financial safety,
- data integrity,
- migration safety.

---

# CI Tooling

Provider/tool not frozen.

Likely GitHub Actions because repository is GitHub.

Final choice confirmed during stack/infrastructure freeze.
