# HILTECH Field-Level Access Matrix

Status: SECURITY DATA MODEL v0.1 / NOT FROZEN

## Purpose
Object-level VIEW permission is not enough.

A user may be allowed to view an Employee, Project, Asset, PO, Invoice, or Ticket while still being forbidden from specific sensitive fields.

This matrix defines the first-pass field-level visibility rules.

Legend:
- YES = visible in authorized context
- OWN = self only
- POLICY = explicit elevated permission/purpose
- REDACTED = object visible but field hidden/masked
- NO = not visible

---

# Employee

| Field group | Owner | Finance | HR | PM | Supervisor | Employee |
|---|---|---|---|---|---|---|
| displayName / employeeCode | YES | YES | YES | ASSIGNED | ASSIGNED | OWN |
| role / team / manager | YES | YES | YES | ASSIGNED | ASSIGNED | OWN |
| mobile/email | POLICY | POLICY | YES | ASSIGNED if work need | ASSIGNED if work need | OWN |
| legalName | POLICY | POLICY if finance/legal need | YES | NO | NO | OWN |
| identity documents | POLICY | REDACTED unless needed | YES | NO | NO | OWN |
| emergency contact | POLICY | NO | YES | NO | NO | OWN |
| salary/compensation | POLICY | YES | POLICY | NO | NO | OWN current/payslip |
| bank/payment data | POLICY | YES | POLICY | NO | NO | OWN masked/full as safe |
| leave reason/details | POLICY | limited | YES | status/dates only if scheduling need | status/dates only | OWN |
| performance/disciplinary if later | POLICY | NO | POLICY | POLICY limited | NO | OWN policy-dependent |

Rule:
PM/Supervisor should normally know availability, not private reason.

---

# Project

| Field group | Owner | Finance | PM | Engineer | Supervisor | Technician | Client |
|---|---|---|---|---|---|---|---|
| name/code/client | YES | YES | ASSIGNED | ASSIGNED | ASSIGNED | ASSIGNED | OWN ORG |
| lifecycle/health | YES | YES | ASSIGNED | ASSIGNED | ASSIGNED | assigned work context | OWN ORG allowed |
| planned dates | YES | YES | ASSIGNED | ASSIGNED | ASSIGNED | assigned work | client-visible |
| internal budget | YES | YES | POLICY | NO | NO | NO | NO |
| internal cost/margin | YES | YES | POLICY | NO | NO | NO | NO |
| sell value/client price | YES | YES | POLICY | NO unless need | NO | NO | OWN commercial role only |
| payment terms | YES | YES | POLICY | NO | NO | NO | OWN finance/admin role |
| internal risk notes | YES | CONTEXT | YES | technical subset | operational subset | NO | NO |
| client-visible progress | YES | YES | YES | YES | YES | source only | YES |

---

# Site

| Field group | Owner | PM | Engineer | Supervisor | Technician | Client |
|---|---|---|---|---|---|---|
| name/code | YES | ASSIGNED | ASSIGNED | ASSIGNED | ASSIGNED | OWN |
| address/location | YES | ASSIGNED | ASSIGNED | ASSIGNED | ASSIGNED job need | OWN |
| access instructions | POLICY | ASSIGNED | ASSIGNED | ASSIGNED | assigned job need | POLICY |
| security-sensitive notes | POLICY | POLICY | POLICY | POLICY | minimum need | NO by default |
| site contacts | YES | ASSIGNED | ASSIGNED | ASSIGNED | job need | OWN contacts |

---

# Asset

| Field group | Owner | Finance | PM | Engineer | Supervisor | Technician | Warehouse | Client |
|---|---|---|---|---|---|---|---|---|
| assetCode/model/serial | YES | CONTEXT | ASSIGNED | ASSIGNED | ASSIGNED | OWN/ASSIGNED | YES | OWN assets |
| status/condition | YES | CONTEXT | ASSIGNED | ASSIGNED | ASSIGNED | OWN/ASSIGNED | YES | OWN assets allowed |
| location/custodian | YES | CONTEXT | ASSIGNED | ASSIGNED | ASSIGNED | OWN/current job | YES | OWN assets limited |
| purchase/acquisition cost | YES | YES | POLICY | NO | NO | NO | POLICY | NO |
| warranty | YES | CONTEXT | ASSIGNED | ASSIGNED | ASSIGNED | OWN/ASSIGNED | YES | OWN assets |
| calibration/maintenance | YES | CONTEXT | ASSIGNED | YES | CONTEXT | VIEW if relevant | YES | OWN assets allowed |
| movement history | YES | CONTEXT | ASSIGNED | technical context | CONTEXT | OWN custody | YES | client-owned subset |
| security-correlated location | POLICY | NO | NO by default | NO | NO | NO | POLICY | NO |

