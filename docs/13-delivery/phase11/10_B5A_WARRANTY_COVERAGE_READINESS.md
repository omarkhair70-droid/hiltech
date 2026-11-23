# Phase 11B5a — warranty coverage readiness, not a warranty grant

Owner: Phase 11 PR #167. This is a **read-only, internal, fail-closed** checkpoint over existing P10 contract-source and P11 support-ticket records. No numbered migration, new customer contract, duplicate asset register or warranty term parser was added.

## Actual workflow

- Project manager uses `GET /v1/projects/{projectId}/support-tickets/{ticketId}/warranty-readiness`; requires both `hiltech.phase11.support.enabled=true` and `hiltech.phase11.warranty.enabled=true`. Both are OFF until authorized integration. A real project-manager permission is verified against canonical Project **before** ticket lookup; a ticket from another project returns not found.
- The endpoint reads `ProjectServiceContractSourceService` (provider-owned P10 signed contract/PO handoff and evidence metadata when deployed) and the original `service_support_ticket`. The ticket must belong to the requested Project.
- Assessment returns explicit blocker codes for missing P10 source, missing signed commercial award, unverified contract/PO storage, or award revalidation. It also identifies missing written terms, company warranty policy approval, actual asset/site scope, incident/exclusion review and approved warranty start/end.
- Even if P10 reports READY signed storage, written terms, effective/end dates and a closed internal ticket, this is **not** permission to infer warranty validity. The decision remains `NOT_EVALUATED`, approval `NOT_APPROVED`, and asset/site verification `NOT_VERIFIED`. No SLA, customer notification, paid work or invoice eligibility.
- Server has a pure policy and unit tests for absent, partial, ready and unknown sources, cross-project mismatch, and fail-closed output. A typed authenticated KMP GET client and standalone Compose manager view have a MockEngine native contract test. UX owns global Windows/Android shell mounting.

## What B5a deliberately does not complete

- Actual B5 **coverage engine** needs a company-authorized structured interpretation of the exact signed contract revision: covered equipment/site IDs, included faults, exclusions, trigger/date interval, approval identity, and policy version, with an auditable approval path. P10's source currently exposes only raw warranty text and contract metadata. DO NOT parse free text into legally binding coverage or invent company approvals.
- Contractual SLA needs approved calendars, response/resolution targets, pause rules and customer-communication evidence. P11B4 internal holds are NOT contractual pauses.
- P11C periodic visit generation and renewals require approved schedule and commercial policy. P7 parts and P8 billing must remain in their owning services.
- DevOps still owns unique Flyway numbers for existing nine P11 migrations, combined migrations/rollback, integration, and deployment. All P11 flags remain OFF, PR Draft.

The B5a tests should be verified on the exact commit through the P11 source workflow and independent Spring Boot boot/build. Do not claim green until those runs finish.
