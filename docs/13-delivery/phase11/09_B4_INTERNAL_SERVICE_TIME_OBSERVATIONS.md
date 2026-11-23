# Phase 11B4 — internal service timing observations (not an SLA entitlement)

Owner: Phase11 source branch phase11/handover-service-20261122, Draft PR #167. Feature implementation only, not merged or deployed.

## Business intent and exact limits

The original immutable service_support_ticket contains the opened-at timestamp. Phase11B3 has immutable internal case events for investigation, resolution, closure and reopening. P11B4 adds an **independent, append-only internal observation ledger** recording first manager attention and reasoned periods when the internal investigation is on hold. A hold note is NOT an SLA pause. This release does not calculate SLA targets, warnings, breach classifications, contractual allowance, customer first response, warranty or maintenance eligibility.

### Source objects and operations

- `service_support_time_event` is linked to the exact ticket, original organization and project via a composite foreign key. A SQL trigger takes the source ticket row lock shared with case and triage events; enforces monotonic contiguous event sequence, server timestamp bounds, current non-resolved case state, exactly one first-internal-attention, and pairs HOLD_STARTED with HOLD_ENDED. Events never mutate after creation.
- A secondary PostgreSQL trigger rejects source case RESOLUTION_RECORDED and INTERNAL_CLOSE_RECORDED while the latest internal hold is still open. It also protects direct SQL writes bypassing the API.
- Manager-only `GET /v1/projects/{projectId}/support-tickets/{ticketId}/internal-time`: original ticket opened-at, source case state, last event number, observed wall seconds, time to first **internal** attention, open hold state and observed internal hold seconds, all derived from the complete append-only timeline. `contractualSlaState=NOT_CONFIGURED`, `holdSlaPauseApprovalState=NOT_APPROVED`, contractual deadlines null and customerResponseConfirmed=false.
- `GET /.../internal-time/events`: latest 500 events, chronologically displayed; summary uses the entire source timeline, not the truncated events.
- `POST /.../internal-time/events`: accepts INTERNAL_ATTENTION_RECORDED, INTERNAL_HOLD_STARTED, INTERNAL_HOLD_ENDED with mandatory reason, source authorization, operation-id replay protection, expected last event number and audit trail. It cannot send an email or mark a client response.
- The API requires both `hiltech.phase11.support.enabled` and `hiltech.phase11.support.time.enabled`; both default OFF. An authorized Project manager can record internal observations. Technician or client access is not implied.
- KMP authenticated typed client and standalone `SupportInternalTimeSection` Compose page are staged for future Android/Windows role-aware shell mounting. Not a live operator experience yet.

### Tests and integration

- Focused server policy tests: first-attention once, hold alternation, elapsed historical duration (open and closed holds), never-computed contractual SLA, invalid commands.
- Native KMP MockEngine tests: session token, installation identity, idempotent operation key, real route, no fabricated SLA/case/client response, and client input rejection.
- PostgreSQL source proof: positive internal attention/hold; rejects consecutive unpaired holds, rejects immutable history rewrite, rejects resolving canonical accepted/READY evidence-linked WorkOrder while a hold is active, then records actual HOLD_ENDED and validates successful source-bound resolution. Entire test transaction rolls back.
- Stage **ninth** pending P11 migration: `11B4_support_internal_time.sql` after B3 and before 11C. DevOps assigns nonconflicting Flyway migration numbers, proves merge+rollback/staging with other phases, and tests real PM/technician/revocation/Windows/Android.

### Actual SLA/contract blockers

The signed award and PO evidence from Phase10 are not themselves authorization to interpret warranty period, support coverage, business-hour calendars, pause rules, escalation thresholds or response/resolution targets. Company policies, signed terms, an approval authority, supported holidays/timezones and permitted client communication still must be supplied and configured before exposing an official SLA timer, expiring coverage or billing. Do not claim this work finishes contracted SLA.

At creation, exact-head CI is not yet reported green. Keep the PR Draft and all feature flags disabled until source and combined operator gates pass.