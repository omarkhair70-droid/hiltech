# ADR-018 — Search Strategy

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH needs global search across authorized operational objects:
- projects/sites/work,
- assets/serials,
- employees where permitted,
- procurement/finance references,
- tickets/documents,
- exact IDs/codes.

Search results are security-sensitive. A dedicated search engine creates another replicated data model and authorization challenge.

## Decision

Start with a **PostgreSQL/read-model search baseline**.

Architecture:
- owner modules expose permission-safe searchable projections,
- normalized search read model contains only fields approved for search,
- exact identifiers/codes receive first-class lookup paths,
- PostgreSQL text/index capabilities handle initial text search,
- authorization and field filtering occur before result exposure,
- local client may keep only its authorized/offline search projection.

Do **not** introduce OpenSearch/Elasticsearch as a baseline dependency.

## Search Result Contract

Representative result:
- object type,
- ID/code,
- display title,
- authorized context,
- status,
- matched/highlight-safe text,
- permitted quick actions.

Never index a sensitive field merely because UI later hides it.

## Dedicated Search Engine Trigger

Consider a dedicated search engine only if measured requirements demand it, for example:
- corpus/latency no longer meets SLOs with PostgreSQL,
- advanced linguistic/fuzzy/ranking needs become material,
- large document-content search is required,
- independent search scaling is justified.

Before adoption, prove:
- authorization-safe indexing,
- deletion/update propagation,
- tenant/org boundaries,
- stale-index behavior,
- operational recovery.

## Consequences

Positive:
- fewer replicated truth stores,
- simpler permission model,
- exact HILTECH identifiers remain strong,
- search evolves with real corpus/use patterns.

Cost:
- advanced relevance features may arrive later.

## Revisit Trigger

Real HILTECH search corpus, query patterns or performance demonstrate PostgreSQL/read-model search is insufficient.
