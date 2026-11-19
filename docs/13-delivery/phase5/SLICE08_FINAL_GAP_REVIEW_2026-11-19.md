# Phase 5 / Slice 08 — Stocktake / Discrepancy / Adjustment — Final Gap Review

Date: 2026-11-19

Status: **BLOCKED / OWNER REALITY + APPROVAL CONTRACT REQUIRED / DO NOT MERGE / DO NOT START SLICE 09**

Canonical frozen contract:

`docs/13-delivery/phase5/12_SLICE08_STOCKTAKE_ADJUSTMENT_CONTRACT_2026-09-21.md`

Reviewed exact head:

`a5a29b3163ac005cc2a0f53142a8a3c200158151`

PR:

`#110 — Phase 5 / Slice 08 — Stocktake / Discrepancy / Adjustment`

---

## 1. Verified production shape before the privileged approval boundary

The exact reviewed head implements and proves:

- forward-only V0033 Stocktake / discrepancy / adjustment-request source truth;
- Warehouse/StorageLocation-scoped Stocktake creation;
- immutable authoritative snapshot quantities and source versions;
- online physical count with canonical unit validation;
- append-only recount history through `supersedes_count_record_id`;
- deterministic terminal recount selection in application and PostgreSQL guard;
- submit only after every snapshot line has a physical count;
- exact variance as `counted - expected snapshot`;
- one deterministic InventoryDiscrepancy for each non-zero variance;
- explicit `source_changed_since_snapshot` reconciliation context;
- no count or submit path directly mutates StockBalance;
- adjustment request with exact discrepancy version, reason, exact delta and canonical unit;
- request authority through current PostgreSQL binding plus OpenFGA `warehouse#can_request_adjustment`;
- unauthorized requester fail-closed;
- post-snapshot authoritative inventory movement blocks adjustment request with `STOCKTAKE_RECONCILIATION_REQUIRED`;
- idempotent adjustment-request replay with no duplicate request;
- authenticated frozen HTTP routes through request-adjustment;
- shared Android/Windows KMP DTO and Ktor client;
- shared Windows/Android Stocktake operator surface;
- visible count/recount history, variance and adjustment-request/approval-reference status;
- Windows human render proof;
- Android production-control human proof for count -> recount -> submit variance -> request adjustment;
- truthful missing approval state: no ApprovalRequest/approver/policy is fabricated.

No Slice 09 Command Center / Quick Scan behavior was pulled forward.

---

## 2. Exact-head verification

Final reviewed workflow conclusion on `a5a29b3163ac005cc2a0f53142a8a3c200158151`:

- Bootstrap Phase 0 — **PASS**
- Phase 1 — Native OIDC Production Smoke — **PASS**
- Phase 2 — Shared Command Runtime — **PASS**
- Phase 3 — Onboarding Human Proof — **PASS**
- Phase 3 — Assignment Change Human Proof — **PASS**
- Phase 3 — Offboarding Human Proof — **PASS**
- Phase 4 — Project Site Core — **PASS**
- Phase 4 — Project Planning Structure — **PASS**
- Phase 4 — WorkOrder Policy Binding — **PASS**
- Phase 4 — Readiness + WorkAssignment — **PASS**
- Phase 4 — Review + Progress + Health — **PASS**
- Phase 4 — Assigned Work Mobile Handoff — **PASS**
- Phase 5 — Warehouse Setup — **PASS**

Phase 5 specifically proves:

- Warehouse service/shared-client contract — PASS;
- Windows Stocktake count-history render — PASS;
- Windows Stocktake adjustment-request/status render — PASS;
- Android debug evidence APK — PASS;
- inherited Asset / handoff / Receiving Android proofs — PASS;
- Android Stocktake count / recount / variance / adjustment-request proof — PASS.

The Phase 1 Android job required bounded retries for external runner/dependency failures: one Android Emulator package download failure and one Maven Central HTTP 429. The final exact-head workflow is PASS without source repair.

---

## 3. Frozen obligations still not implemented

The frozen Slice 08 contract still requires the privileged final correction boundary:

`adjustment request -> authority/approval -> append-only ADJUSTMENT StockMovement -> balance projection update -> close/link discrepancy`

The reviewed head intentionally stops before this boundary.

Still absent:

1. production canonical Approval linkage for Inventory Adjustment;
2. `POST /v1/inventory-adjustments/{id}/apply`;
3. approved exact-once `ADJUSTMENT` StockMovement;
4. negative-resulting-stock denial on the adjustment path;
5. persistence of applied movement / applied actor / applied time on the adjustment request;
6. close/link of the discrepancy after application;
7. complete Stocktake reconciliation/closure transition after discrepancy resolution;
8. production approval status driven from canonical Approval truth rather than a null placeholder.

