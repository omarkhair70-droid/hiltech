# HILTECH P7 + Finance8I + NativeUX + Payroll9 + Sales10 source integration

Date 2026-11-23. Status: Draft / NO-GO for main, staging activation or company use.

## Sources

- Upstream Draft #201 source through 6862be810ca1f63b64ab45f55cdaff99ad052855 (already contains Phase7, Finance 8A-I, current native UX and Payroll9). Payroll provisional V0067-V0070 was added only on integrator branch; Payroll own source #166 may continue moving and will require repinning.
- Sales source PR #170 exact independently green head 332c67d9f024c575a0e9b8f531ae8dcf50583d5e; commercial isolation workflow 45096953084 and server package 45096953118 passed on owner source.
- This candidate merged the above P10 at isolated commit e3755864f5044566a50cd24e06a92f2af595c7a2.

## Cross-domain conflict resolution

Three shared documents files conflicted with P7 supplier/receiving/service Evidence plus P8 expense receipts:
- EvidenceLifecycleApi.kt retains all previous routes and installs Commercial CONTRACT/CLIENT_PO protected reserve and current access case with exact target org, high confidentiality and private view permission. Disabled Commercial port remains fail-closed without a live owner adapter.
- EvidencePersistence.kt retains previous restricted reservation structures and adds typed CommercialEvidenceReservationInsert plus explicit persistence port.
- JdbcEvidencePersistence.kt retains P7/P8 SQL reservations and adds owner Commercial reservation path with typed restricted target, correct PDF/image scan class and session idempotency. No generic arbitrary-file permission or public object access.
- No cross-owned owner source branch modified; source was reconciled in isolated worktree and conflicts are resolved statically. This is NOT a claim of successful runtime/security verification.

## Flyway integration contract

- Existing merged main versions V0001-V0038 untouched.
- P7 V0039-V0064 and P8 V0065-V0066 preserved; P9 V0067-V0070 preserved.
- Source P10 isolation schema at database/phase10/pending_commercial_schema.sql was byte-copied to database/migrations/V0071__phase10_commercial_sales_tenders.sql. Source wording/comments remain to maintain exact byte equality; the new FILE IS NOT AUTHORIZED FOR LIVE APPLY. Only disposable integration database test may run it.
- All versions V0001..V0071 contiguous and unique, post-V0033 migration-count test adjusted from 37 to 38.

## Exact merged-head verification required

1. devops-p7-finance-native-payroll-sales10.yml: all backend, Flyway, Spring Modulith, bootJar, KMP/Windows/Android, main history immutable.
2. phase10-commercial-isolation.yml: verifies P10 schema static source, canonical V0071 migration and negative/positive commercial PostgreSQL contract; on this INTEGRATION BRANCH ONLY it does not reapply original pending DDL because Flyway already owns the exact same tables.
3. phase9-payroll-9a-contract.yml: source, immutable release/corrections, no-payment-required and negative payroll tests.
4. phase8-finance-8i-accounting-review.yml: Finance supplier accounting approval/evidence regression.
5. Company policy/role deep links, actual protected Evidence upload (signed contract + client PO), canonical Project award conversion, downstream Finance8J and Service11 view dependencies, native shell routing, real OIDC PKCE + OpenFGA + recovery/restore on isolated staging remain open.

DO NOT merge to main or deploy. Commercial, payroll and financial side effects remain OFF. No supplier/client outbound, real money, employee salary inputs, company data, live DB migrations, Docker restarts or other shared-host changes have occurred during this source integration.