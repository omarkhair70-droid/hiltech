# Slice 06 checkpoint 01 — Stock fulfilment persistence
Status: **DRAFT / CI UNVERIFIED / NOT OPERABLE**

Bootstrap 44355739554 passed on merge 41d8c3682f50db962aed151dc9dd4b9592cd7257.

V0031 starts with authoritative Stock issue, consume and unused return at the persistence layer. The established ledger is append-only; a matching reservation is ended atomically on exact whole-hold issue. The issued balance projection has a ledger-derived reconciliation view; no direct projection mutations. Demand already allocated through ISSUE less RETURN limits later reservations. No-login active Employee is a supported recipient. Neither consumption nor a failed command restores warehouse on-hand stock. Synthetic PostgreSQL contract extends the existing reservation scenario.

This checkpoint intentionally does not expose an API or claim operator usability. Whole-hold issue is the initial conservative path; partial/unreserved issue, approved policy, released/reacquired demand semantics, actor authority, current versions, idempotency, events and readiness after physical movement must be completed before release. Asset checkout/return/inspection/transfer and their projection, authorized Stock commands, shared KMP contracts, Windows and Android surfaces, all regression evidence and independent review remain open. Real HILTECH inventory and operator acceptance remain deferred. Do not merge this checkpoint alone.
