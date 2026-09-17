# HILTECH Object × Action Permission Matrix

Status: DOMAIN SECURITY MODEL v0.1 / NOT FROZEN

Legend:
- YES = normally allowed in authorized context
- OWN = own/self only
- ASSIGNED = assigned project/site/work only
- POLICY = policy/threshold/delegation determines
- TECH = technical authority only
- VIEW = read only
- NO = denied by default

This matrix refines the coarse role matrix. Final rules may be relationship-based rather than role-only.

---

# Project

| Action | Owner | Finance | PM | Engineer | Supervisor | Technician | Warehouse | Procurement | HR | Sales | Client |
|---|---|---|---|---|---|---|---|---|---|---|---|
| View | YES | CONTEXT | ASSIGNED | ASSIGNED | ASSIGNED | ASSIGNED | CONTEXT | CONTEXT | CONTEXT | CONTEXT | OWN ORG |
| Create from award | POLICY | NO | POLICY | NO | NO | NO | NO | NO | NO | YES | NO |
| Change baseline | POLICY | CONTEXT | POLICY | TECH input | NO | NO | NO | NO | NO | COMMERCIAL input | APPROVAL only |
| Put on hold | POLICY | NO | POLICY | NO | NO | NO | NO | NO | NO | NO | NO |
| Close | POLICY | CONTEXT | POLICY | TECH acceptance | NO | NO | CONTEXT | CONTEXT | NO | NO | ACCEPTANCE only |
| View internal cost | YES | YES | POLICY | NO | NO | NO | POLICY | CONTEXT | NO | POLICY | NO |

---

# Work Order

| Action | Owner | PM | Engineer | Supervisor | Technician | Warehouse | Client | Subcontractor |
|---|---|---|---|---|---|---|---|---|
| View | YES | ASSIGNED | ASSIGNED | ASSIGNED | ASSIGNED | CONTEXT | CLIENT-VISIBLE only | ASSIGNED |
| Create | POLICY | YES | TECH candidate | POLICY | NO | NO | NO | NO |
| Assign | POLICY | YES | NO | POLICY | NO | NO | NO | NO |
| Start | NO | NO | ASSIGNED if executor | ASSIGNED if executor | ASSIGNED | NO | NO | ASSIGNED |
| Block | NO | YES | ASSIGNED | ASSIGNED | ASSIGNED | CONTEXT | NO | ASSIGNED |
| Submit complete | NO | NO | ASSIGNED | ASSIGNED | ASSIGNED | NO | NO | ASSIGNED |
| Accept | POLICY | POLICY | TECH | POLICY | NO | NO | client acceptance only if configured | NO |
| Request rework | POLICY | POLICY | TECH | POLICY | NO | NO | NO | NO |
| Cancel | POLICY | YES | NO | POLICY | NO | NO | NO | NO |

---

# Asset

| Action | Owner | Finance | PM | Engineer | Supervisor | Technician | Warehouse | Procurement | Client |
|---|---|---|---|---|---|---|---|---|---|
| View asset passport | YES | CONTEXT | CONTEXT | ASSIGNED | ASSIGNED | OWN/ASSIGNED | YES | CONTEXT | CLIENT-OWN |
| Reserve | POLICY | NO | REQUEST/POLICY | REQUEST | REQUEST | REQUEST | YES | NO | NO |
| Checkout | POLICY | NO | REQUEST | REQUEST | POLICY | OWN/REQUEST | YES | NO | NO |
| Return | POLICY | NO | CONTEXT | CONTEXT | POLICY | OWN | YES | NO | NO |
| Transfer | POLICY | NO | CONTEXT | CONTEXT | POLICY | OWN/REQUEST | YES | NO | NO |
| Report damage | YES | NO | YES | YES | YES | OWN | YES | NO | CLIENT-OWN issue only |
| Mark lost | POLICY | CONTEXT | NO | NO | REPORT | REPORT | REPORT | NO | NO |
| Approve write-off/retire | POLICY | POLICY | NO | NO | NO | NO | REQUEST | NO | NO |
| Edit acquisition value | POLICY | YES | NO | NO | NO | NO | POLICY | CONTEXT | NO |
| Calibration/maintenance update | POLICY | CONTEXT | VIEW/REQUEST | TECH | CONTEXT | VIEW | POLICY | NO | NO |

---

# Stock / Inventory

| Action | Owner | Finance | PM | Supervisor | Technician | Warehouse | Procurement |
|---|---|---|---|---|---|---|---|
| View quantity | YES | CONTEXT | CONTEXT | CONTEXT | assigned need only | YES | YES |
| Reserve | POLICY | NO | REQUEST | REQUEST | REQUEST | YES | CONTEXT |
| Issue | POLICY | NO | REQUEST | POLICY | RECEIVE/CONFIRM | YES | NO |
| Consume | NO | NO | CONTEXT | YES | ASSIGNED | CONFIRM | NO |
| Return | NO | NO | CONTEXT | YES | ASSIGNED | YES | NO |
| Adjust | POLICY | CONTEXT | NO | NO | NO | REQUEST | NO |
| Approve adjustment | POLICY | POLICY | NO | NO | NO | POLICY if delegated | NO |

