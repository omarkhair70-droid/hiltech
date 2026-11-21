# Phase 6 / Slice 04C — Controlled Document Revision Source

Date: 2026-11-22
Status: **READ SOURCE IMPLEMENTED / NO INVENTED PUBLISH AUTHORITY**

## Purpose

HILTECH Work already supports materialized `DOCUMENT` requirements and the readiness evaluator already recognizes `DOCUMENT` / `DRAWING_REVISION` types.

Before this checkpoint those requirements always returned:

`AUTHORITATIVE_DOCUMENT_SOURCE_UNAVAILABLE`

This checkpoint adds the missing generic revision source. It does not create a Fiber-specific drawing subsystem.

## Model

### controlled_document

Project-scoped controlled identity:

- organization;
- project;
- optional site scope;
- code;
- title;
- document type code;
- lifecycle;
- explicit current approved revision.

Examples can later be drawing, method statement, specification, commissioning sheet or another controlled project document without changing the domain.

### controlled_document_revision

Immutable revision content/provenance:

- revision code + ordered sequence;
- lifecycle: APPROVED / SUPERSEDED / WITHDRAWN;
- exactly one content source:
  - READY Evidence reference, or
  - external authoritative source reference;
- approval timestamp;
- optional source-owned approver reference;
- source version.

A current revision must be APPROVED and belong to the same document.

Revision content/provenance cannot be edited in place. A current revision cannot be demoted until another approved revision is selected as current.

### work_document_revision_binding

Pins one materialized WorkOrder DOCUMENT requirement to one exact document revision.

Binding identity is immutable. Rebinding is:

1. supersede the old binding;
2. insert a new binding.

The previous Work execution therefore preserves exactly which revision it used.

## Readiness behavior

For a DOCUMENT/DRAWING_REVISION requirement:

- no binding → `DOCUMENT_REVISION_NOT_BOUND`;
- wrong Organization/Project/Site scope → `DOCUMENT_SCOPE_INVALID`;
- withdrawn document → `DOCUMENT_WITHDRAWN`;
- withdrawn bound revision → `DOCUMENT_REVISION_WITHDRAWN`;
- missing current revision → `DOCUMENT_CURRENT_REVISION_MISSING`;
- document current revision differs from WorkOrder-pinned revision → `DOCUMENT_REVISION_STALE`;
- bound/current approved revision matches → `DOCUMENT_REVISION_CURRENT` and requirement is SATISFIED.

There is no implicit upgrade from revision A to revision B.

## Field JobBundle

The existing `safeDocumentRefs` surface now reads authoritative bindings.

It exposes safe metadata only:

- document ID;
- code;
- title;
- pinned revision;
- current revision;
- CURRENT / STALE / WITHDRAWN / SOURCE_MISSING status;
- source type (EVIDENCE or EXTERNAL_REF).

It does **not** expose an external source URL/object key through the JobBundle.

Because JobBundle is already durably cached, the downloaded Work context preserves the exact revision visible when it was fetched. The next online refresh may mark that revision STALE if the authoritative current revision changed.

Offline cached content remains historical/read-only context, never authority to silently rebind.

## Authority boundary

This checkpoint intentionally has no generic “Approve Drawing” or “Publish Revision” field endpoint.

The repository does not yet freeze which HILTECH relationship or external engineering/document-control system owns publication approval.

The source tables can be fed by the future approved control-plane/integration path. Field technicians only consume the safe projection.

This prevents using a title such as Engineer / Supervisor / PM as an invented authorization rule.

## S04 interaction

Structured engineering-test results are separately pinned to their Work/policy/schema revisions.

Controlled document binding gives the Work execution the same property for drawings/specs: the system can prove the exact document revision context rather than always reading “latest”.

No document revision is overwritten when a project publishes a newer revision.

## Proof gates

- controlled-document source unit tests: CURRENT, STALE, missing binding, wrong scope, withdrawn;
- V0037 migration on real PostgreSQL;
- JobBundle/shared/Android compile with revision status fields;
- history/immutability constraints in PostgreSQL;
- no direct document content URL disclosure in field projection.

Before a production publication workflow is enabled, HILTECH must bind a real document-control authority or external source integration.

`P6_S04C_DOCUMENT_READ_SOURCE = IMPLEMENTED`
`P6_S04C_DOCUMENT_IMPLICIT_OVERWRITE = FORBIDDEN`
`P6_S04C_DOCUMENT_PUBLICATION_AUTHORITY = NOT_INVENTED`
