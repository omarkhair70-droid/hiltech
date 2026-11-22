# Phase 11 — build evidence and merge gates (23 Nov 2026)

Repository: omarkhair70-droid/hiltech
Owner branch: phase11/handover-service-20261122
Draft PR: #167. Planning PR #161.

## Exact observed verification

At earlier Phase11 head 150e598920757ac9ccbcd90036bfb4d766a6771e, the owner ran locally using the connected Windows worktree and the JDK17 launcher:
gradlew :server:application:test --tests com.hiltech.server.service.MaintenanceDraftPolicyTest --tests com.hiltech.server.service.HandoverReadinessPolicyTest --tests com.hiltech.server.service.SupportDeskFieldRulesTest --no-daemon --console=plain

Observed result: BUILD SUCCESSFUL in 5m 24s; 9 actionable tasks (8 executed, 1 cache). The server compileKotlin and compileTestKotlin tasks completed, and focused tests completed. Existing Kotlin/Gradle deprecation warnings were emitted but did not fail this run.

This exact run did NOT include the subsequent P11B2 support triage code and P11 source SQL/source-digest hardening. Do not represent it as verification of the latest head.

The connected Desktop Commander reached its monthly usage limit after an updated local run was started, so its final output cannot be read from this chat. GitHub Actions on later heads had pending/queued jobs at review time. No latest-head PASS has been observed.

## Implemented components on draft branch

- P11A: source-linked internal draft handover and source digest, blocked from client review, issuance and acceptance until approved requirements/authorization and signed contract.
- P11B: internal-only support ticket and note, without guessed warranty or SLA.
- P11B2: manager-authorized immutable event ledger for internal triage and active Employee owner, expected latest event sequence, audit, idempotency. Source SupportTicket remains immutable.
- P11C: internal proposed maintenance visit against source WorkOrder, source checklist read, evidence-linked findings, expected WorkOrder version, audit, idempotency; never claims a field visit has occurred.
- KMP client and standalone native Compose feature components for Android/Windows. Integrator-owned app shell wiring is pending.
- All pending Phase11 DDL under database/pending/phase11, not assigned Flyway V-numbers; feature flags OFF.

## Required proof before any merge/flag enablement

1. Compile and focused unit tests on exact latest commit; KMP desktop and Android production compilation, no UI dead routes.
2. Migrate real clean + upgrade PostgreSQL after the Phase7 V0060 and other sibling PRs. Do NOT assume staging SQL has run.
3. Negative DB tests: cross-tenant Project/WorkOrder/Site/Evidence, forged signed contract, immutable snapshots, mismatch of latest Evidence revision, later superseding revision, idempotent same/different payload, concurrent versions, actor role revocation.
4. P11A actual HILTECH redacted approved handover, document completeness, explicit customer evidence-sharing grant and legal acceptance authority.
5. P11B coverage and SLA governed by immutable verified P10 signed contract, actual authorized award evidence, client PO and company-approved service policy. Free text warranty/maintenance terms are NOT computed durations or coverage by themselves.
6. P11C real checklist/visit frequency/service technicians and P7 parts request / P8 Finance integration (as linked source operations only). No duplicated scheduling, stock, WorkOrder, Document, invoice or Commercial object.
7. End-to-end staging role tests: PM, field technician and any permitted viewer; real source fixtures, revoked roles, Windows and Android behavior, no client portal and no unintended external messages.
8. DevOps signs migration order, merged-source dependency order, Oracle/Coolify staging database restore, shared shells and approvals. Keep all Phase11 endpoints OFF by default until these proofs pass.

## Safe currently available workflows

Internal manager can draft handover, record internal ticket/note/triage, propose a visit and record READY Work Evidence findings using explicitly selected real project/work/evidence sources. These API actions are implemented, but their feature flags and migrations remain disabled pending integration; they cannot yet be claimed available in a running product.

No actual contract, acceptance, warranty activation, SLA, field completion, procurement, billing or renewal execution has been performed.
