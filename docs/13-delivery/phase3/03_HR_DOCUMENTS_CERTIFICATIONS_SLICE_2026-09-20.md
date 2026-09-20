# Phase 3 / Slice 03 — HR Documents / Certifications

Date: 2026-09-20  
Status: **VERIFIED / MERGED**

## Reality basis

`docs/13-delivery/phase3/03_HR_DOCUMENTS_CERTIFICATIONS_REALITY_CLOSURE_2026-09-20.md`

## Domain ownership

People owns:
- EmployeeDocument metadata/business verification;
- Certification/training facts;
- certification validity/eligibility read models.

Documents/Evidence owns:
- Evidence metadata;
- upload session;
- object-storage identity;
- integrity/security state;
- signed delivery.

People must not duplicate binary storage state.

## EmployeeDocument

Create `employee_document`.

Minimum fields:
- id;
- organization_id;
- employee_id;
- document_type_code;
- document_label nullable;
- issue_date nullable;
- expiry_date nullable;
- verification_state;
- verified_at nullable;
- verified_by_user_id nullable;
- evidence_id nullable unique;
- retention_policy_code nullable;
- created_at;
- updated_at;
- version.

Initial verification states:
- `UNVERIFIED`;
- `VERIFIED`;
- `REJECTED`.

Rules:
- employee belongs to same organization;
- type code required/bounded/configurable;
- expiry >= issue when both exist;
- verification metadata is internally consistent;
- Evidence, when attached, must target this EmployeeDocument;
- private metadata is People-authorized only.

## Certification

Create `certification`.

Minimum fields:
- id;
- organization_id;
- employee_id;
- certification_type_code;
- certification_label nullable;
- issuer nullable;
- issued_at nullable;
- valid_until nullable;
- verification_state;
- verified_at nullable;
- verified_by_user_id nullable;
- employee_document_id nullable;
- created_at;
- updated_at;
- version.

Rules:
- employee/document/verified-by identities stay organization-consistent;
- certification type is configurable, not enum;
- valid_until >= issued_at when both exist;
- supporting document is optional;
- supporting document must belong to same Employee;
- VERIFIED with a supporting document requires that document to be VERIFIED and any attached Evidence to be READY.

## Commands

### CreateEmployeeDocument

Input:
- operationId;
- employeeId;
- baseEmployeeVersion;
- documentTypeCode;
- documentLabel nullable;
- issueDate nullable;
- expiryDate nullable;
- retentionPolicyCode nullable;
- actor/correlation.

Behavior:
- People management authority required;
- idempotent;
- create UNVERIFIED metadata;
- audit safe field names/codes only;
- emit `EmployeeDocumentCreated`.

### VerifyEmployeeDocument

Input:
- operationId;
- employeeDocumentId;
- baseVersion;
- result = VERIFIED or REJECTED;
- actor/correlation.

Behavior:
- People management authority required;
- exact-version;
- if VERIFIED and Evidence is attached, Evidence must be READY;
- write verifiedAt/by;
- audit/event without private binary/file values.

### CreateCertification

Input:
- operationId;
- employeeId;
- baseEmployeeVersion;
- certificationTypeCode;
- certificationLabel nullable;
- issuer nullable;
- issuedAt nullable;
- validUntil nullable;
- employeeDocumentId nullable;
- actor/correlation.

Behavior:
- People management authority;
- idempotent;
- create UNVERIFIED;
- supporting document, when supplied, must belong to same Employee.

### VerifyCertification

Input:
- operationId;
- certificationId;
- baseVersion;
- result = VERIFIED or REJECTED;
- actor/correlation.

Behavior:
- exact version;
- People management authority;
- when verifying with supporting document, require verified document + READY Evidence if attached.

## Reads

### Private employee document list/detail

People management authority only.

May expose:
- document type/label;
- issue/expiry;
- verification state;
- evidence state/reference ID;
- verifier/time;
- version.

Do not return signed URLs inside normal JSON reads.

### Private certification administration

People management authority only.

### Safe certification eligibility

Current organization members may read safe certification facts for Employees in that organization:

- employeeId/code/displayName;
- certificationTypeCode/label;
- verification state;
- `validNow`;
- validUntil.

No EmployeeDocument metadata or Evidence ID is exposed.

This read does not claim final Project/Work eligibility.

## Evidence target extension

Add `EMPLOYEE_DOCUMENT` as a supported Evidence target adapter.

### Reserve

The existing reserve API remains:

`POST /v1/evidence/reservations`

For `targetType = EMPLOYEE_DOCUMENT`:
- targetId = EmployeeDocument ID;
- `workOrderId` must be absent;
- target authorization = current explicit People management authority;
- organization derives from EmployeeDocument;
- document must be current/visible and not REJECTED;
- use system HR Evidence policy baseline, never client classification;
- create Evidence with `target_type='EMPLOYEE_DOCUMENT'`, `target_id=documentId`, `work_order_id=NULL`;
- link EmployeeDocument.evidence_id to the reserved Evidence transactionally;
- superseding, if supported in this first adapter, must stay within same EmployeeDocument target.

### Finalize/read/download

Dispatch authorization by target type.

