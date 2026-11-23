# Phase 11C3 — evidence-bound internal maintenance technical review

Owner: Phase11 engineering, branch phase11/handover-service-20261122, Draft PR #167. This new slice is **pending integration**, not deployed.

## Why this is an actual missing service operation

Previous P11C stored immutable maintenance visit DRAFTs, WorkOrder-derived checklists, READY work evidence, findings and support follow-ups. It did **not** have a recorded outcome that an authorized manager could review and audit without falsely changing the DRAFT to a customer-accepted visit. P11C3 adds that narrow, source-bound record.

## Real source and review contract

- Project, organization, site, WorkOrder lifecycle/version, real checklist and Evidence remain canonical sources; no alternate Project/Asset/WorkOrder/checklist/evidence store is introduced.
- \`GET /v1/projects/{projectId}/maintenance-visits/{visitId}/review/readiness\`: authorized Project-manager-only view of current WorkOrder, required checklist count/pending count, current READY Evidence count (captured no earlier than both planned visit time and visit creation time), latest internal review state and blockers. It does not infer cover or customer acceptance.
- \`GET /.../review/events\`: latest 500 immutable events chronologically within the window; full history remains durable in PostgreSQL.
- \`POST /.../review/events\`: audited/idempotent manager-only \`REVIEW_STARTED\` → \`INTERNAL_TECHNICAL_COMPLETION\` → optionally \`RECHECK_REQUESTED\` → new \`REVIEW_STARTED\` on an actual change. Uses Idempotency-Key matching operationId, expected latest event number and exact current WorkOrder version.
- Confirming internal technical completion requires **accepted or closed canonical WorkOrder**, **at least one required checklist item**, **every required item COMPLETE**, **planned visit time reached**, and **same-work/organization READY Evidence captured after the visit window**, not superseded. These checks run in Kotlin and in the PostgreSQL INSERT source trigger. No direct SQL writer may skip them.
- Review and original visit are immutable; no status field is rewritten. Events are visible only to internal managers. Field technicians can record permitted findings via existing separate role+source gate but cannot confirm a manager review.
- \`hiltech.phase11.maintenance.review.enabled\` must be explicitly TRUE in addition to \`hiltech.phase11.maintenance.enabled\`; both default OFF.
- Shared Kotlin Multiplatform client and standalone Compose PM review section are included. Native shell routing/auth/permission experience belongs to UX/DevOps integration; not a live mounted screen yet.

## Not implied by this source proof

- \`COMPLETED_INTERNAL\` is **not** client acceptance, warranty eligibility, a billable maintenance service, SLA compliance, or a contract-defined preventive schedule. The current WorkOrder may have open findings after technical inspection; resolution must remain in actual Work / Support follow-up.
- No external email, purchase request, invoice, payment, inventory reservation, asset mutation, recurring dates or sales opportunity is created.
- P10 must provide actual verified signed award and written terms; company policy must authorize warranty, client evidence-sharing, acceptance, SLAs, maintenance intervals and renewals. This slice does not guess policy duration, severity mapping, approval actor, financial treatment or next visit date.

## Verification

Dedicated Phase11 workflow runs focused server unit tests, the canonical migration test, ordered staging DDL \`11A → 11A2 → 11B → 11B2 → 11B3 → 11C → 11C2 → 11C3\`, schema source gates and disposable PostgreSQL negative proof (rejects technical completion of IN_PROGRESS WorkOrder even with READY Evidence; rejects mutation of recorded review). Desktop KMP mock-engine client contract tests and Desktop/Android native builds run on the exact source SHA. DevOps independently boots the server on disposable PostgreSQL. **Do not claim new exact-head CI passed until its runs finish.**

For full integrated closure, verify actual accepted work plus READY evidence positive review with Project-manager auth, revocation, concurrent review write, same-organization isolation and native role test; DevOps gives unique conflict-free Flyway numbering after other phase migrations. Do not merge or enable prematurely.
