# Phase 5 — Authorization Matrix

Date: 2026-09-21  
Status: **AUTHORIZATION CONTRACT v0.1 / FROZEN FOR PHASE-5 SLICE PLANNING**

## 1. Principle

Phase 5 reuses the existing authorization architecture:

- PostgreSQL current-source authority/binding truth;
- OpenFGA relation projection;
- current organization/team/user source guards;
- fail closed when source truth is stale/unavailable.

Do not authorize warehouse actions from a job-title string alone.

Do not hard-code named HILTECH people.

The currently reported company structure informs seed data only.

---

# 2. Existing OpenFGA foundation

The current model already contains:

## warehouse

Relations:

- operator;
- manager;
- viewer.

Current permissions include:

- can_view_inventory;
- can_receive;
- can_checkout;
- can_return;
- can_transfer;
- can_count;
- can_manage.

## storage_location

Relations:

- warehouse;
- project;
- site;
- responsible;
- operator;
- viewer.

This is a valid starting point but too coarse for final Phase-5 authority.

Phase 5 extends it without discarding the existing model.

---

# 3. Current-source binding strategy

## WarehouseAuthorityBinding

Phase 5 should add effective-dated PostgreSQL authority bindings following the proven People/Project pattern.

Fields:

- id;
- organizationId;
- warehouseId;
- authorityKey;
- principalType: USER / TEAM;
- principalUserId?;
- principalTeamId?;
- effectiveFrom;
- effectiveTo?;
- active;
- createdBy;
- createdAt;
- version.

Authority keys:

- WAREHOUSE_MANAGER;
- WAREHOUSE_OPERATOR;
- WAREHOUSE_VIEWER;
- ASSET_MASTER_MANAGER;
- INVENTORY_ADJUSTMENT_APPROVER;
- INVENTORY_VALUE_VIEWER.

One person/team may hold multiple keys.

A manager does not automatically receive every sensitive capability unless the FGA relation explicitly composes it.

## StorageLocationAuthorityBinding

For location-specific authority where needed:

- id;
- organizationId;
- storageLocationId;
- authorityKey;
- principal USER / TEAM;
- effective dates;
- active/version.

Candidate keys:

- LOCATION_RESPONSIBLE;
- LOCATION_OPERATOR;
- LOCATION_VIEWER.

Warehouse-linked locations may inherit Warehouse relations.

Project/Site temporary locations may derive visibility from Project/Site context while still requiring explicit operational authority for physical movement.

---

# 4. Current-source guard

Before any sensitive Phase-5 command succeeds:

1. authenticated identity must be ACTIVE;
2. current organization membership must exist;
3. USER binding must still be current, or TEAM binding must resolve through current Team membership;
4. relevant Warehouse/StorageLocation binding must be current;
5. OpenFGA permission must pass;
6. object state/version/invariants must pass.

If PostgreSQL binding is ended/replaced, deny immediately even if an OpenFGA tuple is stale.

This matches the Phase-3/4 fail-closed pattern.

---

# 5. Capability matrix

Legend:

- YES — directly authorized by capability.
- CONTEXT — visible/actionable only for current Project/Work/custody context.
- REQUEST — can request but not apply physical inventory mutation.
- NO — not granted by this capability.
- APPROVAL — requires separate explicit approval authority/policy.

| Action | Warehouse Operator | Warehouse Manager | Asset Master Manager | Adjustment Approver | Project PM/Engineer/Supervisor | Technician / Current Custodian | Inventory Viewer | Value Viewer |
|---|---|---|---|---|---|---|---|---|
| View normal inventory | YES | YES | YES | CONTEXT | CONTEXT | CONTEXT | YES | CONTEXT |
| View restricted acquisition/value | NO | NO by default | NO by default | NO by default | NO | NO | NO | YES |
| View Project resource readiness | CONTEXT | CONTEXT | CONTEXT | NO | YES | CONTEXT | CONTEXT | NO |
| Create Warehouse/StorageLocation | NO | YES | NO | NO | NO | NO | NO | NO |
| Retire StorageLocation | NO | YES | NO | NO | NO | NO | NO | NO |
| Create/edit StockItem master | NO | YES or delegated | YES | NO | NO | NO | NO | NO |
| Create/register Asset master | NO unless delegated | YES or delegated | YES | NO | NO | NO | NO | NO |
| Issue/replace Asset tag | YES where policy permits | YES | YES | NO | NO | NO | NO | NO |
| Receive goods | YES | YES | NO unless delegated | NO | NO | NO | NO | NO |
| Reserve Asset/Stock operationally | YES | YES | NO unless delegated | NO | REQUEST | NO | NO | NO |
| Request Project resources | NO | CONTEXT | NO | NO | REQUEST | CONTEXT request only where Work policy later permits | NO | NO |
| Checkout Asset | YES | YES | NO unless operator | NO | NO | NO | NO | NO |
| Issue Stock | YES | YES | NO unless operator | NO | NO | NO | NO | NO |
| Accept ReturnAsset | YES | YES | NO unless operator | NO | NO | NO | NO | NO |
| Accept unused Stock return | YES | YES | NO unless operator | NO | NO | NO | NO | NO |
| Transfer Asset/Stock | YES | YES | NO unless operator | NO | REQUEST for Project context | NO final authority | NO | NO |
| Return inspection | YES if trained/bound | YES | YES if bound | NO | NO | report-only context | NO | NO |
| Report damage/missing | YES | YES | YES | NO | CONTEXT | YES for current custody | NO | NO |
| Resolve Asset incident | NO | YES or delegated | YES | APPROVAL if write-off/financial | NO | NO | NO | NO |
| Send for calibration/maintenance | YES | YES | YES | NO | REQUEST | NO final authority | NO | NO |
| Record calibration/service completion | NO unless delegated | YES or delegated | YES | NO | NO | NO | NO | NO |
| Start stocktake/count | YES | YES | NO | NO | NO | NO | NO | NO |
| Record count | YES | YES | NO | NO | NO | NO | NO | NO |
| Request stock adjustment | YES | YES | NO | NO | NO | NO | NO | NO |
| Approve/apply stock adjustment | NO | NO unless separately bound | NO | YES | NO | NO | NO | NO |
| Request Asset retirement/write-off | NO | YES | YES | NO | NO | NO | NO | NO |
| Final retirement/write-off | NO | explicit authority + APPROVAL | explicit authority + APPROVAL | APPROVAL role where policy requires | NO | NO | NO | NO |
| Review movement/history | YES | YES | YES | CONTEXT | CONTEXT | own/current custody subset | YES | CONTEXT |
| Import staging review | delegated | YES | YES | NO | NO | NO | NO | NO |
| Approve opening balance/custody cutover | NO | YES + explicit cutover authority | YES for Assets | NO | NO | NO | NO | NO |

