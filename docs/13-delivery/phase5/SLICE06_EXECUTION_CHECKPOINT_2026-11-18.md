# Slice 06 checkpoint 01 — Stock fulfilment persistence
Status: **DRAFT / CI UNVERIFIED / NOT OPERABLE**

Bootstrap 44355739554 passed on merge 41d8c3682f50db962aed151dc9dd4b9592cd7257.

V0031 starts with authoritative Stock issue, consume and unused return at the persistence layer. The established ledger is append-only; a matching reservation is ended atomically on exact whole-hold issue. The issued balance projection has a ledger-derived reconciliation view; no direct projection mutations. Demand already allocated through ISSUE less RETURN limits later reservations. No-login active Employee is a supported recipient. Neither consumption nor a failed command restores warehouse on-hand stock. Synthetic PostgreSQL contract extends the existing reservation scenario.

This checkpoint intentionally does not expose an API or claim operator usability. Whole-hold issue is the initial conservative path; partial/unreserved issue, approved policy, released/reacquired demand semantics, actor authority, current versions, idempotency, events and readiness after physical movement must be completed before release. Asset checkout/return/inspection/transfer and their projection, authorized Stock commands, shared KMP contracts, Windows and Android surfaces, all regression evidence and independent review remain open. Real HILTECH inventory and operator acceptance remain deferred. Do not merge this checkpoint alone.

## Checkpoint 02 — Stock server contracts (CI pending)
Adds scoped IssueReservedStock / ReturnUnused / Consume and issued-context API, current source Warehouse authority, version checks, idempotency, audit, Work event and fulfilment-aware resource fact. Authorization is deliberately conservative: actor must operate every origin location of the issued context, and any return destination. No external custodian or arbitrary unreserved issue enabled. Prior generic movement feed excludes issued context entries pending scoped history UI. Phase 4 workflow now includes V0031/fulfilment changes to execute the extended real PostgreSQL scenario. Service-level negative tests and UI/asset paths still required.

## Checkpoint 03 — Asset physical custody (CI pending)
Implemented core V0031 reserved checkout, employee custody, due return, same-home warehouse storage transfer and basic immutable inspection tied to the actual return. Adds server commands with current Warehouse permissions, versions, idempotency, audit, existing custody authorization projection, and Work readiness facts. Cross-warehouse/employee reassignment, typed accessory inspection and complete operator UI remain open. Basic inspection fails closed for accessory-required types and requires an existing open damage incident for observed damage. No new policy inferred. Synthetic database scenarios are not yet verified on this head.


## Checkpoint 04 — Desktop physical operations
Adds outstanding issued-stock discovery scoped to the selected Warehouse and every contributing origin, by current employee code/name, including non-active Work for return. Returns explicit Work/issued versions and canonical unit. Desktop uses existing stable pending-command identity for consume/return and Asset return/storage transfer/inspection. Basic inspection remains blocked for accessory-controlled types; damage links use existing incident records. Exact-head CI pending. No Android mutation surfaces claimed.

## Checkpoint 05 — Android online physical operator (WIP)

Reuses shared Compose physical handoff, Asset return/storage transfer/basic inspection and issued Stock return/consumption controls inside the signed-in Android shell. Uses authorized warehouse discovery, current API context, explicit employee selection, online server receipts, and actor/installation/payload-scoped stable operation IDs. No technician offline execution, administrative setup, accessory-controlled release, or broadened custody policy. Previous d2ab1ce workflow runs all succeeded (13/13); Android operator build and interactive evidence are not yet verified for this new checkpoint. Dedicated Android mutation journey tests, access/session regression, scoped movement history and remaining frozen contract gates still required. Draft only.
