# HILTECH Offline Classification Matrix

Status: PRODUCT / ARCHITECTURE MODEL v0.1

Classes:
- LF = Local-first write; queues and syncs.
- CR = Cached read.
- OA = Online authoritative.
- ID = Integration-dependent online.
- NA = Not needed offline.
- MIXED = action-specific.

---

| Capability | Class | Notes |
|---|---|---|
| Sign in first time | OA | network required |
| Existing session/app open | CR/MIXED | cached state allowed subject to security |
| Project list assigned | CR | freshness shown |
| Project executive summary | CR | stale indicator required |
| Work/job bundle | CR | prefetched |
| Start work | LF | queued with base version |
| Block work | LF | queued |
| Capture photo/evidence | LF | binary queued |
| Record measurement/test | LF | queued |
| Material usage draft | LF | server validates |
| Submit job completion | LF | depends on evidence; authoritative acceptance later |
| Supervisor accept work | OA | default; may review cached data |
| Project assignment/reassignment | OA | avoid conflicting offline authority |
| Project close | OA | critical |
| Drawing/spec view | CR | revision/freshness visible |
| Document latest-approved change | OA | authority-critical |
| Asset passport view | CR | current state may be stale |
| Asset scan/identify | CR/MIXED | local known tags; server refresh when online |
| Asset checkout | OA default | optional controlled warehouse offline mode later |
| Asset return scan draft | LF/MIXED | final receipt authority policy |
| Report asset damage | LF | server may validate lifecycle transition |
| Mark asset lost/write-off | OA | high risk |
| Stock count | LF | count draft |
| Stock issue | OA default | quantity/collision risk |
| Stock consumption at site | LF | validates against issued balance |
| Stock adjustment | OA | high risk |
| Purchase requirement draft | LF/CR | can draft offline |
| Submit purchase requirement | OA | shared workflow |
| Supplier quote/PO | OA | commercial authority |
| Receive delivery | MIXED | local capture possible; final authoritative receipt policy |
| Payroll view own payslip | CR | secure cached copy policy TBD |
| Payroll draft/edit | OA | finance |
| Payroll approval | OA | critical |
| Payment prepare | OA | finance |
| Payment execute | ID | bank/external |
| Payment reconcile | ID/OA | external authority |
| Expense draft | LF | receipt queued |
| Expense submit | LF/OA | can queue; server approves |
| Leave request | LF/OA | can queue request |
| Employee role change | OA | permissions affected |
| Offboarding completion | OA | security-critical |
| Client project view | CR | selected cached data possible |
| Client approval | OA | version-bound |
| Support ticket create | LF/OA | can queue creation with evidence |
| Support ticket status | CR | stale indicator |
| Maintenance checklist | LF | field |
| Maintenance close/accept | OA | authoritative |
| Camera live view | ID | requires NVR/network |
| Camera event history | ID/CR | provider dependent |
| Door/access control action | ID | physical authority |
| Security credential change | OA/ID | critical |
| Global search | OA + local recent | exact local recent can work; authoritative search online |
| Inbox read | CR | cached |
| Approval action | OA | all high-trust approvals |
| Notifications delivery | ID | provider |
| Local notification display | LF | device local |
| AI company search/summary | OA | server permissions/data required |

---

# Rules

## 1. Offline capture ≠ offline acceptance
Technician may complete locally.
Server may later reject/supersede based on current state.

## 2. Financial/security authority stays online
No payroll/payment/access/write-off finalization offline.

## 3. Stale state must be visible
Particularly:
- assets
- project summary
- client status
- drawings
- support SLA

## 4. Every LF feature needs
- local durable record
- operation ID
- base version if conflict-sensitive
- dependency graph
- user-visible state
- retry
- conflict handling

## 5. Every CR feature needs
- freshness timestamp/version
- secure local storage classification
- invalidation policy

## 6. Every ID feature needs
- provider unavailable state
- unknown outcome semantics
- integration health

## Freeze gate
Every Feature Catalog ID in first build sequence must receive one explicit offline class.