---

# 6. Separation-of-duty rules

## Stock adjustment

Requester/counter does not gain approval merely because they are Warehouse Operator.

Final adjustment requires:

`INVENTORY_ADJUSTMENT_APPROVER`

The same user may hold both roles only if company configuration explicitly grants both.

The product does not silently infer separation or merge it.

## Asset retirement/write-off

Retirement/write-off is not a routine warehouse movement.

It requires explicit authority/ApprovalPolicy where configured.

Warehouse Operator cannot finalize it.

## Restricted value

Acquisition cost/import value is not implied by:

- Warehouse Manager;
- Warehouse Operator;
- PM;
- Technician.

Only explicit `INVENTORY_VALUE_VIEWER` can see it.

This preserves the warehouse/finance boundary.

---

# 7. Project / Work integration

The current Project relation already has:

`can_request_resource`

Project PM / Engineer / Supervisor may:

- request resource need;
- view Project-context resource readiness;
- view Project-context reservation/issue status when authorized.

They do not gain Warehouse checkout/issue authority merely by managing a Project.

Phase-4 WorkRequirement remains the Work source.

Phase-5 reservation/availability satisfies resource facts.

---

# 8. Technician / custodian visibility

Technician/current custodian may see only necessary context:

- Asset identity;
- safe condition/status;
- tag/serial where relevant;
- current custody assigned to them;
- expected return;
- Project/Site/Work context;
- return/damage-report instructions;
- assigned/issued Stock context required for Phase-6 work.

They do not get:

- warehouse-wide inventory;
- unrelated movement history;
- acquisition value;
- stock adjustments;
- master data editing;
- final ReturnAsset authority merely by possessing the Asset.

Possession/custody is not warehouse authority.

---

# 9. External party boundary

Suppliers and clients do not require Phase-5 HILTECH OS login for first production.

Receiving records supplier/source references internally.

Client visibility remains only the subset explicitly exposed by later client/product phases.

No Supplier Portal is introduced in Phase 5.

---

# 10. StorageLocation authority

For Warehouse-linked locations:

- Warehouse authority may inherit into the location.

For Project/Site temporary storage:

- Project/Site relationships may grant visibility;
- physical issue/transfer/count authority still requires explicit storage/warehouse operational authority or an explicitly frozen later policy.

A Project Supervisor must not automatically become inventory-adjustment authority because stock is physically on their Site.

---

# 11. OpenFGA target evolution

Phase 5 should evolve the current Warehouse model toward relations/capabilities equivalent to:

Relations:

- operator;
- manager;
- viewer;
- asset_master_manager;
- adjustment_approver;
- value_viewer.

Permissions:

- can_view_inventory;
- can_view_value;
- can_manage_master;
- can_receive;
- can_reserve;
- can_checkout_asset;
- can_issue_stock;
- can_accept_return;
- can_transfer;
- can_inspect_return;
- can_count;
- can_request_adjustment;
- can_approve_adjustment;
- can_manage_asset_service;
- can_request_retirement.

Do not overload one `can_manage` relation for every high-risk action.

---

# 12. First seed reality

Current reported operating reality suggests:

- Mohamed remains final management oversight;
- Ahmed currently coordinates workers/tools/material movement in practice.

This informs initial configuration review only.

The code and contracts remain role/relation based.

No seed assignment should be committed until the actual Warehouse Manager/Operator/Adjustment Approver mapping is confirmed from HILTECH reality.

---

# 13. Authorization result

`WAREHOUSE_AUTH_SOURCE = POSTGRES_BINDING_PLUS_OPENFGA`

`JOB_TITLE_ONLY_AUTH = REJECTED`

`STALE_FGA_TUPLE_CAN_OVERRIDE_ENDED_BINDING = NO`

`WAREHOUSE_OPERATOR_CAN_ADJUST_FINAL_BALANCE = NO`

`WAREHOUSE_ACCESS_IMPLIES_VALUE_VISIBILITY = NO`

`PROJECT_MANAGER_IMPLIES_WAREHOUSE_OPERATOR = NO`

`CURRENT_CUSTODY_IMPLIES_WAREHOUSE_AUTHORITY = NO`

`PHASE5_AUTHORIZATION_MATRIX = FROZEN_FOR_SLICE_PLANNING`