These are Slice 08 gaps and therefore block merge.

---

## 4. Owner reality gate — official adjustment authority mapping

The Phase-5 decision register explicitly retains this seed/process gate:

**official warehouse responsibility/approval mapping**

The authorization matrix is equally explicit:

- requester/counter does not gain final approval from Warehouse Operator authority;
- final adjustment requires `INVENTORY_ADJUSTMENT_APPROVER`;
- Warehouse Manager may approve only if separately bound;
- no seed assignment is allowed until the actual Warehouse Manager / Operator / Adjustment Approver mapping is confirmed from HILTECH reality.

Therefore this review cannot assign Mohamed, Ahmed, Omar, a Warehouse Manager, or any other named person/team as adjustment approver.

Required owner reality input:

- which current USER or TEAM is granted `INVENTORY_ADJUSTMENT_APPROVER` for each Warehouse / initial production Warehouse;
- whether any current Warehouse Manager is deliberately also granted this separate authority.

Until that mapping exists, final application must remain unavailable.

---

## 5. Approval integration contract gap

Phase 2 provides the canonical Approval engine foundation, but production domain-specific Approval policy/configuration remains phase-local.

The repository currently does **not** freeze or seed the Inventory Adjustment values required to create a canonical ApprovalRequest, including:

- the production ApprovalPolicy key/version used by Inventory Adjustment;
- the canonical Approval subject type for an InventoryAdjustmentRequest;
- exact subject-version semantics for approval supersession;
- canonical reason code / safe-summary mapping from the adjustment reason;
- how the InventoryAdjustmentRequest stores/refreshes canonical approval request ID + terminal status;
- the domain subject-version guard that allows the Approval engine to decide this subject while failing closed on stale versions.

The existing Approval engine production subject guard is fail-closed. Reusing test-only `EXCEPTION_OWNER_FINAL`, inventing `OWNER_FINAL`, or bypassing the canonical engine would violate the frozen contracts.

Required controlled clarification:

1. freeze the Inventory Adjustment ApprovalPolicy key and required mode;
2. freeze subject type/version semantics;
3. bind policy authority to `INVENTORY_ADJUSTMENT_APPROVER`;
4. confirm the real authority binding from HILTECH reality;
5. then implement canonical request/link/decision consumption.

---

## 6. Discrepancy / Stocktake terminal lifecycle gap

The frozen model contains:

Stocktake:
- SUBMITTED;
- RECONCILING;
- CLOSED.

Discrepancy:
- OPEN;
- INVESTIGATING;
- RESOLVED_NO_ADJUSTMENT;
- ADJUSTMENT_REQUESTED;
- CLOSED.

Current production commands reach SUBMITTED / OPEN / ADJUSTMENT_REQUESTED.

The frozen API list does not define a separate discrepancy-investigation or resolve-no-adjustment command route, and the contract does not specify:

- who may resolve a discrepancy with no adjustment;
- required reason/evidence for that resolution;
- exact transition into `RECONCILING`;
- exact rule for closing the Stocktake when multiple discrepancies have mixed outcomes;
- whether closure is automatic after every discrepancy is terminal or an explicit authorized command.

Do not invent these transition semantics or add an uncontracted endpoint merely to make the enum reachable.

This narrow transition contract must be clarified together with final adjustment application so the reconciliation lifecycle closes once and from authoritative outcomes.

---

## 7. What may be implemented after the gate is frozen

Once the owner reality + approval contract above are frozen, Slice 08 can close with one coherent privileged-correction vertical:

1. create/link canonical ApprovalRequest for the exact InventoryAdjustmentRequest;
2. preserve separation of duty and current authority revalidation;
3. canonical Approval decides;
4. `POST /v1/inventory-adjustments/{id}/apply` requires:
   - exact request version;
   - canonical APPROVED outcome for the exact subject version;
   - current explicit `INVENTORY_ADJUSTMENT_APPROVER`;
   - exact discrepancy/request delta;
   - current balance/version reconciliation;
   - no negative resulting StockBalance;
5. append exactly one `ADJUSTMENT` StockMovement;
6. project StockBalance only from that movement;
7. record applied movement/actor/time;
8. close/link discrepancy;
9. move Stocktake reconciliation lifecycle according to the frozen terminal rule;
10. audit/event;
11. prove adjustment-without-approval denied and approved replay produces exactly one movement;
12. expose canonical approval/application status in Windows;
13. run Final Gap Review again on the corrected exact head;
14. obtain independent verification before merge.

---

## 8. Final decision

Slice 08 is **not merge-ready**.

The implementation before the final approval/apply boundary is verified and green.

The remaining blocker is not a coding-performance problem and must not be papered over with a guessed approver/policy.

`PHASE5_SLICE08_PRE_APPROVAL_VERTICAL = VERIFIED`

