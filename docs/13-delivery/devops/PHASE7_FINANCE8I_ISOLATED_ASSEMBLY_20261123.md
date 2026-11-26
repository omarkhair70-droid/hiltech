# Phase 7 + Finance 8A-I: isolated integration assembly

Status: DRAFT, NO-GO for main, stage activation, finance posting or supplier dispatch.
Recorded: 2026-11-23. Owner: DevOps integration.

## Exact source lineage

- Main-based Phase7 full assembly: PR #193; tested source 3a09493a90d23d4c226b29db9515eab54a3878ba; exact-head run 45094319829 passed all three full backend/PostgreSQL, native and Flyway history jobs.
- Latest incorporated Finance source: phase8/finance-8i-supplier-accounting-review-20261123 at 1234052d95d001dd4515c43957fe2d02b11356b6 (PR #191; source still under active development).
- Isolated local merge commit: bad5b9916a748a07f190697e3c57432efee20e11. Two source-level conflicts resolved manually; this is a NEW assembled tree, not an automatic end-to-end green result.
- Branch: integration/phase7-finance8i-20261123; no main merge, production DB connection, runtime configuration or user-data mutation.

## Reviewable integration decisions

1. Preserve latest Procurement SERVICE_QUOTE_DOCUMENT and SERVICE_PURCHASE_DOCUMENT providers AND Finance FINANCE_EXPENSE_RECEIPT provider in EvidenceLifecycleService. No duplicated object store or alternate upload pipeline.
2. Restrict JdbcEvidencePersistence.insertRestrictedEvidenceReservation to all previously authorized types PLUS the Finance receipt target; do not loosen actor or organization checks, private Evidence classification, magic/hash/size verification, or independent review authority.
3. Keep already-merged main V0001-V0038 unchanged. Preserve tested Phase7 V0039-V0064. Move only Finance provisional 8A V0061 to V0065, 8B V0062 to V0066 in this integration branch. 1..66 are contiguous and unique. Company Finance source branches retain their original provisional version numbers until integrator approval.
4. All Finance operations are draft/review-only. Documentation approval does not post a payable, pay wages, fund an advance/imprest, recognize VAT, transfer cash, authorize a supplier email, or create a real bank transaction.
5. Phase7 service-order issue remains internal-only, with real company authorization and configured policy required before any external dispatch. Do not change default-OFF feature flags.

## Required exact-merged-head proof

- devops-phase7-finance8i-assembly.yml: entire server regression suite, disposable PostgreSQL 18 from V0001 through V0066, Modulith and bootJar, immutable Flyway baseline, KMP desktop tests, native Windows build and Android debug APK.
- phase8-finance-8i-accounting-review.yml: targeted finance source, ApprovalEngine, private Evidence receipt, signed upload, native/desktop Windows tests.
- Verify positive AND negative cross-company/revoked roles and source-version/reviewer constraints; review all fails before considering this integration successful.
- Follow later with Phase9, Phase10, Phase11, Phase13 and UX source bridges only after owner heads are immutable or exact-head green. Allocate their migrations uniquely AFTER V0066 and test real cross-module handoffs.
- Stage backend only after approved HTTPS/OIDC/OpenFGA/S3/backup and host capacity gates. Current Coolify inventory is not proof that deployed API or user acceptance exists.

This document is engineering evidence, not release authorization.