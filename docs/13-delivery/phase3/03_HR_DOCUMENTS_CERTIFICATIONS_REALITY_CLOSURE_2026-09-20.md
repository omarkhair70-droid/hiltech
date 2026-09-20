# Phase 3 / Slice 03 — HR Documents / Certifications Reality Closure

Date: 2026-09-20  
Status: **REALITY CLOSED / CONTRACT MAY FREEZE**

## Purpose

Slice 03 adds People-owned employee document metadata and certification/training facts without inventing HILTECH-specific required-document catalogs that have not been validated.

It must reuse the already-verified private Evidence/object-storage lifecycle rather than introduce a second binary/file subsystem.

## Canonical sources

- `docs/13-delivery/phase3/00_PHASE3_PEOPLE_CORE_SCOPE_CLOSURE_2026-09-20.md`
- `docs/05-workflows/EMPLOYEE_LIFECYCLE.md`
- `docs/06-data/object-specs/PEOPLE_PAYROLL_OBJECTS.md`
- `docs/03-product/FEATURE_CATALOG.md`
- Phase 2 Evidence lifecycle contract and production code
- Phase 3 Slice 01 Employee/Employment authority and Slice 02 WorkforceAssignment structure.

## Reality conclusions

### 1. Exact HILTECH document/certification catalogs are not known yet

Known product need:
- employee document records;
- certification/training records;
- issued/expiry/verification facts;
- eligibility facts for later work planning;
- private binary attachments.

Unknown and deliberately not invented:
- exact mandatory document list per role;
- exact certification list per role/client/site;
- exact retention periods/legal schedules;
- exact onboarding requirement matrix.

Therefore document/certification type codes are configurable data, not hard-coded enums.

Synthetic/test codes may be used in fixtures only.

### 2. Employee documents are highly restricted HR data

Document metadata and binary contents are not organization-directory data.

Baseline:
- manage/read private HR document metadata: explicit People management authority;
- binary reserve/finalize/download: explicit People management authority;
- employee self-service access to own allowed documents is deferred to Slice 04, where onboarding/self-service policy is frozen.

No permanent/public URLs.

### 3. Certification eligibility is a safe derived fact, not the HR document itself

Operational planning later needs to know whether an employee has a verified, currently valid certification.

Safe eligibility read may expose:
- certification type code;
- optional safe display label;
- verification state;
- validity state / valid-until;
- employee identity code/display name as already-safe People fields.

It must not expose:
- document binary;
- raw document numbers;
- private filenames;
- verification notes;
- legal identity data.

This is a certification fact, not a final job-eligibility decision. Project/Work requirements arrive in later phases.

### 4. Certification may reference an EmployeeDocument instead of creating another binary lifecycle

A certification/training record may have:
- no file;
- or one supporting EmployeeDocument reference.

The file remains owned by EmployeeDocument/Evidence.

Do not add a second certification attachment subsystem.

### 5. Existing Evidence lifecycle is currently WorkOrder-only

Phase 2 intentionally froze the production Evidence lifecycle to `WORK_ORDER`.

Slice 03 is the first justified second target.

Therefore Slice 03 extends the existing lifecycle with one target adapter:

`EMPLOYEE_DOCUMENT`

The extension must preserve all existing WorkOrder behavior and tests.

### 6. EmployeeDocument metadata exists before binary reservation

Flow:
1. authorized People user creates EmployeeDocument metadata;
2. optional binary Evidence reserve targets that EmployeeDocument;
3. upload uses the existing private object-storage adapter;
4. finalize performs the same size/hash/content verification;
5. EmployeeDocument references the authoritative Evidence record;
6. optional Certification references EmployeeDocument.

This avoids circular creation.

### 7. HR Evidence target uses strict baseline policy

Until a formal HR-document policy catalog exists, the EmployeeDocument Evidence target uses a narrow system baseline:

- classification: `HIGHLY_RESTRICTED`;
- client visibility: `INTERNAL_ONLY`;
- maximum size: reuse existing Evidence system maximum (16 MiB);
- accepted content types: reviewed HR-safe set only;
- native-media/document content validation remains fail-closed;
- arbitrary file behavior remains quarantined where the Evidence lifecycle requires it;
- no client-controlled classification/visibility.

This is not a legal retention policy decision.

### 8. Verification is a People business action, not proof that the binary is safe

Binary Evidence state and People verification state are separate:

Evidence answers:
- did the stored bytes pass integrity/security lifecycle?

EmployeeDocument/Certification answers:
- did an authorized People reviewer verify the business fact?

A document/certification cannot become VERIFIED if required supporting Evidence exists but is not READY.

### 9. Expiry is time-derived

Do not run a mutation job merely to flip every row at midnight.

For certification/document validity:
- `validUntil/expiryDate < current date/time` => expired in reads/eligibility;
- stored verification state remains historical;
- later notifications may consume expiry facts.

### 10. No new user question is required for the minimal Slice 03

The unknown exact HILTECH catalogs, role requirements and retention rules are intentionally configurable/deferred.

They do not block:
- generic metadata;
- verification;
- expiry;
- safe certification eligibility;
- secure binary Evidence attachment.

## Reality conclusion

**REALITY CLOSED.**

Slice 03 may freeze and implement EmployeeDocument + Certification facts and one `EMPLOYEE_DOCUMENT` Evidence target adapter, without inventing HILTECH-specific requirement catalogs.
