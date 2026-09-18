# 05 — Authorization / Policy / Test Matrix

Status: **PRE-FREEZE TEMPLATE**

## Locked Architecture

- Keycloak authenticates subject/session.
- HILTECH resolves product identity/context.
- OpenFGA answers relationship/object-action authorization where applicable.
- application/domain policy enforces state, thresholds, field redaction, re-auth/MFA obligations.
- hiding a UI action is never authorization enforcement.

---

# First-Slice Relationships To Freeze

Exact names may change before freeze.

Need verified relationships for:

- user ↔ HILTECH organization,
- PM ↔ Project,
- technician ↔ WorkOrder,
- supervisor/engineer ↔ WorkOrder review,
- warehouse user ↔ Warehouse,
- WorkOrder ↔ Project/Site,
- Asset ↔ Warehouse/current custody/project/work context,
- temporary Delegation where reality requires it.

---

# Action Policy Matrix

Every action must be frozen as:

| Action | Required relationship | Business precondition | Field obligation | Re-auth? | Online? | Audit | Reality evidence |
|---|---|---|---|---|---|---|---|
| CreateWorkOrder | TBD | project active/context | TBD | TBD | YES | YES | REALITY_REQUIRED |
| AssignWork | TBD | eligible assignee/readiness policy | TBD | TBD | YES | YES | REALITY_REQUIRED |
| StartWork | assigned executor | current assignment/version | least field set | NO candidate | replay allowed | YES | REALITY_REQUIRED |
| SubmitWorkCompletion | assigned executor | evidence/tests complete | TBD | NO candidate | replay allowed | YES | REALITY_REQUIRED |
| AcceptWork | reviewer TBD | exact submitted version | reviewer fields | TBD | YES | YES | REALITY_REQUIRED |
| CancelWork | TBD | reason/policy | TBD | TBD | YES | YES | REALITY_REQUIRED |
| ReserveAsset | TBD | asset fit/available | cost hidden as needed | TBD | YES | YES | REALITY_REQUIRED |
| CheckoutAsset | warehouse authority TBD | version/custody/readiness | restricted cost/location | TBD | YES default | YES | REALITY_REQUIRED |
| ReturnAsset | warehouse receipt TBD | physical identity/condition | TBD | TBD | YES final | YES | REALITY_REQUIRED |

---

# Field-Level Test Families

Must test separately from object visibility:

Project:
- internal cost hidden from technician/client.
- access/security notes least-privilege.
- PM sees only assigned/context projects under policy.

Site:
- technician receives only job-needed access context.
- security-sensitive notes are not broadly cached.

Asset:
- acquisition value hidden from technician.
- custody/location visibility follows context.
- security-correlated history is restricted.

Evidence:
- restricted evidence not downloadable from permanent/public URL.
- external/client visibility requires explicit policy.

---

# Negative Tests Are Mandatory

For every critical allow test, include deny tests:

- unrelated internal employee.
- technician on different project/work.
- PM on different project.
- supervisor not assigned as reviewer.
- warehouse user outside allowed warehouse if scoped.
- client/external organization.
- revoked identity/session/device where applicable.
- expired delegation.
- stale permission after offline reconnect.

---

# Offline Authorization Rule

Permission is re-evaluated server-side on replay.

A command created while authorized may be rejected after reconnect if:
- assignment changed,
- user offboarded/revoked,
- delegation expired,
- object state changed,
- policy changed.

Client must preserve local work/evidence and show an explicit recoverable result where appropriate.

---

# Freeze Output

Before production:
- OpenFGA model file,
- seed/test relationship fixtures,
- server authorization adapter contract,
- application policy obligations,
- field-level projection/filter rules,
- permission integration test suite,
- offline revoked-permission test suite.