---

# Supplier Quote / PO

| Field group | Owner | Finance | PM | Procurement | Supplier | Other Supplier | Client |
|---|---|---|---|---|---|---|---|
| supplier identity | YES | YES | CONTEXT | YES | OWN | NO | NO |
| quote price/terms | YES | YES | POLICY | YES | OWN | NO | NO |
| competitor comparison | POLICY | POLICY | POLICY | YES | NO | NO | NO |
| PO price/terms | YES | YES | POLICY | YES | OWN | NO | NO |
| project allocation | YES | YES | ASSIGNED | YES | delivery need subset | NO | NO |
| internal recommendation | POLICY | CONTEXT | POLICY | YES | NO | NO | NO |

---

# Payroll

| Field group | Owner | Finance | HR | Employee | PM/Supervisor |
|---|---|---|---|---|---|
| run totals | POLICY | YES | POLICY | NO | NO |
| employee population | POLICY | YES | POLICY | NO | NO |
| employee salary line | POLICY | YES | POLICY | OWN only | NO |
| deductions/bonuses | POLICY | YES | POLICY | OWN only | NO |
| bank destination | POLICY | YES | POLICY limited | OWN masked/full safe view | NO |
| payment result | POLICY | YES | POLICY | OWN only | NO |
| exception reasons | POLICY | YES | POLICY if HR source | OWN if relevant | NO |

---

# Invoice / Payment

| Field group | Owner | Finance | PM | Procurement | Client | Supplier |
|---|---|---|---|---|---|---|
| invoice amount | YES | YES | POLICY project | supplier invoice context | OWN client invoice | OWN supplier invoice |
| tax/payment terms | YES | YES | POLICY | YES supplier context | OWN | OWN |
| bank account | POLICY | YES | NO | NO | OWN masked if shown | OWN masked if shown |
| payment instruction | POLICY | YES | NO | status only | status only | status only |
| provider correlation/internal bank status | POLICY | YES | NO | NO | NO | NO |
| reconciliation notes | POLICY | YES | NO | NO | NO | NO |

---

# Support Ticket

| Field group | HILTECH Support/PM | Engineer/Field | Client | Finance |
|---|---|---|---|---|
| title/status | YES | ASSIGNED | OWN ticket | billing context |
| client description | YES | ASSIGNED | OWN | limited |
| internal notes | YES | ASSIGNED need | NO | NO |
| client-visible updates | YES | YES source | YES | CONTEXT |
| SLA | YES | CONTEXT | contractual view | CONTEXT |
| internal root cause analysis | YES | TECH | client summary only | NO |
| billable/commercial detail | POLICY | NO | OWN commercial role | YES |

---

# Documents / Evidence

Visibility is intersection of:
1. target object access,
2. document classification,
3. relationship type,
4. clientVisible/externalVisible flag,
5. explicit document permission.

A user must not gain restricted project access merely because they know a document ID.

---

# Camera / Access Security

| Field group | Owner/Security | Warehouse Security Role | Normal Employee | Client |
|---|---|---|---|---|
| camera registry | POLICY | assigned facility | NO | NO |
| live stream | POLICY | assigned facility if allowed | NO | NO |
| access event | POLICY | assigned zone if allowed | OWN event maybe policy | NO |
| credential details | POLICY | NO by default | OWN masked/status | NO |
| security incident | POLICY | assigned context | only if subject/action required | NO |

Highly Restricted.

---

# Masking Rules

Examples:
- bank account: last 4 digits except authorized finance views.
- national ID: masked except HR/legal authorized views.
- credential ID: opaque/redacted.
- salary: never visible in generic employee list.
- internal cost: never included in client DTO.

---

# API Rule

Field-level filtering must happen server-side.

Forbidden:
return full object then hide fields only in UI.

Preferred:
authorized query/view models or serializers that know disclosure policy.

---

# Audit Rule

Selected HIGHLY_RESTRICTED views/exports may themselves be audited:
- payroll details,
- employee identity docs,
- bank data,
- camera/access history,
- bulk export.

## Freeze Gate
Needs real company authority, legal/privacy review, API query design, automated leakage tests, and export policy.
