# Phase 11C | internal visit draft vertical — integration handoff

Status: IMPLEMENTED AS CODE / NOT VERIFIED / feature OFF. Source branch: phase11/handover-service-20261122. Design authority: PR #161 and existing Project/WorkOrder/Field Evidence.

## Actually added
- Draft maintenance visit linked to an already-existing WorkOrder. Project, organization, site and WorkOrder are canonical, not copied or separately created.
- The manager enters a proposed timestamp explicitly. The server compares against its clock and snapshots the exact WorkOrder version; no recurrence interval or promised appointment is assumed.
- The source checklist is read directly from work_checklist_item_instance. No second checklist template, no generated check statuses, no proof of visit completion.
- An evidence-linked internal finding may be recorded only when that same WorkOrder is ACCEPTED/CLOSED and evidence is READY under that WorkOrder and organization. Database trigger independently enforces the evidence relationship. No claim the entire visit is completed.
- Native shared KMP client and Compose section are available for Android/Windows; integrator must connect authenticated navigation and real selected WorkOrders on separate shared-shell owner branch.
- All create commands use Idempotency-Key, project management authorization, audit, deterministic identifiers and a source-state guard. Both DB tables are append-only and records remain internal.
- Controller is OFF by default; enable only after SQL, permission and operator gates.

## Not implemented / must not be inferred
- Approved maintenance contract coverage, warranty dates, contractual SLA clocks, automatic recurrence/advance scheduling, committed site visit/assignment, executed field task, mandatory visit checklist template, service approvals, parts reservation/requests, invoicing, renewal generation and external client communication.
- Those depend on validated source P10 contract version, company procedures, P7 procurement source and the already existing WorkOrder/Asset/Field infrastructure; do not create duplicate commercial, procurement, asset or evidence modules.
- Review, accept/reject and issue of handover packages remain blocked until real HILTECH sample and confirmed acceptance/share authority. Existing P11A is draft-only.
- Support ticket triage/assignment/reopen/SLA remain blocked pending contract/approval rules. Existing P11B is an internal OPEN ticket + private-note ledger.

## DevOps integration tests
1. Reconcile actual P7 V0039–V0059 and sibling P8–P10 migrations; assign a unique Flyway version to database/pending/phase11/11C_maintenance_visits.sql.
2. Test clean baseline and forward-only upgrade with real PostgreSQL, including cross-project/site/organization rejection, immutable history and foreign-work evidence.
3. Test exact-head server compile and unit tests, KMP Android/desktop compilation, auth revocation, stale WorkOrder/version and idempotent replay.
4. Use authorized Project manager and real current WorkOrder on a staging client. Check that linked checklist and READY evidence appear only with allowed project access and nothing is client-visible.
5. Do not enable hiltech.phase11.maintenance.enabled unless these tests pass. No automatic field execution, stock movement or financial effects.

Recent earlier Phase 11A–11B PR head f6579bfc had DevOps server package proof and five other relevant jobs PASS; Bootstrap/Phase6 were not all complete at review time. These results do NOT test the new 11C code.
