# Phase 7 S07A — Non-stock external-service purchasing

Date: 2026-11-23. Status: DRAFT / ENGINEERING CHECKPOINT / NOT RELEASED.
PR #174 is stacked on #162 (S01–S06).

## Source and safety

External calibration, inspection or specialist maintenance is a service, not a
StockItem. Service delivery must not alter Warehouse stock or fake S04 physical
receiving to reuse its S05 three-way stock invoice matching.

This candidate implements internal demand and technical review only:
- Real active HILTECH organization, Team, team version and requester membership.
- Separate current ApprovalEngine request/review authority; reviewer cannot be
  the same person as requester. All authority keys are unconfigured by default.
- Dedicated immutable root and append-only lifecycle events with PostgreSQL
  team/version/company guards, replay prevention, source and reason constraints.
- DRAFT, SUBMITTED, NEEDS_INFO, READY_FOR_SOURCING, REJECTED and WITHDRAWN.
  Revised scope and acceptance conditions retain historical versions.
- Authenticated server API, idempotent commands, audit trail, typed KMP API and
  a reusable Compose UI for Windows/Android. UI is not mounted in shared shells
  yet; the design and integration owners must coordinate that shared surface.
- Feature configuration hiltech.procurement.service-needs.enabled defaults false.
  Separate request-authority-key and review-authority-key default empty.
- No external supplier sends, service orders, invoices, payable or money movement.

## Verification

S07A GitHub Actions workflow tests the Flyway migration and negative source/
authority inputs against disposable PostgreSQL, plus Windows and Android build.
The migration count test is updated for one additional migration and two tables.
Exact-head CI must be green before labeling the slice engineering-verified.
Genuine cross-role user/device tests, revocation, wrong-org, replay, evidence,
policy and staging recovery are separately required for release.

## Still required before genuine Phase 7 closure

S07B: verified supplier sourcing/quotation Evidence, with current supplier registry.
S07C: canonical approval-backed service order and independently verified dispatch.
S07D: service deliverable/acceptance evidence, partial and rejected outcomes,
without inventing Warehouse receipts or warranty policy.
S07E: non-stock service invoice match (two-way service-order/acceptance source),
immutable mismatch events, and finance-only handoff without payable/payment.
Native UX wiring and acceptance, full server architecture test, ordered merge,
post-merge CI, staging restoration and company policy acceptance.

## Integration and version ownership

Provisional V0061 in PR #174 conflicts with Finance #164 provisional V0061.
DevOps #173 must assign final contiguous migration versions before merging.
DevOps PR #169 owns Procurement/Warehouse cycle repair, not this service branch.
All parent Phase7 PRs are Draft, unmerged and flags OFF. Do not merge #174
before #143, #146, #148, #153, #157, #158 and #162. Integrate and test
the whole source stack before enabling actual business operations.
