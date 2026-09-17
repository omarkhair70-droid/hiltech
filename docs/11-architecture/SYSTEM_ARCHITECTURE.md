# HILTECH System Architecture

Status: ARCHITECTURE MODEL v0.1 / NOT FROZEN / NOT BUILD-READY

## Goal
Turn HILTECH OS into one coherent product with:
- Android mobile,
- Windows desktop,
- future iOS,
- public website,
- one authoritative backend,
- offline/local-first field capability,
- integrations with physical/external systems,
- strong permissions/audit,
- modular growth without premature microservices.

---

# 1. System Shape

```text
                         HILTECH USERS
        ┌────────────────────┬────────────────────┐
        │                    │                    │
   Android App          Windows App          iOS later
        │                    │                    │
        └──────────── Shared HILTECH Client ─────┘
                             │
                    HTTPS / Realtime
                             │
                       HILTECH API
                             │
          ┌──────────────────┴──────────────────┐
          │                                     │
   Modular Monolith                      Integration Boundary
          │                                     │
  ┌───────┼────────┐                    ┌────────┼──────────┐
  │       │        │                    │        │          │
People  Work    Finance               Bank     NVR      Access
Assets  Sales   Clients               Email    SMS      IoT
...      ...     ...
          │
    PostgreSQL
          │
  Object Storage
```

Public website is separate:
```text
hiltech-eg.com (Next.js)
       │
Public/RFQ API boundary
       │
HILTECH backend
```

---

# 2. Architectural Laws

## One authoritative business truth
PostgreSQL-backed HILTECH backend owns shared authoritative business state.

## Local-first does not mean local-authoritative
Field clients may create local work/evidence and queue commands, but server validates authority, versions, invariants and final state.

## Business commands over generic CRUD
Important changes are expressed as commands:
- CheckoutAsset
- CompleteWorkOrder
- ApprovePayroll
- IssuePurchaseOrder
rather than arbitrary generic PATCH of sensitive fields.

## History is first-class
Critical operational/financial changes produce immutable events/history/versions.

## Vendor systems stay behind adapters
Bank/NVR/access/SMS/etc. do not leak vendor-specific APIs into domains.

## No premature microservices
Launch architecture is a modular monolith.
Modules can be extracted only when evidence justifies it.

## UI is role-aware; data model is not duplicated per persona
No separate "client project copy" or "technician project copy".

---

# 3. Client Boundary

Shared client code can own:
- domain models safe for clients,
- API contracts/client,
- local persistence,
- offline command queue,
- sync engine,
- shared feature logic,
- design tokens/components where suitable,
- authorization-aware UI capability hints.

Client never becomes authority for:
- permissions,
- financial truth,
- approvals,
- shared lifecycle invariants.

---

# 4. Backend Boundary

Backend owns:
- authentication integration,
- business authorization,
- domain invariants,
- transactions,
- authoritative workflow states,
- audit,
- integration orchestration,
- server-side automation,
- financial/asset ledgers,
- document metadata,
- notification policy.

---

# 5. Domain Communication

Preferred order:

1. Direct in-module call for one module's internal logic.
2. Explicit application service call when synchronous cross-module response is required.
3. Domain/application event for downstream reactions.
4. Durable workflow/orchestration only when business process requires long-lived coordination.

Avoid:
- shared mutable database tables across modules,
- module reaching directly into another module's repositories,
- circular dependencies,
- event-driven complexity for simple synchronous actions.

---

# 6. Realtime

Realtime transports can improve UX:
- state update,
- action queue update,
- chat/mention,
- progress,
- incident.

But:
- realtime message is never authoritative storage,
- clients reconcile into local data/source of truth,
- reconnect must recover missed state through pull/change sync.

---

# 7. Files / Evidence

Binary content:
- object storage.

Metadata/business relationship:
- PostgreSQL.

Example:
Evidence object in DB:
- id
- project/work/site
- author
- content type
- checksum
- storage key
- upload state
- classification
- created at

Binary:
S3-compatible object storage.

---

# 8. Security Boundaries

Authentication:
OIDC identity provider candidate.

Authorization:
server-side HILTECH policy/object relationship engine.

Critical actions:
may require:
- online authority,
- re-authentication,
- second factor,
- approval,
- version binding.

Never trust hidden UI as authorization.

---

# 9. Deployment Units — Initial

Expected:
1. HILTECH server application.
2. PostgreSQL.
3. Object storage.
4. Identity provider.
5. Authorization service if OpenFGA is adopted.
6. Monitoring/observability stack.
7. Public website.
8. Android client.
9. Windows desktop client.

Conditional later:
- Temporal
- Redis
- dedicated broker
- OpenSearch
- separate telemetry service
- extracted domain services.

---

# 10. Architecture Success Criteria

- Same object state visible safely across mobile/desktop.
- Offline field work survives crash/network loss.
- One module cannot bypass another module's invariants.
- Authorization is enforceable/testable.
- Financial/asset actions are auditable.
- Integrations can be replaced by adapter.
- Company can grow without immediate distributed-system complexity.
- Implementation teams can locate ownership of every feature.

## Completion gate
Requires:
- client architecture frozen,
- backend module map frozen,
- database ownership frozen,
- integration contracts,
- auth/authz spike,
- sync spike,
- packaging/deployment spike,
- observability design,
- repo module/file plan,
- ADRs for final choices.