---

# Purchase Order

| Action | Owner | Finance | PM | Engineer | Warehouse | Procurement | Supplier |
|---|---|---|---|---|---|---|---|
| View | YES | YES | CONTEXT | technical context | receiving context | YES | OWN |
| Create draft | POLICY | CONTEXT | REQUEST | NO | NO | YES | NO |
| Edit draft | POLICY | CONTEXT | CONTEXT | TECH input | NO | YES | NO |
| Approve | POLICY | POLICY | POLICY | TECH only | NO | POLICY | NO |
| Issue | POLICY | NO | NO | NO | NO | YES | NO |
| Confirm | NO | NO | NO | NO | NO | VIEW | OWN |
| Amend | POLICY | CONTEXT | CONTEXT | TECH input | NO | YES | ACKNOWLEDGE |
| Receive | NO | NO | CONTEXT | NO | YES | CONTEXT | NO |
| Cancel | POLICY | CONTEXT | CONTEXT | NO | NO | POLICY | NO |

---

# Payroll Run

| Action | Owner | Finance | HR | PM | Employee |
|---|---|---|---|---|---|
| View run summary | POLICY | YES | CONTEXT | NO | NO |
| Prepare | NO | YES | INPUT only | NO | NO |
| Edit draft | NO | YES | authorized input only | NO | NO |
| Review exceptions | POLICY | YES | CONTEXT | NO | NO |
| Approve exact version | POLICY | POLICY if authorized | NO unless explicit | NO | NO |
| Execute payment handoff | POLICY | POLICY | NO | NO | NO |
| Reconcile | VIEW | YES | NO | NO | NO |
| View payslip | NO | CONTEXT | CONTEXT | NO | OWN |
| Create correction run | POLICY | YES | INPUT | NO | NO |

---

# Payment

| Action | Owner | Finance | HR | PM | Supplier/Employee/Client |
|---|---|---|---|---|---|
| View payment context | POLICY | YES | CONTEXT payroll only | CONTEXT project only | OWN status only |
| Prepare | NO | YES | NO | NO | NO |
| Approve | POLICY | POLICY | NO | NO | NO |
| Submit bank execution | POLICY | POLICY | NO | NO | NO |
| Reconcile | VIEW | YES | NO | NO | NO |
| Retry after explicit failure | POLICY | YES | NO | NO | NO |
| Retry UNKNOWN outcome | NO automatic; reconciliation required | POLICY after verification | NO | NO | NO |

---

# Employee

| Action | Owner | Finance | HR | PM | Supervisor | Employee |
|---|---|---|---|---|---|---|
| View basic work profile | YES | CONTEXT | YES | ASSIGNED | ASSIGNED | OWN |
| View personal docs | POLICY | limited finance-needed | YES | NO | NO | OWN |
| View salary/bank data | POLICY | YES | POLICY | NO | NO | OWN |
| Change role/team | POLICY | NO | YES/POLICY | REQUEST | NO | NO |
| Activate | POLICY | NO | YES/POLICY | NO | NO | NO |
| Start offboarding | POLICY | CONTEXT | YES/POLICY | REQUEST | NO | NO |
| Complete offboarding | POLICY | CONTEXT clearance | YES/POLICY | project clearance | asset/work clearance | NO |

---

# Support Ticket

| Action | Owner | PM | Engineer | Supervisor | Technician | Client | Finance |
|---|---|---|---|---|---|---|---|
| View | YES | assigned/client project | assigned | assigned | assigned | OWN ORG | billing context only |
| Create | YES | YES | YES | YES | YES | YES | YES if finance issue |
| Triage | POLICY | YES/POLICY | TECH candidate | POLICY | NO | NO | NO |
| Assign | POLICY | YES | NO | POLICY | NO | NO | NO |
| Resolve | POLICY | POLICY | TECH | POLICY | submit work only | NO | finance resolution only |
| Close | POLICY | POLICY | NO | POLICY | NO | POLICY if configured | NO |
| Reopen | YES | YES | YES | YES | YES | YES | YES |

---

# Document

| Action | Internal Owner Roles | Field | Client | Supplier/Subcontractor |
|---|---|---|---|---|
| View | POLICY/context | assigned required docs | client-visible only | assigned only |
| Upload | POLICY | evidence/document context | limited client uploads | assigned workflow |
| Create new version | POLICY | NO except field evidence type | NO unless requested | supplier quote/doc only |
| Approve | POLICY | TECH if technical doc | client approval if configured | NO |
| Share externally | POLICY | NO | NO beyond org | NO |
| Download offline | POLICY | assigned required docs | allowed docs | allowed docs |

---

# Critical Security Rules

1. Backend enforces all permissions.
2. Self/assigned/context relationships are re-evaluated server-side.
3. Critical action may add obligations:
   - online
   - re-auth
   - MFA
   - approval
   - reason
4. External users are organization-scoped.
5. Hiding UI is not permission enforcement.
6. Offboarding/revocation must remove access even if device was previously offline.

## Freeze gate
Requires validation with actual HILTECH authority and conversion into authorization policy/tests.
