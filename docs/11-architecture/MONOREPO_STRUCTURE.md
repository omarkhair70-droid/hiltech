# HILTECH Monorepo Structure

Status: PROPOSED v0.1 / NOT BOOTSTRAPPED

## Goal
When implementation begins, repository structure should already express product/domain ownership.

This structure is intentionally concrete enough for BUILD-READY planning but remains subject to technical spikes.

```text
hiltech/
│
├── README.md
├── docs/
│   └── ... planning / architecture / ADR / research
│
├── apps/
│   ├── androidApp/
│   ├── desktopApp/
│   ├── iosApp/                 # later; may exist empty only after bootstrap policy
│   └── public-web/             # only if current website is migrated into this repo
│
├── shared/
│   ├── core/
│   │   ├── model/
│   │   ├── auth/
│   │   ├── network/
│   │   ├── database/
│   │   ├── sync/
│   │   ├── files/
│   │   ├── notifications/
│   │   ├── telemetry/
│   │   ├── validation/
│   │   ├── time/
│   │   └── testing/
│   │
│   ├── design/
│   │   ├── tokens/
│   │   ├── components/
│   │   ├── icons/
│   │   ├── motion/
│   │   └── adaptive/
│   │
│   └── features/
│       ├── home/
│       ├── approvals/
│       ├── search/
│       ├── inbox/
│       ├── projects/
│       ├── field/
│       ├── engineering/
│       ├── people/
│       ├── onboarding/
│       ├── warehouse/
│       ├── assets/
│       ├── procurement/
│       ├── finance/
│       ├── payroll/
│       ├── sales/
│       ├── clients/
│       ├── suppliers/
│       ├── subcontractors/
│       ├── security/
│       └── selfservice/
│
├── server/
│   ├── application/            # runnable Spring Boot app/composition root
│   ├── modules/
│   │   ├── identity/
│   │   ├── organizations/
│   │   ├── people/
│   │   ├── sales/
│   │   ├── projects/
│   │   ├── work/
│   │   ├── engineering/
│   │   ├── assets/
│   │   ├── warehouse/
│   │   ├── procurement/
│   │   ├── finance/
│   │   ├── payroll/
│   │   ├── clients/
│   │   ├── partners/
│   │   ├── approvals/
│   │   ├── documents/
│   │   ├── inbox/
│   │   ├── notifications/
│   │   ├── security/
│   │   ├── automation/
│   │   ├── integrations/
│   │   └── audit/
│   │
│   └── testing/
│       ├── architecture/
│       ├── integration/
│       └── fixtures/
│
├── contracts/
│   ├── api/
│   ├── events/
│   └── schemas/
│
├── database/
│   ├── migrations/
│   ├── seeds/
│   └── test-fixtures/
│
├── infrastructure/
│   ├── local/
│   ├── staging/
│   ├── production/
│   ├── observability/
│   └── scripts/
│
├── tools/
│   ├── codegen/
│   ├── dev/
│   ├── release/
│   └── data/
│
└── .github/
    ├── workflows/
    ├── CODEOWNERS
    └── pull_request_template.md
```

---

# Rules

## 1. No folder because “we might need it”
Bootstrap only frozen/approved roots and modules.

## 2. Features do not own authoritative backend data
Client feature modules consume contracts/use cases.

## 3. Server module owns persistence
No global `repositories/` folder.

## 4. Shared does not mean everything shared
Platform-specific code remains in app/platform source sets.

## 5. Contracts need ownership
Do not create manually duplicated DTOs in five modules.

Exact API contract/codegen strategy remains open.

## 6. Migrations map to owning modules
Physical migration folder organization must preserve ordering while still exposing ownership.

## 7. Tests live near code plus cross-system test suites
Do not build a detached test monolith.

---

# Build Tool Direction

Likely Gradle multi-project for Kotlin/KMP/server.

Potential:
- version catalog.
- convention plugins.
- build-logic.
- Detekt/Ktlint or chosen static analysis.
- dependency locking/verification.

Not frozen.

---

# Repository Question — Public Website

Current public HILTECH website exists in another repository.

Options:
A. Keep public site repo separate.
B. Migrate it into HILTECH monorepo later.

Current preference:
Keep separate initially to avoid coupling marketing deploy lifecycle to company OS, unless unified repo provides a clear operational benefit.

## Completion gate
Requires successful KMP/server bootstrap spike and finalized module dependency graph.
