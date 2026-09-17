# HILTECH OS — Product Map

Status: `RESEARCHING`

HILTECH OS is one product composed of connected operational worlds, not a bundle of disconnected apps.

## Product thesis

> One HILTECH. One account. One source of truth.

Users enter the same HILTECH system but receive different home states, navigation priorities, object visibility, actions, alerts, and device experiences according to role, context, and permissions.

## Product worlds

### 1. Control

Primary users: owner / executives / authorized managers.

Responsibilities:

- company pulse,
- exceptions,
- approvals,
- project risk,
- material financial visibility,
- escalations,
- key people/operations state,
- company timeline,
- authorized security/facility overview.

Goal: reduce management work, not create another dashboard to maintain.

### 2. People

Responsibilities:

- employees,
- candidates,
- onboarding,
- roles/teams,
- attendance/time where applicable,
- leave,
- skills/certifications,
- assigned assets,
- compensation inputs,
- employee lifecycle,
- offboarding.

### 3. Work

Responsibilities:

- projects,
- sites,
- milestones,
- work orders,
- tasks,
- field execution,
- site evidence,
- issues,
- tests,
- handover,
- maintenance work.

### 4. Money

Responsibilities:

- accounting/finance visibility,
- receivables,
- payables,
- payroll,
- employee expenses/advances,
- supplier invoices,
- client invoices,
- project costing,
- payment preparation/approval,
- reconciliation,
- audit/history.

Exact boundary with any existing accounting system remains open.

### 5. Assets & Warehouse

Responsibilities:

- serialized high-value tools/equipment,
- consumable stock,
- receiving,
- reservations,
- checkout/return,
- custody,
- site transfers,
- damage/loss,
- repair,
- calibration,
- warranty,
- stocktaking,
- low-stock logic,
- project reservations,
- movement history,
- selected location tracking technologies where justified.

### 6. Clients & Commercial

Responsibilities:

- CRM/customer organizations,
- leads/opportunities,
- RFQ/tenders,
- quotations,
- contracts,
- change/variation requests,
- client approvals,
- client-visible project progress,
- invoices visible to the client as authorized,
- support and maintenance,
- relationship history.

### 7. Security & Facilities

Responsibilities where supported by real company hardware and policy:

- cameras/NVR access,
- doors/access control,
- visitor events,
- warehouse entry context,
- office/security events,
- company network/facility state where useful.

HILTECH OS should integrate with existing infrastructure rather than unnecessarily replacing specialist security systems.

### 8. Documents & Knowledge

Documents live in business context instead of an isolated file dump.

Examples:

- BOQ → Project,
- drawing → Site/Floor,
- OTDR/Fluke result → Link/Test/Project,
- invoice → Supplier/Project,
- employment contract → Employee,
- warranty → Asset.

Knowledge/SOP/search/AI may later build on this structured context.

### 9. Communication & Inbox

One prioritized activity/decision surface for:

- approvals,
- mentions,
- alerts,
- requests,
- messages,
- important changes.

Notification volume must be role-aware. Owners should not receive field noise; field workers should receive actionable operational context.

### 10. Automation

Cross-domain rules and durable workflows can automate or coordinate:

- onboarding,
- payroll preparation/approval,
- purchase requests,
- procurement,
- warehouse reservation/checkout,
- client approvals,
- handover,
- SLA escalation,
- maintenance,
- offboarding.

Automation should remove manual work rather than merely move it to a screen.

### 11. Integration Layer

Candidate integrations include:

- banking/payment rails,
- email/SMS/push,
- CCTV/NVR,
- access control,
- QR/barcode,
- GPS/IoT/location tags,
- testing equipment/export formats,
- external accounting or ERP systems if required,
- client/vendor systems where commercially valuable.

## Cross-device surfaces

### Mobile

Best for:

- today/next action,
- approvals,
- field work,
- scans,
- photos/evidence,
- quick project/site context,
- alerts,
- remote management actions,
- client status/support.

### Desktop

Best for:

- dense control views,
- finance/payroll,
- project planning,
- tables/bulk actions,
- documents,
- analytics,
- procurement,
- warehouse administration,
- multi-pane investigation,
- keyboard-driven operations.

Mobile and desktop remain peers over the same product state.

## Product behavior principles

- Role-aware, not app-per-role.
- Context-aware navigation.
- Enter once, flow everywhere.
- Physical = digital.
- Management by exception.
- Auditability by default.
- Offline behavior is deliberately designed.
- Motion expresses state and continuity.
- Same object, different authorized views.

## Completeness rule

A world/domain is not complete because its happy-path UI exists.

Completion may require, as applicable:

- object model,
- lifecycle/state model,
- normal paths,
- error paths,
- permissions,
- audit/history,
- offline/online behavior,
- integrations,
- reversals/corrections,
- notifications,
- desktop/mobile behavior,
- tests,
- operational monitoring.

## Current next step

Do not draw final screens yet.

Complete persona/day maps and company reality research first, then derive domains, events, permissions, and information architecture from real HILTECH operations.
