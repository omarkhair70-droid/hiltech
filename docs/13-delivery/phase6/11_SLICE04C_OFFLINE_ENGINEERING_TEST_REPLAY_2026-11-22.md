# Phase 6 / Slice 04C — Durable Offline Engineering-Test Intent + Replay

Date: 2026-11-22

## Status

`P6_S04C_STRUCTURED_TEST_LIVE = IMPLEMENTED_DEFAULT_OFF`

`P6_S04C_STRUCTURED_TEST_OFFLINE_INTENT = IMPLEMENTED_PENDING_EXACT_HEAD_CI`

`P6_S04C_DRAWING_SOURCE = NOT_AVAILABLE`

This checkpoint extends the configurable engineering-test framework from PR #126. It does not define any HILTECH-specific OTDR, Fluke, Fiber or Copper thresholds.

## Core rule

A locally captured engineering-test row is an **intent**, never authoritative test acceptance.

The device may preserve technician-entered values only when the selected JobBundle was verified online in the current authenticated session and the current source explicitly exposes `engineeringTestCaptureOffline=true`.

Replay always returns to current server authority.

## Frozen replay order

1. existing field execution command replay;
2. Evidence reserve/upload/finalize;
3. engineering-test intent replay.

This order is intentional. A structured result may reference a locally captured raw report. The report must become canonical READY Evidence before its server Evidence ID can be attached to the engineering result.

## Captured immutable provenance

The local intent freezes:

- operation ID;
- actor identity;
- device installation;
- WorkOrder ID and version;
- assignment ID and version;
- WorkPolicy binding revision;
- EvidencePolicy ID and revision;
- requirement key;
- ENGINEERING_TEST_SCHEMA template ID, revision and schema version;
- test subject;
- entered values;
- optional opaque instrument AssetTag;
- optional raw-Evidence dependency;
- optional device-reported status;
- capture/client time;
- optional superseded result ID.

The queue does not freeze server authorization as authority.

## Same-Work mutation exclusion

A pending structured-test intent and a pending Start / Block / Resume command may not coexist for the same actor and WorkOrder.

This avoids silently rebasing a captured engineering result onto a different WorkOrder version.

If the server WorkOrder changes independently before replay, the intent becomes `REVIEW_REQUIRED`; it is not automatically rebased.

## Raw Evidence

If the required report is already READY on the current server JobBundle, the intent freezes that server Evidence ID.

If the report was captured locally, the intent freezes the local Evidence ID only when its provenance matches the exact current:

- WorkOrder;
- assignment;
- WorkOrder version;
- binding revision;
- EvidencePolicy ID/revision;
- requirement.

During replay:

- READY + finalized server ID → continue;
- still uploading/pending → retry later;
- rejected/discarded/missing/mismatched → REVIEW_REQUIRED.

No stale local Evidence is reused merely because the requirement key matches.

## Instrument

When the schema requires an instrument, local capture stores only the opaque AssetTag.

Replay resolves that tag again through the existing assigned-Work AssetTag endpoint. The resulting canonical Asset ID is sent to the server, which rechecks canonical Asset and calibration rules.

An unresolved tag becomes `REVIEW_REQUIRED`; it does not retry forever and is never converted into a fabricated Asset identity.

## Replay source gate

Before submission the client fetches the current JobBundle and requires an exact match for:

- WorkOrder ID/version;
- assignment ID/version;
- binding revision;
- EvidencePolicy ID/revision;
- requirement;
- schema template ID/revision/schemaVersion;
- source offline-capture capability.

A mismatch becomes `REVIEW_REQUIRED`.

The server then independently rechecks assignment, `can_submit_completion`, Work version, policy/schema revisions, Evidence, Asset and calibration before applying the idempotent command.

## Network ambiguity

The Android live path creates the operation ID before the first network attempt.

If the request is retryable because of transport/429/5xx failure, the same operation ID is persisted as a local intent. Therefore a server-side success whose response was lost can be recovered by idempotent replay without creating a second result.

401/403/source-hidden/stale-schema failures are not converted into offline authority.

## Room

Room schema moves from v3 to v4 with an additive auto-migration and new `local_engineering_test_intent` table.

The generated v4 schema is retained under:

`shared/core/schemas/com.hiltech.shared.core.local.HiltechLocalDatabase/4.json`.

## Rollout

The server continues to default:

`HILTECH_FIELD_ENGINEERING_TESTS_ENABLED=false`

The JobBundle advertises `engineeringTestCaptureOffline=true` only when the server engineering-test feature itself is enabled and the current Work/policy/authorization allow it.

Android engineering UI remains build-config gated by:

`hiltech.engineeringTestsEnabled=false`

No production feature is enabled by this checkpoint.

## Verification target

The checkpoint is complete only when an exact-head CI run proves:

- S04C server test engine;
- shared durable queue/replay tests;
- pre-existing field queue regression tests;
- Android compile with engineering tests and assigned AssetTag lookup enabled;
- Room v4 schema checked in;
- clean diff;
- base #126 remains green.

## Remaining S04 source gap

This checkpoint does **not** invent an authoritative Project/Work drawing-revision source.

Drawing current/superseded revision semantics remain a separate source/integration gate.
