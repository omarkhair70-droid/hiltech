# HILTECH ID & Versioning Conventions

Status: ARCHITECTURE MODEL v0.1

## 1. Internal IDs

Leading candidate:
UUIDv7 for server-created domain objects where supported.

Reasons:
- globally unique.
- roughly time ordered.
- safe for future distributed/offline contexts.
- PostgreSQL 18 has native uuidv7 generation support.

Final generation ownership (server/client) depends on object/offline behavior.

---

# 2. Human Codes

Separate from ID.

Examples:
Project:
PRJ-2026-00124

Asset:
HT-A-000184

PO:
PO-2026-0081

Work Order:
WO-2026-12345

Ticket:
TKT-2026-0034

Human codes:
- readable/searchable.
- not security boundary.
- formatting rules may vary by domain.

---

# 3. Version Fields

Mutable authoritative objects use:
version: monotonically increasing integer/long.

Used for:
- optimistic concurrency.
- offline baseVersion.
- cache invalidation.

---

# 4. Business Version vs Row Version

Some objects need explicit business version in addition to technical optimistic version.

Examples:
QuoteVersion
PurchaseOrderVersion
PayrollRunVersion
DocumentVersion
ScopeVersion
BOQVersion
HandoverPackageVersion

Business version:
meaningful historical snapshot.

Technical version:
concurrency control of current record.

Never confuse them.

---

# 5. Event IDs

Every domain/audit event:
globally unique event ID.

Technical event envelopes later may include:
- eventId
- eventType
- schemaVersion
- occurredAt
- aggregate/object ref
- actor
- correlation/causation IDs

---

# 6. Operation IDs

Offline/retriable commands:
operationId generated once on originating device/client.

Stable across retry.

Different from request/correlation ID.

---

# 7. Correlation / Causation

Correlation ID:
links technical request/workflow trace.

Causation ID:
links event/action to source command/event when useful.

---

# 8. External IDs

Never replace HILTECH ID with provider ID.

Store:
- provider
- external ID
- HILTECH object relationship.

Example:
Bank payment external reference.
NVR camera external ID.
Keycloak subject.

---

# 9. ID Exposure

Public/external APIs use opaque IDs/human codes according use case.

Do not expose:
- DB sequence.
- secret provider identifiers.
- credentials.

---

# 10. Version Conflict

Command with baseVersion older than server:
server determines whether:
- reject conflict,
- safe rebase,
- append independently.

Never generic last-write-wins for critical state.

## Freeze Gate
Confirm UUID strategy with KMP/local creation, PostgreSQL/jOOQ spike, and API contracts.
