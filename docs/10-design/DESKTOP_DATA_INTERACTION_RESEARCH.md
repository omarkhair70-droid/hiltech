# Desktop Dense-Data Interaction Research

Status: RESEARCHING / NOT FROZEN

## Why this is separate
Finance, Payroll, Warehouse, Procurement, Sales/Tenders, and Project control cannot be designed as a collection of cards only.

Desktop HILTECH needs professional dense-data interaction.

## Required capabilities to research/design

- sortable columns
- per-column filters
- global filter
- faceted filters
- grouping
- row expansion
- column visibility
- column sizing
- logical start/end pinning
- bulk selection
- bulk action
- pagination vs virtualization
- sticky headers
- saved views
- saved filters
- export where authorized
- inline edit only where safe
- keyboard navigation
- copy/paste where appropriate
- contextual side inspector
- row history/audit
- status/owner/next-action at glance

## RTL requirement
Column pinning must use logical START/END semantics.

A table cannot assume:
left = identity column
right = action column

because Arabic reverses the visual shell.

## Object-first pattern
A row is not just data; it opens an object detail/inspector.

Example Payroll:
employee row -> source components -> exceptions -> history.

Example Warehouse:
asset row -> custody -> movement -> condition -> documents.

## Bulk action safety
Bulk actions need:
- explicit selection count
- scope preview
- permission validation
- version/stale-state checks
- destructive confirmation
- result summary with partial failures

## Virtualization
Large lists should not require rendering thousands of rows/columns.

Performance strategy must be part of component architecture.

## Reference
TanStack Table feature docs:
https://tanstack.com/table/latest/docs/guide/features

Reference only; final implementation library depends on chosen desktop stack.

## Freeze gate
Before desktop implementation:
- table primitive strategy
- keyboard model
- RTL model
- saved view model
- virtualization strategy
- bulk action/error model
- accessibility test plan
