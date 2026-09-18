# HILTECH Backend Architecture

Status: ARCHITECTURE MODEL v0.2 / SPRING MODULAR MONOLITH ACCEPTED / EXACT CONTRACTS NOT FROZEN

## ## Target
Accepted baseline:
- Kotlin/JVM,
- Spring Boot,
- Spring Modulith modular monolith,
- PostgreSQL,
- jOOQ.

Evidence:
SPIKE-10 + SPIKE-11; ADR-002/003/004/005.

---

# 1. Layers Per Domain Module

Recommended conceptual layering:

```text
domain/
  entities/value objects
  invariants
  domain services/events

application/
  commands
  queries
  use cases
  transaction boundaries

infrastructure/
  persistence
  external adapters
  messaging/integration implementation

api/
  HTTP/realtime contracts
```

Exact package style may be adjusted after spike.

---

# 2. Candidate Modules

```text
identity
organizations
people
sales
projects
work
engineering
assets
warehouse
procurement
finance
payroll
clients
suppliers
subcontractors
approvals
documents
inbox
notifications
security
automation
integrations
audit
```

Some may merge if boundaries prove artificial.

---

# 3. Dependency Direction

Domain modules expose explicit public application interfaces/events.

Forbidden:
- repository access across modules.
- direct writes into another module's tables.
- arbitrary entity imports creating circular domain coupling.

Example:
Warehouse does not update ProjectCost table directly.
Warehouse emits/records approved consumption; finance/project-cost reacts through explicit contract/event.

---

# 4. Command Handling

A command:
- authenticates actor context,
- authorizes action,
- validates object version,
- validates invariants,
- executes transaction,
- records audit/domain event,
- returns authoritative result.

Example:
CheckoutAsset.

---

# 5. Query Handling

Queries:
- enforce authorization,
- can use optimized read models/views,
- do not mutate domain,
- may aggregate across modules through explicit read services/read projections.

Avoid forcing write-domain entity traversal for executive dashboards.

---

# 6. Transactions

Default:
transaction boundary per authoritative business command.

Cross-module:
- one local DB transaction where safe and explicitly coordinated,
- or reliable post-commit event/reaction.

Do not simulate distributed transactions when system is still one database/application.

---

# 7. Events

Spring Modulith accepted:
- module events,
- persistent publication registry for reliable asynchronous reactions,
- module boundary verification,
- explicit observable resubmission of incomplete publications.

Product event vocabulary remains separate from technical event schema.

Events are facts:
`asset.checked_out`

Commands are requests:
`CheckoutAsset`

---

# 8. Background Jobs

Use application scheduler/background mechanisms for:
- reminders,
- expiry checks,
- digest generation,
- cleanup,
- recalculation.

Long-running/compensating workflow engine only if justified later.

---

# 9. API Style

Likely:
- REST/HTTP commands and queries for broad interoperability.
- SSE/WebSocket for realtime updates where needed.
- signed upload flow for large binary files.

Exact API conventions to freeze later.

Need:
- idempotency keys.
- optimistic version.
- pagination/cursors.
- standard errors.
- correlation/request ID.
- API version policy.

---

# 10. Authorization

Server is mandatory enforcement point.

Flow:
authenticated identity -> HILTECH subject context -> authorization check -> command/query.

OpenFGA handles accepted object/action relationship authorization questions.
Keycloak/OIDC provides identity/session; Spring Security integrates authenticated security context.
HILTECH server remains the mandatory enforcement point.

Sensitive fields may need field-level filtering after object-level allow.

---

# 11. Audit

Separate audit capability stores:
- actor,
- action,
- object,
- old/new semantic summary when appropriate,
- timestamp,
- request/correlation,
- reason,
- version,
- integration reference.

Audit is not equivalent to application logs.

---

# 12. Integration Adapters

Examples:
```text
BankingGateway
VideoSecurityGateway
AccessControlGateway
PushGateway
EmailGateway
SmsGateway
TelemetryGateway
TestEquipmentGateway
ObjectStorageGateway
```

Domain/application code depends on interfaces/contracts, not vendor SDKs.

---

# 13. Failure Semantics

External failures classify:
- retryable,
- permanent,
- unknown outcome.

Unknown outcome is critical for banking/integration operations and must reconcile rather than retry blindly.

---

# 14. Testing

- module architecture tests,
- domain tests,
- command application tests,
- persistence tests with real PostgreSQL,
- authorization tests,
- API contract tests,
- integration adapter contract tests,
- event failure/recovery tests,
- idempotency tests,
- concurrency/version-conflict tests.

---

# 15. Evolution

Extract service only if evidence:
- independent scaling,
- hard isolation,
- separate team ownership,
- deployment cadence,
- regulatory/security boundary,
- workload shape.

Do not extract merely because domain has a folder.

## Completion gate
Requires:
- exact module dependency graph,
- package convention,
- module public APIs,
- persistence ownership,
- Spring Modulith spike,
- auth/authz spike,
- idempotency/version model,
- API conventions.
