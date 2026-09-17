# HILTECH Module Dependency Graph

Status: PROPOSED v0.1 / NOT FROZEN

## Principle
Dependencies should generally point from higher-level orchestration/application modules toward stable capabilities, not form cycles.

This is conceptual; Spring Modulith verification should later enforce actual package/module dependencies.

---

# Foundation Capabilities

```text
identity
organizations
documents
audit
```

These are broadly referenced but must not become giant god-modules.

---

# Workforce

```text
organizations
    ↓
people
```

People may reference identity subject IDs but should not depend on authentication implementation.

---

# Commercial to Delivery

```text
organizations
    ↓
sales ──────────────┐
                    ▼
                 projects
                    ▼
                   work
                    ▼
               engineering
```

Not every arrow means direct synchronous code dependency.
Some transitions occur by application event/command.

---

# Physical Operations

```text
assets
  ▲    │   warehouse ─────→ work readiness
  ▲
  │
procurement
```

More precise direction:
- procurement tells warehouse expected/accepted receipt.
- warehouse owns stock/custody.
- work queries resource readiness.
- assets owns asset lifecycle/identity.

Avoid assets↔warehouse compile-time cycle by explicit ports/contracts.

---

# Money

```text
people ───────→ payroll ───→ finance
                              ▲
procurement ──────────────────┤
projects/work ────────────────→│
sales/contracts ──────────────→│
```

Finance receives obligations/context; it does not reach into other modules' tables.

---

# Shared Approval

```text
sales ───────┐
projects ────┤
procurement ─┤
finance ─────┼──→ approvals
payroll ─────┤
people ──────┤
warehouse ───┘
```

Approvals knows subject reference/policy context, not subject internals.

Subject modules react to approval result.

---

# External Experience

```text
organizations
projects
finance
documents
support/service
   └──────────→ clients projection/API
```

Supplier/subcontractor surfaces are constrained projections over procurement/work/org data.

---

# Cross-Cutting

notifications consumes events.
inbox consumes action/event projections.
audit receives auditable actions.
integrations provides adapters.
automation coordinates where explicitly required.

These should not become arbitrary backdoors into domain persistence.

---

# Forbidden Patterns

- finance repository imported by procurement.
- warehouse table updated directly by work.
- payroll reading HR tables through raw SQL.
- client module duplicating project tables.
- notifications mutating domain state.
- integrations directly deciding business outcome.
- audit module becoming an event bus.
- shared/core module containing domain business logic.

---

# Implementation Verification

Later architecture tests should assert:
- module boundaries.
- allowed dependencies.
- no cycles.
- no internal package access.
- event/interface exposure only through public API.

Spring Modulith is a leading tool for this verification.

## Next
Create per-module public API/command/event contracts for first vertical slice after freeze.
