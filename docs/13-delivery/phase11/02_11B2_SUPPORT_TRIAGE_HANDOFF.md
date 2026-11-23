# P11B2 — internal support triage: implementation contract

Owner branch: phase11/handover-service-20261122. All work is draft; not merged or deployed.
Schema: database/pending/phase11/11B2_support_triage.sql, after P11B. DevOps allocates a conflict-free Flyway number alongside P7/P8/P9/P10.

## Implemented slice
- Existing Project, client context and support ticket are authoritative.
- Append-only version-numbered internal triage events: OWNER_ASSIGNED, OWNER_RELEASED and CLASSIFICATION_RECORDED.
- A desk owner references an Employee in the same organization with ACTIVE employment at event time (DB trigger + server).
- No WorkOrder, workforce assignment, purchasing, event notification or schedule is created. Original ticket remains immutable; classification events are dated internal judgments, not contractual SLAs.
- Native KMP SupportTriageApiClient and Compose SupportTriageSection with real employee choices furnished by the existing People source.
- API: GET/POST /v1/projects/{projectId}/service-tickets/{ticketId}/triage. Project management authorization before reads and writes. POST requires matching Idempotency-Key, expected latest event number, a reason and exact action fields. Ticket row lock serializes concurrent operations.
- Records remain internal-only, the same support feature flag hiltech.phase11.support.enabled defaults OFF.

## Fail-closed limitations
No final incident resolution/reopen, contractual response clocks, warranty eligibility, billable hours, service acceptance, auto-assignment or client communication. P10 contract and company service policies are not yet available.
The UI intentionally does not create employees; it uses People-sourced choices.

## Integration verification
1. Clean + incremental PostgreSQL migrations with sibling PR migration order. Validate FKs, ACTIVE employment trigger, foreign-org rejection, delete/update/truncate immutability, unique event ordering.
2. Auth: foreign project denied; authorized manager only; revoked manager denied; identity from trusted session, not header.
3. Idempotency: same operation/payload replay gives original event; reuse with different payload rejected; simultaneous same expected-event-number results one success and one conflict.
4. Employee: former/preboarding/offboarding rejected; wrong organization rejected; no active employment rejected. OWNER_RELEASED has no employeeId field, but is legal only if the same ticket has a currently assigned owner. Duplicate assignment to the same Employee is rejected; reassignment to a different ACTIVE Employee remains an audited change.
5. Assert no SLA, coverage, original ticket state/classification, WorkOrder, procurement, invoice or client notification modified as a side effect.
6. Wire authenticated native Android and Windows UI journeys on integration-owned shared-shell files and test under real sessions.


## Current owner and truthful recent history (2026-11-23)
- B3 PostgreSQL adds the triage guard after B2: both service and SQL reject OWNER_RELEASED without an existing assigned owner and repeat OWNER_ASSIGNED with the same current owner. A ticket lock serializes owner and case changes.
- JdbcSupportTriageStore.currentOwner derives the most recent owner assignment or release independently of later classification events. No SLA or workforce WorkOrder assignment is inferred.
- GET /triage returns the newest 500 events in ascending order within that window; the former oldest-500 behavior hid current activity after long histories. If the most recent window lacks an owner transition, the native screen reports owner unknown rather than pretending there is no owner.
- Transactional SQL tests reject nonexistent owner releases. JUnit policy tests cover release, reassignment and duplicates. Native tests cover bounded-history uncertainty.
- Internal support ticket read now returns the latest derived case state alongside the immutable original OPEN intake, and the Service Desk shows both instead of always displaying OPEN.