For EmployeeDocument:
- People management authority;
- creator alone does not bypass People authority after creation unless a later self-service contract explicitly grants it;
- same private storage verification rules as existing Evidence;
- HIGHLY_RESTRICTED direct signed download remains prohibited if current Evidence contract prohibits it; use the existing safe delivery behavior only.

### Existing WorkOrder Evidence must not regress

All existing Phase 2 Evidence tests remain green.

No widening of WorkOrder authority.

## Events

Required:
- `EmployeeDocumentCreated`;
- `EmployeeDocumentVerified`;
- `CertificationCreated`;
- `CertificationVerified`.

Eligibility/expiry consumers may derive from certification state; no mandatory midnight mutation event.

## Idempotency / concurrency

Reuse Phase 2 command runtime.

Required:
- stable operation IDs;
- exact-version verification;
- duplicate create replay returns same object;
- same operation ID with changed semantics rejects;
- only one Evidence may be attached as current binary to one EmployeeDocument in this minimal slice;
- concurrent verify cannot overwrite newer state.

## Explicit non-goals

Do not implement:
- exact HILTECH required-document catalog;
- exact role/client/site certification requirement matrix;
- legal retention/deletion schedule;
- employee self-service document upload/read — Slice 04;
- onboarding checklist — Slice 04;
- job assignment eligibility decision — later Work/Field phases;
- generic document management;
- multiple attachments per EmployeeDocument;
- certification binary subsystem separate from EmployeeDocument/Evidence.

## Required evidence for VERIFIED

1. V0017 migration creates EmployeeDocument + Certification with constraints.
2. jOOQ generation compiles.
3. document/certification type codes are configurable and not authority.
4. create commands are idempotent.
5. exact-version verify rejects stale writes.
6. cross-organization document/certification references fail closed.
7. supporting Certification document must belong to same Employee.
8. document/certification VERIFIED business state obeys supporting Evidence readiness.
9. expiry/validNow is derived correctly.
10. private HR metadata is denied to ordinary organization member.
11. safe certification eligibility leaks no private document/Evidence metadata.
12. `EMPLOYEE_DOCUMENT` Evidence reserve uses existing private storage lifecycle.
13. EmployeeDocument Evidence reserve derives organization/authority from People source truth.
14. EmployeeDocument Evidence reserve never accepts client classification/visibility authority.
15. EmployeeDocument Evidence finalize re-authorizes People authority.
16. existing WorkOrder Evidence reserve/finalize/read/download contracts remain green.
17. object checksum/size/quarantine behavior remains inherited and green.
18. no permanent/public HR document URL exists.
19. shared Android/Windows People client contracts compile.
20. inherited Phase 0–2 + Phase 3 Slice 01/02 regressions PASS.
21. Spring Modulith boundaries PASS.
22. exact-head CI PASS.

## Verification closure

Canonical tested code head:

`9fa0b95de549bd92cc7d3a554cd7cdcf941ef35f`

Exact-head verification:

- Bootstrap Phase 0 `35482995671` — **PASS**
  - V0017 migration and database contract — PASS
  - jOOQ generation and server compile — PASS
  - HR Documents / Certifications PostgreSQL + Evidence lifecycle contract — PASS
  - canonical OpenFGA model/local platform regression — PASS
  - inherited Evidence S3-compatible storage contract — PASS
  - shared tests, Android debug build, Desktop compile and server tests — PASS
  - dependency/supply-chain/Terraform gates — PASS
- Phase 2 Shared Command Runtime `35482995728` — **PASS**
- Phase 1 Native OIDC Production Smoke `35482995573` — **PASS**

Verified implementation includes:

- `employee_document` + `certification` schema and constraints;
- configurable document/certification type codes;
- issue/expiry/verification facts and exact-version verification;
- safe certification eligibility read without private HR/Evidence leakage;
- `EMPLOYEE_DOCUMENT` dispatch through the existing private Evidence/object-storage lifecycle;
- target-specific People authorization for Evidence reserve/finalize/read paths;
- inherited checksum/size/private-delivery behavior without a second binary-storage subsystem;
- PDF signature validation in the HR document evidence path;
- shared KMP HR document/certification contracts and client coverage;
- inherited WorkOrder Evidence behavior preserved.

No onboarding/self-service policy, required-document catalog, legal retention schedule, Project/Work eligibility engine, generic document management or separate certification binary subsystem was introduced.

## Merge closure

PR #44 merged at:

`d5d0cb256e68cf7b30978434aa6ba241c7500df1`

Post-merge Bootstrap:

- `35485263189` — **PASS**
  - foundation — PASS;
  - database migration/jOOQ/server compile — PASS;
  - local platform/OpenFGA contract — PASS;
  - Evidence storage contract — PASS;
  - Terraform and supply-chain gates — PASS.

A later documentation-only governance merge (#45) added the canonical product-ownership principles without changing Slice 03 runtime behavior.

## Contract conclusion

**VERIFIED / MERGED.**

Slice 03 is closed.

Phase 3 proceeds to Slice 04 — Onboarding + Basic Self-Service.

Do not reopen Slice 03 unless later evidence exposes a genuine contract contradiction.
