# Master Workflow — Maintenance & Managed Service Lifecycle

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Convert delivered infrastructure into a long-lived managed relationship through preventive maintenance, inspections, recurring work, monitoring, SLA, asset history, and recurring commercial terms.

---

# 1. Service Contract

Potential contract types:
- warranty support
- preventive maintenance
- corrective maintenance
- managed infrastructure
- NOC/monitoring
- scheduled testing/calibration
- recurring support retainer

Contract captures:
- client
- covered sites/assets/services
- start/end
- renewal
- visit frequency
- SLA
- inclusions/exclusions
- commercial terms
- response windows
- escalation contacts

---

# 2. Coverage Model

Coverage can target:
- project
- site
- asset class
- specific asset
- network/service
- location/branch portfolio

Need explicit coverage, not vague "client has maintenance".

---

# 3. Preventive Schedule

System generates planned work:
- inspection
- cleaning
- testing
- calibration check
- firmware/config review if applicable
- fiber/link testing
- rack/data-center checks
- backup readiness

Each visit:
plan -> assign -> execute -> evidence -> findings -> acceptance -> next due.

---

# 4. Corrective Maintenance

Can originate from:
- client ticket
- monitoring alert
- technician discovery
- scheduled inspection finding

Creates:
- incident
- work order
- parts/tool requirement
- engineering review if needed

---

# 5. Managed Monitoring / NOC

Future service can track:
- sites
- links
- devices
- availability
- incidents
- SLA
- alert history

Monitoring event may create incident/ticket according to policy.

Important:
NOC telemetry is not the same as project/ERP data, but integrates into same HILTECH object model.

---

# 6. Maintenance Visit

Technician/engineer gets:
- covered site/assets
- previous history
- checklist
- required tests
- known open issues
- required tools/parts

Outputs:
- findings
- evidence
- tests
- condition
- recommended action
- replaced parts
- next maintenance

---

# 7. Findings

Finding types:
- normal
- attention
- defect
- imminent risk
- out-of-scope upgrade opportunity

Finding can become:
- support ticket
- corrective work
- quote
- client recommendation
- no action

---

# 8. SLA / Service Health

Contract-level view:
- open incidents
- response times
- resolution times
- preventive completion
- uptime/availability if measured
- SLA breach/near-breach
- repeated failures

Client sees contract-appropriate subset.

---

# 9. Commercial

Potential:
- recurring monthly/quarterly/annual billing
- per-visit
- included hours
- out-of-scope billable work
- parts charged separately

Finance integration must follow actual contracts/accounting.

---

# 10. Renewal

Before end:
- contract performance summary
- unresolved issues
- asset condition
- pricing/coverage review
- renewal opportunity

Do not silently auto-renew without contract/policy.

---

# 11. Exit / Expiry

On expiry:
- coverage changes
- support requests may become out-of-contract
- client remains with historical service record
- monitoring integrations deactivated where required

---

# Major Objects

- Service Contract
- Coverage Rule
- SLA Definition
- Preventive Schedule
- Maintenance Visit
- Maintenance Checklist
- Finding
- Managed Service
- Monitoring Profile
- Service Health Summary
- Renewal Opportunity

---

# Completion Gate

Requires:
- actual maintenance/warranty offerings,
- contract samples,
- preventive checklists,
- NOC reality/strategy,
- recurring billing rules,
- monitoring vendor/protocols,
- client experience,
- tests for expiry, partial coverage, out-of-scope findings, missed visits.