`PHASE5_SLICE08_FINAL_GAP_REVIEW = BLOCKED`

`PHASE5_SLICE08_MERGE_AUTHORIZED = NO`

`PHASE5_SLICE09_AUTHORIZED = NO`

`OWNER_REALITY_REQUIRED = OFFICIAL_WAREHOUSE_RESPONSIBILITY_APPROVAL_MAPPING`

`CONTRACT_CLARIFICATION_REQUIRED = INVENTORY_ADJUSTMENT_APPROVAL_SUBJECT_POLICY_AND_TERMINAL_RECONCILIATION`

`NEXT = FREEZE_OWNER_REALITY_AND_APPROVAL_CONTRACT_THEN_IMPLEMENT_PRIVILEGED_ADJUSTMENT_APPLICATION`


---

## Owner clarification addendum — 2026-11-19

The prior owner-reality blocker is resolved. Inventory adjustment approval is not tied to Mohamed, Ahmed, Omar or another named person. The current Warehouse INVENTORY_ADJUSTMENT_APPROVER is canonical and may be a USER or TEAM; multiple humans are represented by one TEAM for the current SINGLE Approval contract.

SLICE08_INVENTORY_ADJUSTMENT_APPROVAL_DECISION_2026-11-19.md freezes the controlled subject/policy/authority contract. The remaining blocker is implementation/verification only: approved exact-once ADJUSTMENT application, negative-result denial, applied linkage, discrepancy closure and terminal Stocktake reconciliation. Merge remains unauthorized until those gaps and the repeated Final Gap Review are complete.


---

## Final re-review — PASS / MERGE READY SUBJECT TO CLOSURE-HEAD CI

Implementation head reviewed: `138632e1a68a6039bced7794461a94cb1e3619b8`

All fourteen triggered workflows are PASS, including Phase 5 Warehouse server/shared tests, Windows Stocktake applied-state render, Android Stocktake count/recount/variance/request/approved-apply evidence, Bootstrap, Approval Stability, Native OIDC and inherited Phase 3/4 regressions.

### Frozen contract coverage

- all five Slice 08 persistence records exist in V0033;
- count/recount is append-only and never edits StockBalance;
- snapshot expected quantities and source versions are retained;
- variance is deterministic as counted minus expected;
- stale post-snapshot inventory becomes reconciliation context and cannot silently apply;
- non-zero discrepancy creates auditable adjustment context;
- adjustment request preserves exact discrepancy/item/location/delta/reason/requester;
- canonical Approval subject/policy is linked to the request;
- requester/counter authority never silently becomes final authority;
- final application revalidates current `INVENTORY_ADJUSTMENT_APPROVER`;
- PENDING/no Approval cannot apply;
- negative available Stock cannot be produced by adjustment;
- approved application appends exactly one `ADJUSTMENT` StockMovement;
- StockBalance changes only through the Stock ledger projection;
- applied movement/actor/time are linked to the request;
- discrepancy closes and the Stocktake becomes CLOSED when no unresolved discrepancy remains, otherwise RECONCILING;
- command replay does not create a second movement;
- all eight frozen HTTP routes exist;
- shared Android/Windows client uses the authenticated idempotent transport;
- Windows shows count history, variance, canonical approval state and final applied movement/reconciliation;
- Android evidence drives the production shared count/recount/request/approved-apply controls online.

### Terminal lifecycle clarification

The frozen persistence model also names `INVESTIGATING`, `RESOLVED_NO_ADJUSTMENT` and `CANCELLED`. The frozen API list provides no separate transition route for those paths and the authorization matrix freezes no authority/reason contract for a no-adjustment resolution command. They therefore remain reserved lifecycle states rather than an invented production command. The required adjustment path closes through authoritative APPROVED application and the implemented reconciliation rule.

### Independent review

PR #110 has no open Conversation comments or inline review threads at this review point. A separate contract-to-code verification pass is recorded in `SLICE08_INDEPENDENT_VERIFICATION_2026-11-19.md` and reports no remaining Slice 08 blocker.

`PHASE5_SLICE08_PRE_APPROVAL_VERTICAL = VERIFIED`

`PHASE5_SLICE08_APPROVAL_APPLICATION = VERIFIED`

`PHASE5_SLICE08_WINDOWS_ANDROID_PROOF = VERIFIED`

`PHASE5_SLICE08_FINAL_GAP_REVIEW = PASS`

`PHASE5_SLICE08_INDEPENDENT_VERIFICATION = PASS`

`PHASE5_SLICE08_MERGE_AUTHORIZED = AFTER_CLOSURE_HEAD_CI_PASS`

`PHASE5_SLICE09_AUTHORIZED = NO_UNTIL_SLICE08_POST_MERGE_VERIFICATION`
