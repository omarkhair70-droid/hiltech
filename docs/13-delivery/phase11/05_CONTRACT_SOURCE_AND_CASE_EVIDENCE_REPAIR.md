# Phase 11 follow-through: evidence chronology, document requirement truth, P10 source adapter

## Handover
P11A readiness now checks two independent facts for every required DOCUMENT:
1. The authoritative `work_requirement_instance` satisfaction is SATISFIED.
2. A **current**, released/approved `work_document_revision` backed by READY Evidence exists for that WorkOrder and requirement key.

Approved document revision is NOT allowed to conceal PENDING/BLOCKED requirements. WAIVED and NOT_APPLICABLE still appear explicitly as handover blockers until a separate HILTECH policy actually approves that exception; an internal Work readiness waiver does not automatically authorize client handover. This policy is test-covered.

The versioned source digest still incorporates Project version, WorkOrder lifecycle/version, required DOCUMENT status/version, and current approved document revisions/Evidence hashes; A2 rejects stale review and superseded package versions.

## Support repair chronology and source choices
The B3 internal case source proof must:
- point to a matching original Project WorkOrder with ACCEPTED or CLOSED state;
- be READY Evidence scoped to the same WorkOrder + HILTECH organization;
- have capture time **on or after ticket opening**; old installation tests/images are not remediation proof for a later service complaint;
- not be superseded by newer READY Evidence.

These validations are duplicated in P11B3 SQL trigger and the server's JDBC evidence gate. Both historical title and current derived internal case state are shown separately.

Added authenticated manager-only `GET /v1/projects/{projectId}/service-tickets/{ticketId}/case/work-choices` and `/ready-evidence`: the KMP/Compose client loads existing WorkOrders and eligible source Evidence, not guessed IDs. This remains INTERNAL ONLY, no customer message, no warranty/financial side effect.

## P10 signed commercial source boundary
Phase 10 currently contains a *draft* signed-source view called `commercial_support_contract_source`. P11 can safely read it when it exists, or respond `P10_SOURCE_NOT_DEPLOYED` when absent, without any Phase11 hard foreign-key dependency. The read-only P11 service source endpoint is:
`GET /v1/projects/{projectId}/service-contract-source`.
Its controller is behind a **new OFF-by-default** `hiltech.phase11.contract.enabled=true` flag, never automatically enabled.

Manager-only projection joins the actual P10 handoff, client PO verification flag and READY storage metadata for contract/PO Evidence. This is NOT canonical P10 SALES_AWARD verification: that gate additionally validates target/id/type, upload session, security scan, confidential classification and authorized business approval. The source is labelled SIGNED_RECORD_STORAGE_READY_AWARD_NOT_REVALIDATED, not legally verified. Source pointers and date/term-presence indicators are shown without exposing term text, object storage URLs or amounts. It does **not** turn effective/end dates into warranty duration, a maintenance recurrence, SLA commitments or renewal eligibility. Those return NOT_EVALUATED/NOT_CONFIGURED/NOT_APPROVED until an explicit approved business policy and human service authorization exist. This design intentionally fails closed even if P10's evidence is READY.

New shared native KMP API + Compose `ServiceContractSourceSection`, plus a Handover navigation callback, can be wired by the shared UX host.

## Tests and rollout
- Add `ServiceContractSourcePolicyTest` (nonexistent P10, unverified signed Evidence, verified metadata while entitlements stay off), plus documented handover satisfaction/waiver tests.
- Extend `Phase11NativeContractTest`: authenticated actual Work/Evidence choices; read-only signed source never activates warranty.
- Exact-head GH Actions must compile tests and run disposable PostgreSQL staging SQL/negative proofs, desktop and Android build.
- Do NOT merge P10/Phase11 Flyway files, flip flags, issue a client document, start warranty, send messages, reserve parts, post invoices or create renewals based solely on this source projection.
- Recheck P10 schema at integration time; this adapter relies on the *draft* P10 view and its evidence links. If the provider contract changes, update the adapter and its tests before deployment.
