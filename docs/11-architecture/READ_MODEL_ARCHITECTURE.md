# HILTECH Read Model Architecture

Status: ARCHITECTURE MODEL v0.1

## Purpose
HILTECH has complex screens that combine multiple domains. We need fast, permission-safe read models without breaking domain ownership.

Do not make UI assemble company truth through 20 sequential API calls.

Do not let one module directly mutate another module's data for convenience.

---

# 1. Write Model vs Read Model

Write path:
domain command -> owner module -> transaction/invariants.

Read path:
authorized projection/query tailored to task.

Example:
Mohamed Company Pulse combines:
- projects,
- finance,
- warehouse,
- support,
- sales.

It is a read projection, not a giant Executive domain entity.

---

# 2. Read Model Types

## Direct Domain Read
Simple object detail from owner module.

Example:
AssetPassport.

## Aggregated Query
Composes authorized data across modules.

Example:
ProjectExecutiveSummary.

## Materialized Projection
Precomputed for expensive/high-volume dashboard/list.

Example:
ProjectPortfolioHealth.

## Search Index Projection
Searchable representation of authorized object metadata.

Dedicated search engine only if needed later.

---

# 3. Candidate Read Models

## Owner
- CompanyPulse
- NeedsYouItem
- ProjectPortfolioItem
- FinancePulse
- CriticalAssetException
- ClientRiskSummary

## Finance
- PayrollReviewRow
- PayableQueueItem
- ReceivableQueueItem
- ReconciliationItem
- ProjectFinancialSummary

## PM
- MyProjectListItem
- ProjectReadinessSummary
- SiteTodaySummary
- ResourceConflict

## Technician
- TechnicianJobBundle
- AssignedAssetSummary
- OfflineDocumentManifest

## Warehouse
- InventoryRow
- AssetCustodyRow
- ReservationQueueItem
- ReturnDueItem
- ReceivingQueueItem

## Client
- ClientProjectSummary
- ClientActionItem
- ClientDocumentItem
- SupportTicketSummary

---

# 4. Permission Safety

Read model generation filters:
- object visibility,
- field-level access,
- organization scope,
- client-visible flags,
- sensitive classifications.

Never create one full internal read model and rely on UI to hide fields.

---

# 5. Freshness

Every projection that may lag should expose:
- asOf / updatedAt
- freshness/state where material

Example:
executive portfolio may be seconds/minutes eventually consistent.
Payment state may require authoritative direct query.

---

# 6. Projection Updates

Options:
- synchronous in transaction.
- post-commit event.
- scheduled refresh.
- query-time aggregation.

Choose per use case.

No need for CQRS infrastructure theatre where simple query works.

---

# 7. Offline Client Reads

Server read model can map to local client cache model.

Do not make local DB schema identical to server tables.

Local schema optimized for:
- screens,
- offline bundles,
- sync.

---

# 8. Search

Search result read model:
- type
- ID/code
- title
- context
- status
- highlight
- allowed quick actions

Search itself must enforce permissions before result exposure.

---

# 9. Pagination / Virtualization

Large desktop lists use server pagination/cursor + client virtualization where needed.

Do not download all payroll/assets merely because desktop can display dense tables.

---

# 10. Export

Exports are specialized read models with explicit permission/audit.

Do not reuse unrestricted database dump.

## Freeze Gate
Map each representative screen to exact read model, owner/query service, freshness, permission and pagination behavior.
