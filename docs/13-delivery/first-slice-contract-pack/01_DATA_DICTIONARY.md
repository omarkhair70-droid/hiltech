# 01 — First-Slice Data Dictionary

Status: **PRE-FREEZE TEMPLATE**

## Rule

Every production field must have:
- canonical owner,
- exact wire/storage type,
- nullability,
- lifecycle mutability,
- classification,
- source of truth,
- audit behavior,
- offline/cache behavior,
- reality evidence.

Do not copy every v0.1 object-spec field automatically into production.

---

# Identity / Organization Subset

| Field | Exact type | Null | Owner | Classification | Source of truth | Offline/cache | Evidence | Status |
|---|---|---:|---|---|---|---|---|---|
| UserIdentity.id | TBD ID strategy | NO | Identity | INTERNAL | HILTECH | minimal | technical model | PROPOSED_FOR_REVIEW |
| UserIdentity.authSubject | String | NO | Identity | HIGHLY_RESTRICTED | Keycloak link | no UI cache | SPIKE-08 | LOCKED_TECHNICAL |
| UserIdentity.status | enum TBD exact | NO | Identity | INTERNAL | HILTECH | session bootstrap | reality + policy | REALITY_REQUIRED |
| Organization subset | TBD | TBD | Organizations | TBD | HILTECH/current source | context only | org reality | REALITY_REQUIRED |
| Team/membership subset | TBD | TBD | People/Organizations | INTERNAL | current org structure | assigned context | Mohamed/PM reality | REALITY_REQUIRED |

---

# Project

Required decisions before freeze:

| Field | Exact type | Null | Owner | Classification | Source | Local cache | Evidence | Status |
|---|---|---:|---|---|---|---|---|---|
| id | TBD ID strategy | NO | Projects | INTERNAL | HILTECH | YES | technical | PROPOSED_FOR_REVIEW |
| projectCode | TBD max/pattern | NO | Projects | INTERNAL | existing/new convention | YES | real project | REALITY_REQUIRED |
| name | TBD | NO | Projects | INTERNAL | current project source | YES | real project | REALITY_REQUIRED |
| clientOrganizationId | ID | NO | Projects | RESTRICTED | commercial/project handoff | YES-safe subset | real project | REALITY_REQUIRED |
| lifecycleState | exact enum | NO | Projects | INTERNAL | Projects | YES | real lifecycle | REALITY_REQUIRED |
| projectManagerId | ID | TBD | Projects | INTERNAL | authority/assignment | YES | real project | REALITY_REQUIRED |
| planned dates | LocalDate | TBD | Projects | INTERNAL | project planning | YES | real project | REALITY_REQUIRED |
| version | Long | NO | Projects | INTERNAL | server | YES | SPIKE-15 pattern | LOCKED_TECHNICAL |

Add/remove fields only from verified pilot reality.

---

# Site / Area

Freeze:
- exact project/site cardinality,
- reusable vs project-bound site identity,
- code/name fields,
- access/contact/location fields,
- Area/Zone hierarchy actually needed for pilot.

All exact rows remain `REALITY_REQUIRED` until one real project/site is mapped.

---

# WorkOrder

Technically fixed semantics:
- authoritative server version,
- offline-capable Start/Block/Resume/Submit,
- online authoritative Assign/Accept/Cancel,
- stale version cannot overwrite server truth,
- required evidence blocks acceptance/submission according policy.

Exact production fields to freeze:

| Field group | Decision needed | Evidence | Status |
|---|---|---|---|
| identity/code | exact code convention | field/project reality | REALITY_REQUIRED |
| project/site/area refs | exact required cardinality | real job | REALITY_REQUIRED |
| title/instruction | exact minimum instruction fields | real assignment | REALITY_REQUIRED |
| lifecycleState | exact production enum | transition review | PROPOSED_FOR_REVIEW |
| readinessState | exact checks + override semantics | field/PM reality | REALITY_REQUIRED |
| assignee/team | user/team/subcontractor cardinality | real assignment | REALITY_REQUIRED |
| supervisor/engineer | exact reviewer model | authority reality | REALITY_REQUIRED |
| planned/actual timestamps | exact meanings | field reality | REALITY_REQUIRED |
| priority | exact values | PM reality | REALITY_REQUIRED |
| evidencePolicyRef | exact policy model | work-type evidence | REALITY_REQUIRED |
| drawing/material/asset requirements | exact structures | real job | REALITY_REQUIRED |
| instructionVersion | exact type/meaning | field revision flow | REALITY_REQUIRED |
| version | Long optimistic lock | SPIKE-15 | LOCKED_TECHNICAL |

---

# Asset / Warehouse

Technically fixed:
- asset identity separate from tag,
- custody/location projection from append-only movement,
- one active custody,
- optimistic version/idempotency on checkout,
- no destructive movement history,
- calibration can block availability by policy.

Reality-required:
- asset classes,
- codes/serial rules,
- warehouse/storage hierarchy,
- checkout proof,
- return inspection,
- calibration classes,
- stock vs serialized split,
- units of measure.

Do not freeze exact enums/columns before walkthrough.

---

# Evidence

Technically fixed:
- metadata authoritative in DB,
- binary in S3-compatible storage,
- SHA-256 identity,
- signed direct upload,
- server finalization verifies stored bytes,
- restricted evidence has no permanent public URL.

Reality-required:
- evidence categories,
- mandatory evidence by work type,
- size/file-type limits,
- retention,
- client visibility.

---

# Global Technical Columns

Candidate baseline per authoritative mutable aggregate:

- `id`
- `version`
- `created_at`
- `created_by`
- `updated_at`

Do not mechanically add soft-delete or generic status columns to every table.
Deletion/retention must follow domain policy.
