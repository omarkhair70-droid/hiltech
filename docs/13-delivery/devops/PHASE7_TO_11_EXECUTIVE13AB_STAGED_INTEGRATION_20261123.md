# Source-bound Executive 13A/13B on integrated Phase 7/8/9/10/11 and native UX

Date: 2026-11-23. Status: DRAFT / NO GO for main or staging live use.

## Verified source provenance

- Parent #203 at 42d0a2dadd7efed6a2a5c7dd063cd4f6ef535049 has Service11 source staged after independently green exact-head parent #202 (aaac5dc11b11d6fadd7aafa34107d020c189441e). Parent #203 itself requires full exact merged-head CI.
- Executive13A source is stacked in Executive13B #197 at 4af86a049c7510ec7a6bacc32b4d533c96cc4e6e (base #168 e7dc4d083db2878c289ca26fe5696b0e56be44a4), exactly owner CI 45098053565 passed.
- Executive13A and 13B source merged cleanly onto parent. No additional SQL, schema changes or role permission broadenings. SQL sequence remains canonical V0001-V0079 and current operator feature flags remain OFF.
- Executive source implements a read-only actor/organization-authorized Executive Home and an on-demand operating brief over the same source DTO, with constrained partial/unknown semantics; it does not infer salaries, finance values, SLA, client acceptance, external messages or a company-wide work queue.

## Exact integrated verification required

1. devops-p7to11-executive13ab.yml: whole Spring server/Flyway/PostgreSQL and full desktop/native Android and Windows compilers.
2. phase13-executive-contract.yml: Executive source server tests/architecture, KMP read-only brief and native compilers on the exact integrated branch.
3. Phase8/9/10/11 specialized regressions re-run on this integrated branch, including private Evidence/document access, Payroll employee isolation, Commercial canonical signed-client source and P11 service follow-up/maintenance.
4. Real company actor/org permission checks with current OIDC and OpenFGA policy, no access to employee-level compensation, signed-in device navigation, staging data and backup restoration are NOT implied by CI. Executive route must be mounted and feature access explicitly approved before release.
5. Finance8J view is not yet included; it remains separately owned, and its source result must not be guessed or exposed as ready.

No merge to main, company DB migration, service activation, supplier/client send, payroll funds, live ObjectStore or Oracle/Coolify changes happened during this source integration.